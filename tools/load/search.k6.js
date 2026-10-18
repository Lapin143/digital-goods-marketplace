// NFT-1.1: поиск по названию и фильтры не дольше 500 мс (95-й перцентиль) при 50 запросах в секунду, ориентир на раннере (шаг 21 Ф4, Р-28).
//
// Смесь методики: 70 % поиска по части названия и 30 % фильтров (тип, платформа, регион, диапазон цены и их сочетания). Каталог засеян
// tools/load/seed_catalog.sql (5000 товаров). Нагрузка и порог те же, что у storefront.k6.js (RATE, STEADY). Если порог нарушен, по методике
// берётся EXPLAIN ANALYZE запроса к триграммному индексу (docs/07-data/explain-report.md).
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'https://localhost:8443';
const RATE = Number(__ENV.RATE || 50);
const STEADY = __ENV.STEADY || '2m';
const searchDuration = new Trend('search_duration', true);
const filterDuration = new Trend('filter_duration', true);
// Доля ответов без единого товара: если засев и слова поиска разошлись, быстрые пустые ответы дали бы ложно хороший результат
const emptySearch = new Rate('empty_search');
const emptyFilter = new Rate('empty_filter');

const WORDS = ['рубеж', 'лес', 'город', 'клинок', 'океан', 'архив', 'орден', 'маяк', 'каньон', 'проход',
  'сияющий', 'тёмный', 'забытый', 'северный', 'хрустальный', 'багровый', 'ледяной', 'золотой', 'тихий', 'дикий', 'steam', 'ключ', '0042', '31'];
const TYPES = ['game_key', 'software_license', 'gift_card', 'subscription', 'game_key,gift_card'];
const PLATFORMS = ['Steam', 'Epic Games', 'PlayStation', 'Xbox', 'Nintendo', 'GOG', 'Microsoft', 'Adobe'];
const REGIONS = ['RU', 'KZ', 'BY', 'US'];

export const options = {
  scenarios: {
    mix: {
      executor: 'ramping-arrival-rate', startRate: 5, timeUnit: '1s', preAllocatedVUs: 60, maxVUs: 200, exec: 'mix',
      stages: [
        { target: Math.round(RATE / 5), duration: '15s' },
        { target: Math.round(RATE / 2), duration: '15s' },
        { target: RATE, duration: '15s' },
        { target: RATE, duration: STEADY },
      ],
    },
  },
  thresholds: {
    'search_duration': ['p(95)<500'],
    'filter_duration': ['p(95)<500'],
    'http_req_failed': ['rate<0.001'],
    'checks': ['rate>0.999'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

function filterQuery() {
  const parts = [];
  const kind = Math.floor(Math.random() * 5);
  if (kind === 0) parts.push('productType=' + pick(TYPES));
  else if (kind === 1) parts.push('platform=' + encodeURIComponent(pick(PLATFORMS)));
  else if (kind === 2) parts.push('region=' + pick(REGIONS));
  else if (kind === 3) { const from = 10000 + Math.floor(Math.random() * 200000); parts.push('priceFrom=' + from, 'priceTo=' + (from + 100000)); }
  else { parts.push('productType=' + pick(TYPES), 'platform=' + encodeURIComponent(pick(PLATFORMS)), 'priceTo=' + (50000 + Math.floor(Math.random() * 400000))); }
  return parts.join('&');
}

export function mix() {
  const isSearch = Math.random() < 0.7;
  const query = isSearch ? 'q=' + encodeURIComponent(pick(WORDS)) : filterQuery();
  const res = http.get(BASE_URL + '/api/v1/products?limit=20&' + query, { tags: { name: isSearch ? 'search' : 'filter' } });
  (isSearch ? searchDuration : filterDuration).add(res.timings.duration);
  check(res, {
    'страница 200': (r) => r.status === 200,
    'в теле items': (r) => r.status === 200 && Array.isArray(r.json('items')),
  });
  if (res.status === 200) (isSearch ? emptySearch : emptyFilter).add(res.json('items').length === 0);
}
