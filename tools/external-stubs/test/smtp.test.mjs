import test, { after, afterEach, before } from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import { startHarness } from './harness.mjs';
import { decodeWords, parseMessage } from '../src/mime.mjs';

let h;
before(async () => {
  h = await startHarness();
});
after(() => h.stop());
afterEach(() => h.reset());

/** Мини-клиент SMTP: отправляет команды по очереди и возвращает ответы сервера. */
function smtp(lines, { raw = false } = {}) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(h.ports.smtpPort, '127.0.0.1');
    socket.setEncoding('utf8');
    const replies = [];
    let buffer = '';
    let i = 0;
    socket.on('data', (chunk) => {
      buffer += chunk;
      let m;
      while ((m = /^(\d{3})([ -])(.*)\r\n/.exec(buffer))) {
        buffer = buffer.slice(m[0].length);
        if (m[2] === ' ') {
          replies.push(`${m[1]} ${m[3]}`);
          if (i < lines.length) socket.write(raw ? lines[i++] : `${lines[i++]}\r\n`);
          else socket.end();
        }
      }
    });
    socket.on('close', () => resolve(replies));
    socket.on('error', reject);
  });
}

const MIME = [
  'From: =?UTF-8?B?0JzQsNCz0LDQt9C40L0=?= <noreply@dgm.test>',
  'To: buyer@example.test',
  'Subject: =?UTF-8?B?0J/QvtC00YLQstC10YDQtNC40YLQtSDQv9C+0YfRgtGD?=',
  'MIME-Version: 1.0',
  'Content-Type: multipart/alternative; boundary="b1"',
  '',
  '--b1',
  'Content-Type: text/plain; charset=UTF-8',
  'Content-Transfer-Encoding: quoted-printable',
  '',
  '=D0=9F=D0=B5=D1=80=D0=B5=D0=B9=D0=B4=D0=B8=D1=82=D0=B5 =D0=BF=D0=BE =D1=81=D1=81=D1=8B=D0=BB=D0=BA=D0=B5: http://x.test/confirm?t=3D1',
  '--b1',
  'Content-Type: text/html; charset=UTF-8',
  'Content-Transfer-Encoding: base64',
  '',
  Buffer.from('<p>Привет</p>').toString('base64'),
  '--b1--',
  '',
  '.',
];

// Тело письма уходит одним блоком: сервер отвечает только после строки с точкой
const dialog = (data) => ['EHLO keycloak', 'MAIL FROM:<noreply@dgm.test>', 'RCPT TO:<buyer@example.test>', 'DATA', data.join('\r\n'), 'QUIT'];

test('письмо Keycloak принято: тема, текст и HTML разобраны из base64, quoted-printable и RFC 2047', async () => {
  const replies = await smtp(dialog([MIME.join('\r\n')]));
  assert.deepEqual(replies.map((r) => r.slice(0, 3)), ['220', '250', '250', '250', '354', '250', '221']);
  assert.equal(h.state.emails.length, 1);
  const m = h.state.emails[0];
  assert.equal(m.sender, 'smtp');
  assert.equal(m.via, 'smtp');
  assert.deepEqual(m.to, ['buyer@example.test']);
  assert.equal(m.subject, 'Подтвердите почту');
  assert.equal(m.text, 'Перейдите по ссылке: http://x.test/confirm?t=1');
  assert.equal(m.html, '<p>Привет</p>');
  assert.equal(h.state.journal.at(-1).status, 250);
});

test('письмо по SMTP видно тесту через список писем и не создаёт вебхуков', async () => {
  await smtp(dialog(['Subject: Hi', '', 'Тело письма', '.']));
  await h.idle();
  const list = (await h.admin('GET', '/admin/emails?sender=smtp')).json.items;
  assert.equal(list.length, 1);
  assert.equal(list[0].text, 'Тело письма');
  assert.equal(h.hooks.length, 0);
});

test('точка в начале строки удваивается клиентом и восстанавливается', async () => {
  await smtp(dialog(['Subject: Dots', '', '..скрытая точка', '.']));
  assert.equal(h.state.emails[0].text, '.скрытая точка');
});

test('временная ошибка: 451 на конце письма, письмо не хранится; первые N', async () => {
  await h.setMode('email', { behavior: 'temp_error', failFirst: 1 });
  const first = await smtp(dialog(['Subject: A', '', 'x', '.']));
  assert.ok(first.some((r) => r.startsWith('451')));
  assert.equal(h.state.emails.length, 0);
  const second = await smtp(dialog(['Subject: A', '', 'x', '.']));
  assert.ok(second.some((r) => r.startsWith('250 2.0.0')));
  assert.equal(h.state.emails.length, 1);
});

test('окончательная ошибка: 550', async () => {
  await h.setMode('email', { behavior: 'perm_error', sender: 'smtp' });
  const r = await smtp(dialog(['Subject: A', '', 'x', '.']));
  assert.ok(r.some((x) => x.startsWith('550')));
  await h.setMode('email', { behavior: 'perm_error', sender: 'delivery' });
  assert.ok((await smtp(dialog(['Subject: A', '', 'x', '.']))).some((x) => x.startsWith('250 2.0.0')), 'режим для другого отправителя');
});

test('порядок команд и неподдерживаемое: RCPT до MAIL, DATA без получателя, AUTH, STARTTLS, неизвестная команда', async () => {
  const r = await smtp(['EHLO x', 'RCPT TO:<a@b.test>', 'DATA', 'MAIL FROM:<a@b.test>', 'MAIL ЧТО', 'DATA', 'AUTH PLAIN abc', 'STARTTLS', 'FOO', 'NOOP', 'RSET', 'VRFY a', 'QUIT']);
  assert.deepEqual(r.map((x) => x.slice(0, 3)), ['220', '250', '503', '503', '250', '501', '503', '502', '502', '500', '250', '250', '252', '221']);
});

test('несколько получателей собираются в письмо', async () => {
  await smtp(['EHLO x', 'MAIL FROM:<a@b.test>', 'RCPT TO:<one@example.test>', 'RCPT TO:<two@example.test>', 'DATA', 'Subject: S\r\n\r\nx\r\n.', 'QUIT']);
  assert.deepEqual(h.state.emails[0].to, ['one@example.test', 'two@example.test']);
});

test('разбор MIME: вложенный multipart, вложение пропускается, тема без кодировки', () => {
  const raw = [
    'Subject: Plain',
    'Content-Type: multipart/mixed; boundary=outer',
    '',
    '--outer',
    'Content-Type: multipart/alternative; boundary=inner',
    '',
    '--inner',
    'Content-Type: text/plain',
    '',
    'Текст',
    '--inner--',
    '--outer',
    'Content-Type: text/plain',
    'Content-Disposition: attachment; filename=a.txt',
    '',
    'не тело письма',
    '--outer--',
  ].join('\r\n');
  const m = parseMessage(raw);
  assert.equal(m.subject, 'Plain');
  assert.equal(m.text, 'Текст');
  assert.equal(m.html, undefined);
});

test('разбор MIME: тема Q-кодировкой и склеенные слова, письмо без темы', () => {
  assert.equal(decodeWords('=?UTF-8?Q?=D0=9A=D0=BB=D1=8E=D1=87_=D0=B3=D0=BE=D1=82=D0=BE=D0=B2?='), 'Ключ готов');
  assert.equal(decodeWords('=?UTF-8?B?0J/RgNC40LI=?= =?UTF-8?B?0LXRgg==?='), 'Привет');
  assert.equal(parseMessage('To: a@b.test\r\n\r\nтекст').subject, '(без темы)');
});
