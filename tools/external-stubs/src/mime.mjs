// Разбор письма в объёме, нужном просмотру: заголовки, текстовая и HTML-части, кодировки base64 и quoted-printable, RFC 2047.

function splitHeadBody(raw) {
  const m = /\r?\n\r?\n/.exec(raw);
  return m ? [raw.slice(0, m.index), raw.slice(m.index + m[0].length)] : [raw, ''];
}

export function parseHeaders(head) {
  const headers = {};
  const unfolded = head.replace(/\r?\n[ \t]+/g, ' ');
  for (const line of unfolded.split(/\r?\n/)) {
    const i = line.indexOf(':');
    if (i > 0) headers[line.slice(0, i).trim().toLowerCase()] = line.slice(i + 1).trim();
  }
  return headers;
}

function decodeQuotedPrintable(text, charset) {
  const bytes = [];
  const clean = text.replace(/=\r?\n/g, '');
  for (let i = 0; i < clean.length; i++) {
    if (clean[i] === '=' && /^[0-9A-Fa-f]{2}$/.test(clean.slice(i + 1, i + 3))) {
      bytes.push(parseInt(clean.slice(i + 1, i + 3), 16));
      i += 2;
    } else {
      bytes.push(...Buffer.from(clean[i], 'utf8'));
    }
  }
  return decodeBytes(Buffer.from(bytes), charset);
}

function decodeBytes(buffer, charset = 'utf-8') {
  try {
    return new TextDecoder(charset.replace(/"/g, '')).decode(buffer);
  } catch {
    return buffer.toString('utf8');
  }
}

/** Заголовок с кодированными словами =?utf-8?B?...?= и =?utf-8?Q?...?= */
export function decodeWords(value) {
  return value
    .replace(/\?=\s+=\?/g, '?==?')
    .replace(/=\?([^?]+)\?([bBqQ])\?([^?]*)\?=/g, (_, charset, enc, data) =>
      enc.toUpperCase() === 'B'
        ? decodeBytes(Buffer.from(data, 'base64'), charset)
        : decodeQuotedPrintable(data.replace(/_/g, ' '), charset));
}

function decodeBody(body, headers) {
  const encoding = (headers['content-transfer-encoding'] || '7bit').toLowerCase();
  const charset = /charset="?([^";\s]+)"?/i.exec(headers['content-type'] || '')?.[1] ?? 'utf-8';
  if (encoding === 'base64') return decodeBytes(Buffer.from(body.replace(/\s+/g, ''), 'base64'), charset);
  if (encoding === 'quoted-printable') return decodeQuotedPrintable(body, charset);
  return body;
}

function collect(raw, out, depth = 0) {
  const [head, body] = splitHeadBody(raw);
  const headers = parseHeaders(head);
  const type = (headers['content-type'] || 'text/plain').split(';')[0].trim().toLowerCase();
  if (type.startsWith('multipart/') && depth < 5) {
    const boundary = /boundary="?([^";]+)"?/i.exec(headers['content-type'])?.[1];
    if (boundary) {
      for (const part of body.split(`--${boundary}`).slice(1)) {
        if (part.startsWith('--')) break;
        collect(part.replace(/^\r?\n/, ''), out, depth + 1);
      }
    }
    return headers;
  }
  const disposition = (headers['content-disposition'] || '').toLowerCase();
  if (disposition.startsWith('attachment')) return headers;
  if (type === 'text/plain' && out.text === undefined) out.text = decodeBody(body, headers).replace(/\r?\n$/, '');
  if (type === 'text/html' && out.html === undefined) out.html = decodeBody(body, headers).replace(/\r?\n$/, '');
  return headers;
}

export function parseMessage(raw) {
  const out = {};
  const headers = collect(raw, out);
  return {
    headers,
    from: headers.from ? decodeWords(headers.from) : undefined,
    subject: headers.subject ? decodeWords(headers.subject) : '(без темы)',
    text: out.text,
    html: out.html,
  };
}
