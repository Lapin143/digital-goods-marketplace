// Точка входа контейнера external-stubs.
import { loadConfig } from './config.mjs';
import { createStubs } from './server.mjs';

let config;
try {
  config = loadConfig();
} catch (e) {
  console.error(`external-stubs: ошибка настройки: ${e.message}`);
  process.exit(2);
}

const stubs = createStubs(config);
const ports = await stubs.start();
console.log(
  `external-stubs: слушаем API ${config.tls ? 'https' : 'http'}://${config.bind}:${ports.apiPort}, админ-порт ${ports.adminPort}, SMTP ${ports.smtpPort}`,
);

let stopping = false;
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, async () => {
    if (stopping) return;
    stopping = true;
    console.log(`external-stubs: получен ${signal}, останавливаемся`);
    await stubs.stop();
    process.exit(0);
  });
}
