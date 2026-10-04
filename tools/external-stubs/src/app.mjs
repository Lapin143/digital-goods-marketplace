// Общий контекст приложения: конфигурация, состояние, часы, планировщик задержек и отправитель вебхуков.
import { createState } from './state.mjs';
import { createWebhookSender, httpRequest } from './webhook.mjs';

export function createApp(config, deps = {}) {
  const clock = deps.clock ?? { now: () => new Date() };
  const bootTag = deps.bootTag ?? Math.floor(Date.now() / 1000).toString(36);
  const state = createState({ clock, bootTag });
  const webhooks = createWebhookSender({
    state,
    clock,
    request: deps.request ?? httpRequest(config),
    pause: deps.pause,
  });

  const pending = new Set();

  /** Отложенное действие (исход платежа, статус письма). Исключение не роняет процесс, а попадает в журнал. */
  function schedule(delayMs, fn, label = 'task') {
    const task = new Promise((resolve) => {
      setTimeout(async () => {
        try {
          await fn();
        } catch (e) {
          state.record({ kind: 'internal', system: 'stubs', outcome: 'error', note: `${label}: ${e.message}` });
        }
        resolve();
      }, delayMs);
    }).finally(() => pending.delete(task));
    pending.add(task);
    return task;
  }

  /** Ждёт, пока выполнятся все отложенные действия и отправки. Нужна тестам. */
  async function idle() {
    while (pending.size) await Promise.all([...pending]);
  }

  return { config, clock, state, webhooks, schedule, idle };
}
