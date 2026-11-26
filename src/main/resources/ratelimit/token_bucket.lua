-- ============================================================
-- token_bucket.lua
-- [무엇] 토큰 버킷 호출 제한 스크립트 (Redis 안에서 실행)
-- [역할] 앱 하나의 "토큰 양동이"를 읽고 → 지난 시간만큼 채우고 → 토큰 1개를 꺼낼 수 있는지 판단하고 → 다시 저장한다.
--        이 네 단계를 Redis가 스크립트 하나로 "한 번에" 실행하므로, 요청 100개가 동시에 와도
--        두 요청이 같은 토큰을 동시에 꺼내 가는 일(경쟁 상태)이 생기지 않는다.
--        (Redis는 스크립트를 실행하는 동안 다른 명령을 끼워 넣지 않는다)
-- [누가 쓰나] RateLimiter(Java)가 StringRedisTemplate.execute로 요청마다 한 번 호출
--
-- 입력
--   KEYS[1] : 양동이 키. 예) ratelimit:app:42
--   ARGV[1] : capacity            양동이 크기 (예: 10)
--   ARGV[2] : refill-per-second   1초에 채워지는 토큰 수 (소수 가능, 예: 1, 0.5)
--   ARGV[3] : ttl-ms              키 유효 시간(밀리초). 이 시간 동안 안 쓰면 Redis가 키를 지운다
--
-- 저장 모양 (Hash)
--   tokens : 남은 토큰 수 (소수일 수 있다. 예: 3.42 → 0.42개만큼 채워지는 중)
--   ts     : tokens를 마지막으로 계산한 시각 (밀리초, Redis 시계 기준)
--
-- 반환 (정수 3개짜리 배열)
--   [1] allowed      : 1 = 통과, 0 = 거절
--   [2] remaining    : 남은 토큰 (내림한 정수) → X-RateLimit-Remaining
--   [3] retry-after  : 다음 토큰 1개가 생길 때까지 남은 시간(밀리초). 지금 1개 이상 있으면 0
--   ※ Redis는 Lua의 소수를 돌려줄 때 소수점 아래를 그냥 버리므로, 여기서 직접 내림·올림해서 돌려준다
-- ============================================================

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_per_ms = tonumber(ARGV[2]) / 1000   -- 초당 → 밀리초당 채워지는 양
local ttl_ms = tonumber(ARGV[3])

-- 지금 시각: 서버(Spring) 시계가 아니라 Redis 시계를 쓴다.
-- 서버가 여러 대면 서버마다 시계가 조금씩 다를 수 있는데, Redis는 하나라 모두 같은 시계를 보게 된다.
-- TIME은 {초, 마이크로초}를 돌려준다 → 밀리초로 바꿈
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

-- 1) 읽기
local saved = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(saved[1])
local ts = tonumber(saved[2])

if tokens == nil or ts == nil then
  -- 처음 온 앱(또는 TTL로 지워진 앱): 양동이가 가득 찬 상태로 시작
  tokens = capacity
  ts = now
end

-- 2) 채우기: 지난 시간 × 채우는 속도, 단 양동이 크기를 넘지 않게
--    (Redis 시계가 혹시 뒤로 가도(now < ts) 토큰을 빼앗지 않도록 지난 시간이 양수일 때만 채운다)
local elapsed = now - ts
if elapsed > 0 then
  tokens = math.min(capacity, tokens + elapsed * refill_per_ms)
  ts = now
end

-- 3) 꺼내기: 1개 이상 있으면 1개 쓰고 통과, 없으면 거절 (거절할 때는 토큰을 줄이지 않는다)
local allowed = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
end

-- 다음 토큰 1개가 생길 때까지 남은 시간 (지금 1개 이상 남아 있으면 0)
local retry_after_ms = 0
if tokens < 1 then
  retry_after_ms = math.ceil((1 - tokens) / refill_per_ms)
end

-- 4) 쓰기 + 유효 시간 갱신 (오래 안 쓰는 앱의 키가 Redis에 계속 쌓이지 않게)
redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(ts))
redis.call('PEXPIRE', key, ttl_ms)

return { allowed, math.floor(tokens), retry_after_ms }
