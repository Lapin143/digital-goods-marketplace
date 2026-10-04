import test, { after, afterEach, before } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createPublicKey, createVerify } from 'node:crypto';
import { startHarness } from './harness.mjs';

let h;
before(async () => {
  h = await startHarness();
});
after(() => h.stop());
afterEach(() => h.reset());

const REDIRECT = 'https://keycloak.test/realms/dgm/broker/vkid/endpoint';
const verifier = 'v'.repeat(50);
const challenge = createHash('sha256').update(verifier).digest('base64url');
const authorize = (extra = {}) => {
  const q = new URLSearchParams({ response_type: 'code', client_id: 'dgm', redirect_uri: REDIRECT, state: 'st-1', nonce: 'n-1', scope: 'openid email', code_challenge: challenge, code_challenge_method: 'S256', ...extra });
  return h.api()('GET', `/vkid/authorize?${q}`, { redirect: 'manual' });
};
const token = (code, extra = {}) =>
  h.api()('POST', '/vkid/token', { raw: new URLSearchParams({ grant_type: 'authorization_code', code, redirect_uri: REDIRECT, client_id: 'dgm', client_secret: 's', code_verifier: verifier, ...extra }).toString(), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
const codeOf = (r) => new URL(r.headers.get('location')).searchParams.get('code');

test('описание провайдера: адреса, подпись RS256, PKCE', async () => {
  const r = await h.api()('GET', '/vkid/.well-known/openid-configuration');
  assert.equal(r.status, 200);
  assert.equal(r.json.issuer, 'http://stubs.test/vkid');
  assert.equal(r.json.authorization_endpoint, 'http://stubs.test/vkid/authorize');
  assert.deepEqual(r.json.id_token_signing_alg_values_supported, ['RS256']);
  assert.ok(r.json.code_challenge_methods_supported.includes('S256'));
});

test('вход: код авторизации, обмен на токены, профиль, подпись id_token проверяется по jwks', async () => {
  const a = await authorize();
  assert.equal(a.status, 302);
  const loc = new URL(a.headers.get('location'));
  assert.equal(`${loc.origin}${loc.pathname}`, REDIRECT);
  assert.equal(loc.searchParams.get('state'), 'st-1');
  const t = await token(loc.searchParams.get('code'));
  assert.equal(t.status, 200);
  assert.equal(t.json.token_type, 'Bearer');

  const [head, payload, sig] = t.json.id_token.split('.');
  const header = JSON.parse(Buffer.from(head, 'base64url'));
  const claims = JSON.parse(Buffer.from(payload, 'base64url'));
  const jwks = (await h.api()('GET', '/vkid/jwks')).json;
  const jwk = jwks.keys.find((k) => k.kid === header.kid);
  assert.ok(jwk, 'ключ с нужным kid есть в jwks');
  assert.equal(header.alg, 'RS256');
  const ok = createVerify('RSA-SHA256').update(`${head}.${payload}`).verify(createPublicKey({ key: jwk, format: 'jwk' }), Buffer.from(sig, 'base64url'));
  assert.equal(ok, true);
  assert.equal(claims.iss, 'http://stubs.test/vkid');
  assert.equal(claims.aud, 'dgm');
  assert.equal(claims.nonce, 'n-1');
  assert.equal(claims.sub, 'vk_100500');
  assert.ok(claims.exp - claims.iat === 3600);

  const info = await h.api(t.json.access_token)('GET', '/vkid/userinfo');
  assert.equal(info.status, 200);
  assert.equal(info.json.email, 'ivan.petrov@example.test');
  assert.equal(info.json.email_verified, true);
});

test('профиль меняется режимом: другой пользователь VK ID', async () => {
  await h.setMode('vkid', { profile: { sub: 'vk_777', email: 'anna@example.test', name: 'Анна Смирнова' } });
  const t = await token(codeOf(await authorize()));
  const info = await h.api(t.json.access_token)('GET', '/vkid/userinfo');
  assert.deepEqual([info.json.sub, info.json.email, info.json.name], ['vk_777', 'anna@example.test', 'Анна Смирнова']);
});

test('режим «отказ пользователя»: возврат с error=access_denied и тем же state', async () => {
  await h.setMode('vkid', { behavior: 'deny' });
  const a = await authorize();
  const loc = new URL(a.headers.get('location'));
  assert.equal(loc.searchParams.get('error'), 'access_denied');
  assert.equal(loc.searchParams.get('state'), 'st-1');
  assert.equal(loc.searchParams.get('code'), null);
});

test('режим «сбой провайдера»: возврат с error=server_error', async () => {
  await h.setMode('vkid', { behavior: 'error' });
  assert.equal(new URL((await authorize()).headers.get('location')).searchParams.get('error'), 'server_error');
});

test('код одноразовый, привязан к клиенту, redirect_uri и code_verifier', async () => {
  const code = codeOf(await authorize());
  assert.equal((await token(code, { code_verifier: 'чужой' })).json.error, 'invalid_grant', 'неверный verifier; код при этом сгорает');
  assert.equal((await token(code)).json.error, 'invalid_grant', 'использованный код не принимается');
  assert.equal((await token(codeOf(await authorize()), { redirect_uri: 'https://evil.test/cb' })).json.error, 'invalid_grant');
  assert.equal((await token(codeOf(await authorize()), { client_id: 'другой' })).json.error, 'invalid_grant');
  assert.equal((await token('нет-такого')).json.error, 'invalid_grant');
  assert.equal((await token(code, { grant_type: 'password' })).json.error, 'unsupported_grant_type');
});

test('запрос авторизации без обязательных параметров отвергается', async () => {
  assert.equal((await authorize({ response_type: 'token' })).status, 400);
  assert.equal((await authorize({ redirect_uri: 'javascript:alert(1)' })).status, 400);
  assert.equal((await authorize({ client_id: '' })).status, 400);
});

test('userinfo без токена или с чужим токеном отвечает 401', async () => {
  assert.equal((await h.api()('GET', '/vkid/userinfo')).status, 401);
  assert.equal((await h.api('чужой')('GET', '/vkid/userinfo').catch(() => ({ status: 401 }))).status, 401);
});
