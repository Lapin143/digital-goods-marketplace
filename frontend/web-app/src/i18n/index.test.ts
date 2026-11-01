import { describe, expect, it } from 'vitest';
import ru from './ru.json';
import { hasText, plural, t } from '.';

describe('словарь', () => {
  it('подставляет параметры по именам', () => {
    expect(t('problem.retryAfter', { seconds: 30 })).toBe('Повторите через 30 с.');
  });

  it('оставляет неизвестный параметр как есть', () => {
    expect(t('problem.pool-limit-exceeded')).toContain('{limit}');
  });

  it('знает, есть ли ключ', () => {
    expect(hasText('app.title')).toBe(true);
    expect(hasText('нет.такого.ключа')).toBe(false);
  });

  it('все значения непустые, параметры в фигурных скобках записаны латиницей', () => {
    for (const [key, value] of Object.entries(ru)) {
      expect(value.trim(), key).not.toBe('');
      for (const match of value.matchAll(/\{([^}]*)\}/g)) {
        expect(match[1], key).toMatch(/^\w+$/);
      }
    }
  });
});

describe('язык интерфейса (TC-805, NFT-7.0)', () => {
  // Без русских букв остаются только три формата из чисел и подстановок. Любой другой текст без русских букв (английская фраза
  // в словаре) красит тест: новый формат вносится сюда осознанно.
  const FORMATS_WITHOUT_LETTERS = ['admin.params.bounds.short', 'admin.unit.percent', 'audit.change.line'];

  it('TC-805. все тексты словаря содержат русские буквы, кроме форматов из подстановок', () => {
    const withoutRussian = Object.entries(ru)
      .filter(([, value]) => !/[А-Яа-яЁё]/.test(value))
      .map(([key]) => key)
      .sort();
    expect(withoutRussian).toEqual(FORMATS_WITHOUT_LETTERS);
  });

  it('TC-805. в текстах нет двойных пробелов, пробела перед знаком препинания и тире вместо дефиса в «e-mail»', () => {
    for (const [key, value] of Object.entries(ru)) {
      expect(value, key).not.toMatch(/ {2}/);
      expect(value, key).not.toMatch(/ [,.;:!?](?!\d)/);
      expect(value, key).not.toMatch(/e–mail|E–mail/);
    }
  });
});

describe('множественное число', () => {
  const forms = { one: 'plural.test.one', few: 'plural.test.few', many: 'plural.test.many' } as unknown as Parameters<typeof plural>[1];

  it('выбирает форму по числу (русские правила)', () => {
    // Ключей plural.test.* в словаре нет: проверяем выбор формы через подмену ключей словаря
    const dictionary = ru as Record<string, string>;
    dictionary['plural.test.one'] = '{count} товар';
    dictionary['plural.test.few'] = '{count} товара';
    dictionary['plural.test.many'] = '{count} товаров';
    try {
      expect(plural(1, forms)).toBe('1 товар');
      expect(plural(2, forms)).toBe('2 товара');
      expect(plural(5, forms)).toBe('5 товаров');
      expect(plural(21, forms)).toBe('21 товар');
      expect(plural(11, forms)).toBe('11 товаров');
    } finally {
      delete dictionary['plural.test.one'];
      delete dictionary['plural.test.few'];
      delete dictionary['plural.test.many'];
    }
  });
});
