import ru from './ru.json';

/** Ключи словаря проверяются при сборке: опечатка в t('…') не пройдёт tsc. */
export type TextKey = keyof typeof ru;
export type TextParams = Record<string, string | number>;

const TEMPLATE_PARAM = /\{(\w+)\}/g;

/** Текст по ключу с подстановкой {имя} из params; неизвестный параметр остаётся в тексте как есть. */
export function t(key: TextKey, params?: TextParams): string {
  const template: string = ru[key];
  if (!params) {
    return template;
  }
  return template.replace(TEMPLATE_PARAM, (whole: string, name: string) => {
    const value = params[name];
    return value === undefined ? whole : String(value);
  });
}

/** Есть ли в словаре такой ключ (для ключей, которые собираются из кода ошибки сервера). */
export function hasText(key: string): key is TextKey {
  return Object.prototype.hasOwnProperty.call(ru, key);
}

const pluralRules = new Intl.PluralRules('ru');

/** Форма слова по числу: «1 товар», «2 товара», «5 товаров». Три ключа словаря: one, few, many (и дробные). */
export function plural(count: number, forms: { one: TextKey; few: TextKey; many: TextKey }, params?: TextParams): string {
  const category = pluralRules.select(count);
  const key = category === 'one' ? forms.one : category === 'few' ? forms.few : forms.many;
  return t(key, { count, ...params });
}
