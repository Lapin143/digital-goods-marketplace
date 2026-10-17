import { describe, expect, it } from 'vitest';
import { CSV_MAX_BYTES, checkCsv, splitKeys } from './keys';

describe('строки ключей из поля ввода', () => {
  it('пусто даёт пустой список', () => {
    expect(splitKeys('')).toEqual([]);
  });

  it('по ключу в строке, последний перевод строки не считается', () => {
    expect(splitKeys('A\nB\n')).toEqual(['A', 'B']);
    expect(splitKeys('A\nB')).toEqual(['A', 'B']);
  });

  it('пустая строка внутри списка остаётся, чтобы номера строк совпали с отчётом сервера', () => {
    expect(splitKeys('A\n\nB')).toEqual(['A', '', 'B']);
  });

  it('окончания строк Windows убираются', () => {
    expect(splitKeys('A\r\nB\r\n')).toEqual(['A', 'B']);
  });

  it('пробелы внутри ключа сохраняются (решает сервер)', () => {
    expect(splitKeys(' A \nB')).toEqual([' A ', 'B']);
  });
});

describe('файл CSV до отправки', () => {
  it('csv и txt проходят', () => {
    expect(checkCsv({ name: 'keys.csv', type: 'text/csv', size: 100 })).toEqual({ ok: true });
    expect(checkCsv({ name: 'keys.txt', type: 'text/plain', size: 100 })).toEqual({ ok: true });
    expect(checkCsv({ name: 'keys.CSV', type: '', size: 100 })).toEqual({ ok: true });
    expect(checkCsv({ name: 'keys.csv', type: 'application/vnd.ms-excel', size: 100 })).toEqual({ ok: true });
  });

  it('пустой файл отвергается', () => {
    expect(checkCsv({ name: 'keys.csv', type: 'text/csv', size: 0 })).toEqual({ ok: false, reason: 'keys.error.csvEmpty' });
  });

  it('больше 2 МБ отвергается, ровно 2 МБ проходит', () => {
    expect(checkCsv({ name: 'keys.csv', type: 'text/csv', size: CSV_MAX_BYTES })).toEqual({ ok: true });
    expect(checkCsv({ name: 'keys.csv', type: 'text/csv', size: CSV_MAX_BYTES + 1 })).toEqual({ ok: false, reason: 'keys.error.csvSize' });
  });

  it('другой тип и другое расширение отвергаются', () => {
    expect(checkCsv({ name: 'keys.pdf', type: 'application/pdf', size: 10 })).toEqual({ ok: false, reason: 'keys.error.csvType' });
    expect(checkCsv({ name: 'keys.exe', type: 'text/plain', size: 10 })).toEqual({ ok: false, reason: 'keys.error.csvType' });
  });
});
