// Минимальный SMTP-приёмник для Keycloak и оповещений: EHLO, MAIL, RCPT, DATA, RSET, NOOP, QUIT. Без AUTH и STARTTLS.
import net from 'node:net';
import { parseMessage } from './mime.mjs';
import { shouldFail, storeMessage } from './email.mjs';
import { sleep } from './util.mjs';

const MAX_MESSAGE = 10 * 1024 * 1024;
const MAX_LINE = 4096;

export function createSmtpServer(app) {
  const { state } = app;
  const server = net.createServer((socket) => {
    socket.setEncoding('latin1'); // байты письма сохраняются как есть, кодировку разбирает MIME-разбор
    socket.setTimeout(60_000, () => socket.destroy());
    let buffer = '';
    let session = { from: null, rcpt: [] };
    let data = null; // строки тела, пока идёт DATA
    let dataSize = 0;
    const reply = (text) => socket.write(`${text}\r\n`);

    reply('220 external-stubs ESMTP (заглушка)');

    async function finishMessage() {
      const raw = Buffer.from(data.join('\r\n'), 'latin1').toString('utf8');
      data = null;
      const mode = state.modes.email;
      const parsed = parseMessage(raw);
      const entry = { kind: 'request', system: 'email', via: 'smtp', method: 'SMTP', path: 'DATA', from: session.from, to: session.rcpt, subject: parsed.subject };
      if (shouldFail(app, 'smtp')) {
        if (mode.behavior === 'perm_error') {
          state.record({ ...entry, status: 550 });
          reply('550 5.1.1 Адрес получателя отклонён');
        } else if (mode.tempKind === 'timeout') {
          state.record({ ...entry, status: 0, note: 'тайм-аут: ответа нет' });
          await sleep(mode.timeoutMs);
          socket.destroy();
          return;
        } else {
          state.record({ ...entry, status: 451 });
          reply('451 4.3.0 Временная ошибка, повторите позже');
        }
      } else {
        const messageId = state.id('msg');
        storeMessage(app, { sender: 'smtp', via: 'smtp', messageId, from: session.from, to: session.rcpt, subject: parsed.subject, text: parsed.text, html: parsed.html });
        state.record({ ...entry, status: 250, messageId });
        reply(`250 2.0.0 Принято, идентификатор ${messageId}`);
      }
      session = { from: null, rcpt: [] };
    }

    async function onLine(line) {
      if (data) {
        if (line === '.') return finishMessage();
        dataSize += line.length + 2;
        if (dataSize > MAX_MESSAGE) {
          data = null;
          reply('552 5.3.4 Письмо слишком большое');
          return;
        }
        data.push(line.startsWith('..') ? line.slice(1) : line);
        return;
      }
      const [cmd, ...rest] = line.trim().split(/\s+/);
      const arg = rest.join(' ');
      switch (cmd.toUpperCase()) {
        case 'EHLO':
          socket.write(`250-external-stubs\r\n250-SIZE ${MAX_MESSAGE}\r\n250 8BITMIME\r\n`);
          break;
        case 'HELO':
          reply('250 external-stubs');
          break;
        case 'MAIL': {
          const m = /^FROM:\s*<([^>]*)>/i.exec(arg);
          if (!m) return reply('501 5.5.4 Ожидается MAIL FROM:<адрес>');
          session = { from: m[1], rcpt: [] };
          reply('250 2.1.0 Ok');
          break;
        }
        case 'RCPT': {
          const m = /^TO:\s*<([^>]+)>/i.exec(arg);
          if (session.from === null) return reply('503 5.5.1 Сначала MAIL');
          if (!m) return reply('501 5.5.4 Ожидается RCPT TO:<адрес>');
          session.rcpt.push(m[1]);
          reply('250 2.1.5 Ok');
          break;
        }
        case 'DATA':
          if (!session.rcpt.length) return reply('503 5.5.1 Сначала RCPT');
          data = [];
          dataSize = 0;
          reply('354 Конец письма: строка с одной точкой');
          break;
        case 'RSET':
          session = { from: null, rcpt: [] };
          reply('250 2.0.0 Ok');
          break;
        case 'NOOP':
          reply('250 2.0.0 Ok');
          break;
        case 'VRFY':
          reply('252 2.5.2 Проверка не поддерживается');
          break;
        case 'QUIT':
          socket.end('221 2.0.0 До свидания\r\n');
          break;
        case 'AUTH':
        case 'STARTTLS':
          reply('502 5.5.1 Не поддерживается');
          break;
        default:
          reply('500 5.5.1 Команда не распознана');
      }
    }

    let chain = Promise.resolve(); // строки обрабатываются строго по очереди, даже если пришли одним пакетом
    socket.on('data', (chunk) => {
      buffer += chunk;
      if (!data && buffer.length > MAX_LINE && !buffer.includes('\n')) {
        reply('500 5.5.2 Строка слишком длинная');
        socket.destroy();
        return;
      }
      let i;
      while ((i = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, i).replace(/\r$/, '');
        buffer = buffer.slice(i + 1);
        chain = chain.then(() => onLine(line)).catch((e) => {
          state.record({ kind: 'internal', system: 'email', outcome: 'error', note: `smtp: ${e.message}` });
          reply('451 4.3.0 Внутренняя ошибка заглушки');
        });
      }
    });
    socket.on('error', () => {});
  });
  return server;
}
