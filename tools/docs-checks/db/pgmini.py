# -*- coding: utf-8 -*-
"""Минимальный клиент PostgreSQL на стандартной библиотеке (простой протокол, текстовые значения).

Нужен проверкам физической модели данных (шаг 12 Ф2): в песочнице нет драйверов Python и нет доступа к реестрам,
а тестам нужны настоящие параллельные сессии. Поддерживается: сокет Unix или TCP, аутентификация trust,
простые запросы, транзакции, ошибки с кодом SQLSTATE. Не поддерживается: парольная аутентификация, SSL, копирование.

    c = connect(host='/tmp', port=5433, user='postgres', dbname='order_db')
    rows = c.query("select 1, 'a'")        # [('1', 'a')]
    c.execute("insert ...")                # число строк из тега команды
"""
import socket
import struct


class PgError(Exception):
    def __init__(self, fields):
        self.fields = fields
        self.code = fields.get('C', '')
        self.message = fields.get('M', '')
        self.detail = fields.get('D', '')
        self.constraint = fields.get('n', '')
        super().__init__('%s %s' % (self.code, self.message))


class Conn:
    def __init__(self, host, port, user, dbname, application_name='pgmini'):
        if host.startswith('/'):
            self.s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.s.connect('%s/.s.PGSQL.%d' % (host, port))
        else:
            self.s = socket.create_connection((host, port))
        self.buf = b''
        params = {'user': user, 'database': dbname, 'application_name': application_name, 'client_encoding': 'UTF8'}
        body = struct.pack('!i', 196608)
        for k, v in params.items():
            body += k.encode() + b'\0' + v.encode() + b'\0'
        body += b'\0'
        self.s.sendall(struct.pack('!i', len(body) + 4) + body)
        self._startup()

    # --- низкий уровень
    def _read(self, n):
        while len(self.buf) < n:
            chunk = self.s.recv(65536)
            if not chunk:
                raise ConnectionError('соединение закрыто сервером')
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def _msg(self):
        t = self._read(1)
        (ln,) = struct.unpack('!i', self._read(4))
        return t, self._read(ln - 4)

    @staticmethod
    def _fields(payload):
        out = {}
        for part in payload.split(b'\0'):
            if part:
                out[chr(part[0])] = part[1:].decode('utf-8', 'replace')
        return out

    def _startup(self):
        while True:
            t, p = self._msg()
            if t == b'R':
                (code,) = struct.unpack('!i', p[:4])
                if code != 0:
                    raise PgError({'C': 'XX000', 'M': 'поддерживается только trust, получен код аутентификации %d' % code})
            elif t == b'E':
                raise PgError(self._fields(p))
            elif t == b'Z':
                return

    def _run(self, sql):
        data = sql.encode('utf-8') + b'\0'
        self.s.sendall(b'Q' + struct.pack('!i', len(data) + 4) + data)
        rows, tag, err = [], '', None
        while True:
            t, p = self._msg()
            if t == b'D':
                (n,) = struct.unpack('!h', p[:2])
                pos, row = 2, []
                for _ in range(n):
                    (ln,) = struct.unpack('!i', p[pos:pos + 4])
                    pos += 4
                    if ln == -1:
                        row.append(None)
                    else:
                        row.append(p[pos:pos + ln].decode('utf-8'))
                        pos += ln
                rows.append(tuple(row))
            elif t == b'C':
                tag = p.rstrip(b'\0').decode()
            elif t == b'E':
                err = PgError(self._fields(p))
            elif t == b'Z':
                if err:
                    raise err
                return rows, tag

    # --- публичный интерфейс
    def query(self, sql):
        return self._run(sql)[0]

    def execute(self, sql):
        tag = self._run(sql)[1]
        parts = tag.split()
        return int(parts[-1]) if parts and parts[-1].isdigit() else 0

    def scalar(self, sql):
        rows = self.query(sql)
        return rows[0][0] if rows else None

    def close(self):
        try:
            self.s.sendall(b'X' + struct.pack('!i', 4))
        finally:
            self.s.close()


def connect(host='/tmp', port=5433, user='postgres', dbname='postgres', application_name='pgmini'):
    return Conn(host, port, user, dbname, application_name)


def q(v):
    """Литерал для подстановки в SQL."""
    if v is None:
        return 'null'
    if isinstance(v, bool):
        return 'true' if v else 'false'
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"
