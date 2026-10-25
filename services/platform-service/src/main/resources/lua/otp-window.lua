-- Ограничение частоты запросов кода одним вызовом (ADR-010, NFT-3.5).
-- Скрипт общий: им же считает окно создания обращений RedisSupportRateLimiter (одно скользящее окно в час, Р-177).
--
-- Окна проверяются все сразу, и только если ни одно не исчерпано, запрос записывается во все: отказ ничего не расходует, поэтому
-- настойчивые повторы не продлевают собственную блокировку. Время приходит от вызывающего (Clock сервиса), а не из Redis: пользователю
-- platform команда TIME закрыта ACL, а тесты управляют временем.
--
-- KEYS: ключи окон
-- ARGV[1] текущее время, мс; ARGV[2] уникальная метка запроса;
-- дальше для каждого ключа по три значения: вид ('z' скользящее окно, 'c' счётчик с фиксированным окном), длина окна, мс, предел
--
-- Ответ: 'D:<мс>' запрос не допущен, через столько освободится самое долгое из исчерпанных окон;
--        'A:<осталось>:<мс>' запрос допущен; осталось запросов в самом тесном скользящем окне и, если не осталось, через сколько оно освободится.
local now = tonumber(ARGV[1])
local token = ARGV[2]
local denied = 0
local used = {}
local oldest = {}

for i = 1, #KEYS do
  local base = 2 + 3 * (i - 1)
  local kind = ARGV[base + 1]
  local window = tonumber(ARGV[base + 2])
  local limit = tonumber(ARGV[base + 3])
  local count
  local free
  if kind == 'z' then
    redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now - window)
    count = redis.call('ZCARD', KEYS[i])
    if count > 0 then
      local first = redis.call('ZRANGE', KEYS[i], 0, 0, 'WITHSCORES')
      oldest[i] = tonumber(first[2])
    end
    free = (oldest[i] or now) + window - now
  else
    count = tonumber(redis.call('GET', KEYS[i]) or '0')
    local ttl = redis.call('PTTL', KEYS[i])
    if ttl > 0 then free = ttl else free = window end
  end
  used[i] = count
  if count >= limit then
    if free < 1 then free = 1 end
    if free > denied then denied = free end
  end
end

if denied > 0 then
  return 'D:' .. string.format('%d', denied)
end

local remaining = nil
local resend = 0
for i = 1, #KEYS do
  local base = 2 + 3 * (i - 1)
  local kind = ARGV[base + 1]
  local window = tonumber(ARGV[base + 2])
  local limit = tonumber(ARGV[base + 3])
  if kind == 'z' then
    redis.call('ZADD', KEYS[i], now, token .. ':' .. i)
    redis.call('PEXPIRE', KEYS[i], window)
    local left = limit - used[i] - 1
    if remaining == nil or left < remaining then
      remaining = left
    end
    if left == 0 then
      local free = (oldest[i] or now) + window - now
      if free > resend then resend = free end
    end
  else
    local value = redis.call('INCR', KEYS[i])
    if value == 1 then
      redis.call('PEXPIRE', KEYS[i], window)
    end
  end
end

if remaining == nil then remaining = 0 end
if remaining > 0 then resend = 0 end
return 'A:' .. string.format('%d', remaining) .. ':' .. string.format('%d', resend)
