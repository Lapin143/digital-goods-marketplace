// Размер собранного пакета (шаг 16 Ф4): сумма файлов JavaScript в dist/assets после сжатия gzip не больше 220 КБ,
// CSS не больше 40 КБ. Превышение значит, что в пакет попала лишняя зависимость или экраны надо грузить по требованию.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'dist');
const limits = { '.js': 220 * 1024, '.css': 40 * 1024 };

function files(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path) : [path];
  });
}

const totals = { '.js': 0, '.css': 0 };
for (const path of files(root)) {
  const ext = path.slice(path.lastIndexOf('.'));
  if (ext in totals) totals[ext] += gzipSync(readFileSync(path)).length;
}
let failed = false;
for (const [ext, limit] of Object.entries(limits)) {
  const kb = (totals[ext] / 1024).toFixed(1);
  const ok = totals[ext] > 0 && totals[ext] <= limit;
  console.log(`${ok ? 'ok     ' : 'ОШИБКА '} ${ext}: ${kb} КБ gzip, предел ${limit / 1024} КБ`);
  if (!ok) failed = true;
}
process.exit(failed ? 1 : 0);
