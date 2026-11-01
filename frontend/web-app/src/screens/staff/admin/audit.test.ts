import { describe, expect, it } from 'vitest';
import { EMPTY_FORM, changeValue, formatPrecise, readForm, shortHash, shortId, toRfc3339 } from './audit';
import { NO_FILTERS } from './useAdmin';

const ACTOR = '0199E0A0-0000-7000-8000-0000000000A1';

describe('условия выборки журнала (TC-810)', () => {
  it('пустая форма даёт выборку без условий', () => {
    expect(readForm(EMPTY_FORM)).toEqual({ filters: NO_FILTERS });
  });

  it('условия очищаются от пробелов, идентификатор исполнителя приводится к нижнему регистру, время уходит в RFC 3339', () => {
    const result = readForm({
      actorId: ` ${ACTOR} `,
      objectType: ' parameter ',
      objectId: ' reservation.ttl-seconds ',
      action: ' parameter.changed ',
      from: '2026-10-01T10:00',
      to: '2026-10-02T10:00',
    });
    expect(result).toEqual({
      filters: {
        actorId: ACTOR.toLowerCase(),
        objectType: 'parameter',
        objectId: 'reservation.ttl-seconds',
        action: 'parameter.changed',
        occurredFrom: new Date('2026-10-01T10:00').toISOString(),
        occurredTo: new Date('2026-10-02T10:00').toISOString(),
      },
    });
  });

  it('исполнитель не UUID: ошибка у поля', () => {
    const result = readForm({ ...EMPTY_FORM, actorId: 'admin' });
    expect('errors' in result && Object.keys(result.errors)).toEqual(['actorId']);
  });

  it('конец периода не позже начала: ошибка у конца', () => {
    const same = readForm({ ...EMPTY_FORM, from: '2026-10-01T10:00', to: '2026-10-01T10:00' });
    expect('errors' in same && Object.keys(same.errors)).toEqual(['to']);
    const reversed = readForm({ ...EMPTY_FORM, from: '2026-10-02T10:00', to: '2026-10-01T10:00' });
    expect('errors' in reversed && Object.keys(reversed.errors)).toEqual(['to']);
  });

  it('один край периода допустим', () => {
    const result = readForm({ ...EMPTY_FORM, from: '2026-10-01T10:00' });
    expect(result).toEqual({ filters: { ...NO_FILTERS, occurredFrom: new Date('2026-10-01T10:00').toISOString() } });
  });

  it('время поля ввода переводится в RFC 3339, нечитаемое и пустое дают пусто', () => {
    expect(toRfc3339('2026-10-01T10:00')).toBe(new Date('2026-10-01T10:00').toISOString());
    expect(toRfc3339('')).toBeUndefined();
    expect(toRfc3339('вчера')).toBeUndefined();
  });
});

describe('показ записей журнала (TC-810, TC-815)', () => {
  it('идентификатор и хеш сокращаются, короткие значения остаются как есть', () => {
    expect(shortId('c3d4e5f6-0718-4293-a4b5-c6d7e8f90a1b')).toBe('c3d4e5f6');
    expect(shortId('abc')).toBe('abc');
    expect(shortHash('9f86d081884c7d659a2feaa0c55ad015')).toBe('9f86d081…');
    expect(shortHash('abc')).toBe('abc');
  });

  it('значение изменения: обычное как есть, персональное хешем сокращённо, пустое словом', () => {
    const plain = { field: 'value', personal: false, before: '900', after: '1200' };
    expect(changeValue(plain, 'before')).toBe('900');
    expect(changeValue(plain, 'after')).toBe('1200');
    const personal = { field: 'email', personal: true, before: '9f86d081884c7d659a2feaa0c55ad015', after: null };
    expect(changeValue(personal, 'before')).toBe('9f86d081…');
    expect(changeValue(personal, 'after')).toBe('нет');
  });

  it('время записи с секундами, нечитаемое возвращается как есть', () => {
    expect(formatPrecise('2026-10-17T12:00:05.123Z')).toMatch(/\d{2}\.\d{2}\.\d{4}.*\d{2}:\d{2}:05/);
    expect(formatPrecise('не время')).toBe('не время');
  });
});
