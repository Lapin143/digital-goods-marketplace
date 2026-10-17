import { describe, expect, it } from 'vitest';
import { serverClockOffset, toLocalMoment } from './clock';

describe('часы сервера', () => {
  it('расхождение берётся из заголовка Date', () => {
    const headers = new Headers({ Date: 'Sat, 17 Oct 2026 12:00:10 GMT' });
    expect(serverClockOffset(headers, Date.parse('2026-10-17T12:00:00.000Z'))).toBe(10_000);
  });

  it('без заголовка или с мусором расхождения нет', () => {
    expect(serverClockOffset(new Headers(), 1)).toBe(0);
    expect(serverClockOffset(new Headers({ Date: 'not-a-date' }), 1)).toBe(0);
  });

  it('момент сервера переводится в местное время', () => {
    expect(toLocalMoment('2026-10-17T12:00:30.000Z', 10_000)).toBe(Date.parse('2026-10-17T12:00:20.000Z'));
    expect(toLocalMoment(null, 0)).toBeUndefined();
    expect(toLocalMoment('не дата', 0)).toBeUndefined();
  });
});
