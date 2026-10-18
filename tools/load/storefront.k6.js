// NFT-1.0: карточка товара не дольше 300 мс (95-й перцентиль) при 50 запросах в секунду, ориентир на раннере (шаг 21 Ф4, решение Р-28).
//
//   make load-nft                                      оба сценария (tools/load/README.md)
//   k6 run -e BASE_URL=https://localhost:8443 tools/load/storefront.k6.js
//
// Порядок: setup() обходит витрину страницами по курсору и собирает идентификаторы товаров, затем прогрев (каждая карточка один раз:
// кэш Redis заполнен, как требует методика), затем ступенчатый рост до 50 запросов в секунду и устойчивая нагрузка STEADY (по умолчанию
// 2 минуты, на сервере 5 минут: -e STEADY=5m). Адреса карточек выбираются случайно по всему каталогу. Тело ответа проверяется: 200 и поле productId.
// Порог: p95 меньше 300 мс по запросам карточек. Он же завершает k6 с кодом 99, если нарушен.
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'https://localhost:8443';
const RATE = Number(__ENV.RATE || 50);
const STEADY = __ENV.STEADY || '2m';
const cardDuration = new Trend('card_duration', true);

export const options = {
  scenarios: {
    warmup: {
      executor: 'shared-iterations', vus: 5, iterations: 5, maxDuration: '10m', exec: 'warmup', startTime: '0s',
    },
    card: {
      executor: 'ramping-arrival-rate', startRate: 5, timeUnit: '1s', preAllocatedVUs: 60, maxVUs: 200, exec: 'card',
      stages: [
        { target: Math.round(RATE / 5), duration: '15s' },
        { target: Math.round(RATE / 2), duration: '15s' },
        { target: RATE, duration: '15s' },
        { target: RATE, duration: STEADY },
      ],
      startTime: '1m',
    },
  },
  thresholds: {
    'card_duration': ['p(95)<300'],
    'http_req_failed{scenario:card}': ['rate<0.001'],
    'checks{scenario:card}': ['rate>0.999'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const ids = [];
  let cursor = null;
  for (let page = 0; page < 1000; page++) {
    const url = BASE_URL + '/api/v1/products?limit=100' + (cursor ? '&cursor=' + encodeURIComponent(cursor) : '');
    const res = http.get(url);
    if (res.status !== 200) throw new Error('витрина ответила ' + res.status + ' на ' + url);
    const body = res.json();
    for (const item of body.items) ids.push(item.productId);
    cursor = body.page && body.page.nextCursor;
    if (!cursor) break;
  }
  if (ids.length === 0) throw new Error('на витрине нет товаров: выполните make load-seed');
  console.log('товаров на витрине: ' + ids.length);
  return { ids };
}

export function warmup(data) {
  // весь каталог пятью итерациями, по одной пятой каждая: все карточки оказываются в кэше, как требует методика
  const share = Math.ceil(data.ids.length / 5);
  const from = exec.scenario.iterationInTest * share;
  for (const id of data.ids.slice(from, from + share)) http.get(BASE_URL + '/api/v1/products/' + id, { tags: { name: 'warmup' } });
}

export function card(data) {
  const id = data.ids[Math.floor(Math.random() * data.ids.length)];
  const res = http.get(BASE_URL + '/api/v1/products/' + id, { tags: { name: 'card' } });
  cardDuration.add(res.timings.duration);
  check(res, {
    'карточка 200': (r) => r.status === 200,
    'в теле productId': (r) => r.status === 200 && r.json('productId') === id,
  });
}
