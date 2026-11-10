import { describe, expect, it } from 'vitest';
import { API_KEYS_MAX, activeKeyCount, keyMark, validateKeyName } from './apiKeys';
import type { ApiKey } from './types';

function key(id: string, status: 'active' | 'revoked'): ApiKey {
  return { id, name: id, fingerprint: 'kJ3x9Zt0', status, createdAt: '2026-11-01T09:00:00Z', revokedAt: status === 'revoked' ? '2026-11-02T09:00:00Z' : null } as ApiKey;
}

describe('имя ключа', () => {
  it('пустое имя и одни пробелы не принимаются', () => {
    expect(validateKeyName('')).toBe('field.required');
    expect(validateKeyName('   ')).toBe('field.required');
  });

  it('до 60 знаков после обрезки пробелов допустимо, 61 нет', () => {
    expect(validateKeyName(`  ${'я'.repeat(60)}  `)).toBeUndefined();
    expect(validateKeyName('я'.repeat(61))).toBe('field.too_long');
  });
});

describe('предел ключей', () => {
  it('предел пять, считаются только действующие', () => {
    expect(API_KEYS_MAX).toBe(5);
    expect(activeKeyCount([key('a', 'active'), key('b', 'revoked'), key('c', 'active')])).toBe(2);
    expect(activeKeyCount([])).toBe(0);
  });
});

describe('метка ключа', () => {
  it('префикс и отпечаток, значения ключа в метке нет', () => {
    expect(keyMark('kJ3x9Zt0')).toBe('dgm_kJ3x9Zt0');
  });
});
