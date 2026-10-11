-- Проверка и погашение одноразового кода одним неделимым действием (ADR-010, NFT-3.7).
--
-- Две команды (прочитать код, потом удалить) пропустили бы между собой параллельную попытку: при 50 одновременных попытках с верным кодом
-- успешными оказались бы несколько. Скрипт выполняется в Redis целиком, поэтому успешна ровно одна.
--
-- KEYS: 1 запись кода, 2 счётчик кодов, погашенных исчерпанием попыток, 3 замок номера (2 и 3 нужны, когда ARGV[4] равно '1')
-- ARGV: 1 текущее время, мс; 2 HMAC введённого кода; 3 ожидаемый идентификатор подтверждения ('' если не сверять);
--       4 вести ли учёт погашенных и замок номера ('1' или '0'); 5 сколько погашенных кодов закрывают номер;
--       6 окно счёта погашенных, мс; 7 срок закрытия номера, мс
--
-- Запись кода: JSON {h: HMAC кода, x: срок, мс, a: осталось попыток, u: учётная запись ('' у кода-приманки), c: идентификатор подтверждения, p: номер}.
--
-- Ответ: 'V:<учётная запись>:<номер>' верно, код погашен; 'I:<попыток осталось>' неверно;
--        'L:<мс>' попытки исчерпаны, код погашен (мс больше нуля: номер закрыт на это время);
--        'E' срок истёк; 'N' кода нет; 'M' код относится к другому подтверждению; 'K:<мс>' номер закрыт, проверка не выполнялась.
local now = tonumber(ARGV[1])
local track = ARGV[4] == '1'

if track then
  local lock = redis.call('GET', KEYS[3])
  if lock then
    local left = tonumber(lock) - now
    if left < 1 then left = 1 end
    return 'K:' .. string.format('%d', left)
  end
end

local raw = redis.call('GET', KEYS[1])
if not raw then
  return 'N'
end
local record = cjson.decode(raw)

if ARGV[3] ~= '' and record.c ~= ARGV[3] then
  return 'M'
end
if now > record.x then
  return 'E'
end

if record.h == ARGV[2] and record.u ~= '' then
  redis.call('DEL', KEYS[1])
  if track then
    redis.call('DEL', KEYS[2])
  end
  return 'V:' .. record.u .. ':' .. record.p
end

record.a = record.a - 1
if record.a <= 0 then
  redis.call('DEL', KEYS[1])
  if track then
    local burned = redis.call('INCR', KEYS[2])
    if burned == 1 then
      redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[6]))
    end
    if burned >= tonumber(ARGV[5]) then
      local lockMs = tonumber(ARGV[7])
      redis.call('SET', KEYS[3], string.format('%d', now + lockMs), 'PX', lockMs)
      redis.call('DEL', KEYS[2])
      return 'L:' .. string.format('%d', lockMs)
    end
  end
  return 'L:0'
end
redis.call('SET', KEYS[1], cjson.encode(record), 'KEEPTTL')
return 'I:' .. string.format('%d', record.a)
