// Типы запросов и ответов из OpenAPI (docs/06-api/openapi): ловят расхождение интерфейса и контракта при проверке типов.
// Результат лежит в src/api/generated и в репозиторий не входит; создаётся перед lint, typecheck и тестами (npm run gen:api).
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const specs = join(root, '..', '..', 'docs', '06-api', 'openapi');
const out = join(root, 'src', 'api', 'generated');
const services = ['catalog', 'platform', 'inventory', 'order'];

mkdirSync(out, { recursive: true });
const npx = process.platform === 'win32' ? 'npx.cmd' : 'npx';
for (const service of services) {
  execFileSync(npx, ['--no-install', 'openapi-typescript', join(specs, `${service}-service.yaml`), '-o', join(out, `${service}.d.ts`)], {
    cwd: root,
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
}
