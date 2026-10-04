// Минимальная проверка по JSON Schema: только те ключевые слова, которые попадают в файл контракта
// (tools/docs-checks/check_stub_contract.py). Внешних пакетов нет, поэтому валидатор свой и небольшой.
export function validate(schema, value, path = '$') {
  const errors = [];
  const err = (msg) => errors.push(`${path}: ${msg}`);
  if (schema.type === 'object') {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return [`${path}: ожидается объект`];
    for (const name of schema.required ?? []) if (!(name in value)) err(`нет обязательного поля ${name}`);
    const props = schema.properties ?? {};
    for (const [name, v] of Object.entries(value)) {
      if (name in props) errors.push(...validate(props[name], v, `${path}.${name}`));
      else if (schema.additionalProperties === false) err(`лишнее поле ${name}`);
    }
    return errors;
  }
  if (schema.type === 'string') {
    if (typeof value !== 'string') return [`${path}: ожидается строка`];
    if (schema.minLength !== undefined && value.length < schema.minLength) err(`короче ${schema.minLength}`);
    if (schema.maxLength !== undefined && value.length > schema.maxLength) err(`длиннее ${schema.maxLength}`);
    if (schema.pattern && !new RegExp(schema.pattern).test(value)) err(`не подходит под шаблон ${schema.pattern}`);
    if (schema.format === 'date-time' && Number.isNaN(Date.parse(value))) err('не дата и время');
  } else if (schema.type === 'integer') {
    if (!Number.isInteger(value)) return [`${path}: ожидается целое число`];
    if (schema.minimum !== undefined && value < schema.minimum) err(`меньше ${schema.minimum}`);
    if (schema.maximum !== undefined && value > schema.maximum) err(`больше ${schema.maximum}`);
  }
  if (schema.enum && !schema.enum.includes(value)) err(`значение «${value}» не из перечня ${schema.enum.join(', ')}`);
  return errors;
}
