// Всё состояние заглушки в памяти процесса: режимы, платежи, письма, SMS, журнал.
import { defaults, SCHEMAS, SYSTEMS, validateMode } from './modes.mjs';
import { mulberry32 } from './util.mjs';

export function createState({ clock, bootTag, seed = 20261004, journalLimit = 2000 }) {
  const s = {
    clock,
    bootTag,
    seq: {}, // счётчики идентификаторов не сбрасываются между тестами, чтобы идентификаторы не повторялись
    journalSeq: 0,
    journalLimit,
  };

  function resetMutable() {
    s.modes = Object.fromEntries(SYSTEMS.map((name) => [name, defaults(SCHEMAS[name])]));
    s.counters = {};
    s.rng = mulberry32(seed);
    s.payments = new Map();
    s.idempotency = new Map();
    s.emails = [];
    s.sms = [];
    s.vk = { codes: new Map(), tokens: new Map() };
    s.journal = [];
    initCounters();
  }

  /** Счётчики «первые N вызовов» заводятся при установке режима. */
  function initCounters() {
    const { payment, email } = s.modes;
    s.counters = {
      createFail: payment.create.failFirst,
      createLose: payment.create.loseResponseFirst,
      refundFail: payment.refund.failFirst,
      emailFail: email.failFirst,
    };
  }

  s.id = (prefix) => {
    s.seq[prefix] = (s.seq[prefix] ?? 0) + 1;
    return `${prefix}_${bootTag}_${String(s.seq[prefix]).padStart(6, '0')}`;
  };

  s.setMode = (system, input) => {
    const value = validateMode(system, input);
    s.modes[system] = value;
    initCounters();
    return value;
  };

  /** Берёт один из счётчиков «первые N»: true, если этот вызов должен отказать. -1 — отказывать всегда. */
  s.takeFailure = (name) => {
    const left = s.counters[name];
    if (left === 0) return false;
    if (left > 0) s.counters[name] = left - 1;
    return true;
  };

  s.record = (entry) => {
    const item = { n: ++s.journalSeq, at: clock.now().toISOString(), ...entry };
    s.journal.push(item);
    if (s.journal.length > s.journalLimit) s.journal.splice(0, s.journal.length - s.journalLimit);
    return item;
  };

  s.reset = () => {
    resetMutable();
    s.record({ kind: 'admin', system: 'admin', method: 'RESET', path: '/admin/reset', status: 200 });
  };

  resetMutable();
  return s;
}
