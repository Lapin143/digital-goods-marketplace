// NFT-1.2, TC-062: создание заказа и резерв не дольше 1 с (95-й перцентиль), ориентир на раннере (шаг 23 Ф4, решение Р-144).
//
//   make load-order                                    покупатели, товар и запуск готовит tools/load/order_run.py
//   k6 run -e FIXTURE=$PWD/build/load/order-fixture.json tools/load/order.k6.js   (путь к файлу покупателей полный: k6 читает его из папки сценария)
//
// Что замеряется: время от запроса POST /api/v1/orders до ответа 201 с заказом awaiting_payment. Внутри этого времени шлюз проверяет токен и лимит,
// order-service проверяет телефон, берёт карточку товара, резервирует ключи в inventory-service и открывает платёжную сессию у шлюза (заглушка).
// Метка 201 с статусом awaiting_payment означает, что резерв активен: заказ без резерва остаётся created, и ответ был бы 409 или 5xx.
//
// Нагрузка: контрольный прогон методики, RATE заказов в секунду (по умолчанию 10) в течение DURATION (по умолчанию 30 с), без ступеней.
// Покупателей заранее заводит order_run.py: у каждого признак phone_verified, токен доступа действует 5 минут, поэтому запуск идёт сразу
// после входа. Заказы раздаются покупателям по кругу, так что лимит неоплаченных заказов (три на покупателя) и лимит шлюза «order-create»
// (10 в минуту на пользователя) не срабатывают: RATE * DURATION не больше числа покупателей, умноженного на три. Лимиты шлюза не меняются.
// Порог: p95 меньше 1000 мс по запросам заказов; он же завершает k6 с кодом 99, если нарушен.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'https://localhost:8443';
const RATE = Number(__ENV.RATE || 10);
const DURATION = __ENV.DURATION || '30s';
const FIXTURE = __ENV.FIXTURE;

if (!FIXTURE) throw new Error('не задан FIXTURE: путь к файлу покупателей (make load-order готовит его сам)');
const fixture = JSON.parse(open(FIXTURE));
const buyers = new SharedArray('buyers', () => fixture.buyers);
const createDuration = new Trend('create_order_duration', true);
const created = new Counter('orders_created');
const refused = new Counter('orders_refused');

export const options = {
  scenarios: {
    order: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 40, maxVUs: 150, exec: 'order',
    },
  },
  thresholds: {
    'create_order_duration': ['p(95)<1000'],
    'http_req_failed': ['rate<0.001'],
    'checks': ['rate>0.999'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// Идентификатор запроса по RFC 4122, версия 4 (в k6 нет встроенного генератора без внешних модулей)
function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16);
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function seconds(text) {
  const m = /^(\d+)(s|m)$/.exec(String(text));
  if (!m) throw new Error('DURATION задаётся как 30s или 1m, получено ' + text);
  return Number(m[1]) * (m[2] === 'm' ? 60 : 1);
}

export function setup() {
  if (!fixture.productId || buyers.length === 0) throw new Error('в файле ' + FIXTURE + ' нет товара или покупателей: запустите make load-order');
  const limit = buyers.length * 3;
  const planned = RATE * seconds(DURATION);
  console.log('покупателей ' + buyers.length + ', заказов в плане ' + planned + ', предел по лимиту неоплаченных ' + limit);
  if (planned > limit) throw new Error('в плане ' + planned + ' заказов, а покупателей хватает на ' + limit + ': больше трёх неоплаченных заказов на покупателя сервис не пустит');
}

export function order() {
  const buyer = buyers[exec.scenario.iterationInTest % buyers.length];
  const res = http.post(BASE_URL + '/api/v1/orders', JSON.stringify({ productId: fixture.productId, quantity: 1 }), {
    headers: { 'Content-Type': 'application/json', 'Accept': 'application/json', 'Authorization': 'Bearer ' + buyer.token, 'Idempotency-Key': uuid() },
    tags: { name: 'createOrder' },
  });
  createDuration.add(res.timings.duration);
  const ok = check(res, {
    'заказ создан: 201': (r) => r.status === 201,
    'заказ ждёт оплаты, резерв активен': (r) => r.status === 201 && r.json('status') === 'awaiting_payment',
  });
  if (ok) created.add(1); else refused.add(1);
}
