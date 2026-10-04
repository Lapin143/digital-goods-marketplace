// VK ID: упрощённый провайдер OpenID Connect (код авторизации с PKCE). Для платформы выглядит как внешний провайдер входа.
import { createHash, createSign, generateKeyPairSync } from 'node:crypto';
import { HttpError, sendEmpty, sendJson, sleep } from './util.mjs';

const b64u = (input) => Buffer.from(input).toString('base64url');

let keyPair;
/** Ключ подписи создаётся при первом обращении: старт заглушки не ждёт генерации RSA. */
function signingKey() {
  if (!keyPair) {
    const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
    const jwk = publicKey.export({ format: 'jwk' });
    const kid = createHash('sha256').update(`${jwk.n}.${jwk.e}`).digest('base64url').slice(0, 16);
    keyPair = { privateKey, jwk: { ...jwk, kid, use: 'sig', alg: 'RS256' }, kid };
  }
  return keyPair;
}

export function signJwt(claims) {
  const { privateKey, kid } = signingKey();
  const head = b64u(JSON.stringify({ alg: 'RS256', typ: 'JWT', kid }));
  const payload = b64u(JSON.stringify(claims));
  const signature = createSign('RSA-SHA256').update(`${head}.${payload}`).sign(privateKey).toString('base64url');
  return `${head}.${payload}.${signature}`;
}

export function registerVkid(router, app) {
  const { state, config } = app;
  const issuer = () => `${config.publicUrl}/vkid`;
  const redirect = (res, uri, params) => {
    const target = new URL(uri);
    for (const [k, v] of Object.entries(params)) if (v !== undefined) target.searchParams.set(k, v);
    sendEmpty(res, 302, { Location: target.toString(), 'Cache-Control': 'no-store' });
  };

  router.add('GET', '/vkid/.well-known/openid-configuration', async ({ res }) => {
    sendJson(res, 200, {
      issuer: issuer(),
      authorization_endpoint: `${issuer()}/authorize`,
      token_endpoint: `${issuer()}/token`,
      userinfo_endpoint: `${issuer()}/userinfo`,
      jwks_uri: `${issuer()}/jwks`,
      response_types_supported: ['code'],
      subject_types_supported: ['public'],
      id_token_signing_alg_values_supported: ['RS256'],
      scopes_supported: ['openid', 'profile', 'email', 'phone'],
      token_endpoint_auth_methods_supported: ['client_secret_post', 'client_secret_basic', 'none'],
      code_challenge_methods_supported: ['S256', 'plain'],
      claims_supported: ['sub', 'name', 'given_name', 'family_name', 'email', 'email_verified', 'phone_number'],
    });
  });

  router.add('GET', '/vkid/jwks', async ({ res }) => sendJson(res, 200, { keys: [signingKey().jwk] }));

  router.add('GET', '/vkid/authorize', async ({ res, query }) => {
    const { response_type: type, client_id: clientId, redirect_uri: redirectUri } = query;
    if (type !== 'code') throw new HttpError(400, 'unsupported_response_type', 'Поддерживается только response_type=code');
    if (!clientId) throw new HttpError(400, 'invalid_request', 'Нужен client_id');
    if (!redirectUri || !/^https?:\/\//.test(redirectUri)) throw new HttpError(400, 'invalid_request', 'Нужен redirect_uri (http или https)');
    const mode = state.modes.vkid;
    if (mode.delayMs) await sleep(mode.delayMs);
    if (mode.behavior === 'deny') {
      return redirect(res, redirectUri, { error: 'access_denied', error_description: 'Пользователь отказал в доступе', state: query.state });
    }
    if (mode.behavior === 'error') {
      return redirect(res, redirectUri, { error: 'server_error', error_description: 'Провайдер временно недоступен', state: query.state });
    }
    const code = state.id('code');
    state.vk.codes.set(code, {
      clientId,
      redirectUri,
      nonce: query.nonce,
      challenge: query.code_challenge,
      method: query.code_challenge_method ?? 'plain',
      scope: query.scope ?? 'openid',
      profile: structuredClone(mode.profile),
      expiresAt: app.clock.now().getTime() + 60_000,
    });
    redirect(res, redirectUri, { code, state: query.state });
  });

  router.add('POST', '/vkid/token', async ({ req, res, body }) => {
    const fail = (error, description) => sendJson(res, 400, { error, error_description: description });
    const form = body && typeof body === 'object' ? body : {};
    if (form.grant_type !== 'authorization_code') return fail('unsupported_grant_type', 'Поддерживается authorization_code');
    const basic = /^Basic\s+(.+)$/i.exec(req.headers.authorization || '');
    const clientId = form.client_id ?? (basic ? Buffer.from(basic[1], 'base64').toString().split(':')[0] : undefined);
    const entry = state.vk.codes.get(form.code);
    state.vk.codes.delete(form.code); // код одноразовый
    if (!entry || entry.expiresAt < app.clock.now().getTime()) return fail('invalid_grant', 'Код неизвестен, использован или истёк');
    if (entry.clientId !== clientId) return fail('invalid_grant', 'Код выдан другому клиенту');
    if (entry.redirectUri !== form.redirect_uri) return fail('invalid_grant', 'redirect_uri не совпал с запросом авторизации');
    if (entry.challenge) {
      const verifier = form.code_verifier ?? '';
      const expected = entry.method === 'S256' ? createHash('sha256').update(verifier).digest('base64url') : verifier;
      if (expected !== entry.challenge) return fail('invalid_grant', 'code_verifier не совпал с code_challenge');
    }
    const now = Math.floor(app.clock.now().getTime() / 1000);
    const accessToken = state.id('vkat');
    state.vk.tokens.set(accessToken, { profile: entry.profile, expiresAt: (now + 3600) * 1000 });
    const idToken = signJwt({
      iss: issuer(),
      aud: clientId,
      sub: entry.profile.sub,
      iat: now,
      exp: now + 3600,
      ...(entry.nonce ? { nonce: entry.nonce } : {}),
      name: entry.profile.name,
      email: entry.profile.email,
      email_verified: entry.profile.email_verified,
    });
    sendJson(res, 200, { access_token: accessToken, token_type: 'Bearer', expires_in: 3600, id_token: idToken, scope: entry.scope });
  });

  router.add('GET', '/vkid/userinfo', async ({ req, res }) => {
    const m = /^Bearer\s+(\S+)$/i.exec(req.headers.authorization || '');
    const entry = m && state.vk.tokens.get(m[1]);
    if (!entry || entry.expiresAt < app.clock.now().getTime()) {
      return sendJson(res, 401, { error: 'invalid_token' }, { 'WWW-Authenticate': 'Bearer error="invalid_token"' });
    }
    sendJson(res, 200, entry.profile);
  });
}
