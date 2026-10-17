export interface MoneyValue {
  amount: number;
  currency: string;
}

/** Сумма для показа: «1 499 ₽» и «1 499,50 ₽». Деньги в API это целые копейки (conventions.md, раздел 4). */
export function formatMoney(money: MoneyValue): string {
  const rubles = money.amount / 100;
  try {
    return new Intl.NumberFormat('ru-RU', {
      style: 'currency',
      currency: money.currency,
      minimumFractionDigits: money.amount % 100 === 0 ? 0 : 2,
      maximumFractionDigits: 2,
    }).format(rubles);
  } catch {
    // Код валюты, которого браузер не знает (перечисление открытое): показываем число и код как есть
    return `${rubles} ${money.currency}`;
  }
}

export type RublesParse = { kind: 'empty' } | { kind: 'value'; kopecks: number } | { kind: 'invalid' };

const MAX_KOPECKS = 10_000_000_000;

/**
 * Рубли из поля ввода в копейки без дробной арифметики: «1499», «1499,5», «1 499.50». Больше двух знаков после запятой,
 * буквы, минус и суммы больше 100 млн рублей считаются ошибкой.
 */
export function parseRubles(text: string): RublesParse {
  const clean = text.replace(/[\s\u00a0]/g, '');
  if (clean === '') {
    return { kind: 'empty' };
  }
  const match = /^(\d{1,10})(?:[.,](\d{1,2}))?$/.exec(clean);
  if (!match) {
    return { kind: 'invalid' };
  }
  const whole = Number(match[1]);
  const fraction = Number((match[2] ?? '').padEnd(2, '0'));
  const kopecks = whole * 100 + fraction;
  return kopecks > MAX_KOPECKS ? { kind: 'invalid' } : { kind: 'value', kopecks };
}
