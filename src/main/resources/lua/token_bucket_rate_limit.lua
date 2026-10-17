--[[
================================================================================
 令牌桶限流（HASH 实现，O(1) 内存）

 原理：
   桶里以固定速率 rate（个/秒）补充令牌，上限 capacity。
   每个请求要消耗 requested 个令牌，够就放行、不够就拒绝。
   因为允许"攒令牌"，所以天然支持突发流量——这正是它和滑动窗口的核心差异：
     · 滑动窗口：严格限制「任意 window 内的请求数」，削峰最狠，但会把正常突发打散
     · 令牌桶：  限制「长期平均速率 + 突发上限」，对业务更友好
   本项目里：登录接口用令牌桶（用户偶尔连点几下不该被拒），
             秒杀接口用滑动窗口（要的就是严格削峰）。

 参数：
   KEYS[1] 桶 key（HASH，字段 tokens / ts）
   ARGV[1] capacity   桶容量（突发上限）
   ARGV[2] rate       每秒补充令牌数
   ARGV[3] now        当前时间戳(ms)
   ARGV[4] requested  本次请求消耗的令牌数

 返回：
    >= 0  放行，值为剩余令牌数
      -1  拒绝（令牌不足）
================================================================================
]]

local key       = KEYS[1]
local capacity  = tonumber(ARGV[1])
local rate      = tonumber(ARGV[2])
local now       = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local bucket = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts     = tonumber(bucket[2])

-- 桶不存在：按满桶初始化（冷启动允许一次突发）
if tokens == nil then
    tokens = capacity
    ts = now
end

-- 按时间差补充令牌
local delta = now - ts
if delta < 0 then
    -- 时钟回拨保护：不补充，也不让 ts 倒退
    delta = 0
end
local refill = delta * rate / 1000
if refill > 0 then
    tokens = math.min(capacity, tokens + refill)
end

local allowed = -1
if tokens >= requested then
    tokens = tokens - requested
    allowed = math.floor(tokens)
end

-- 用 %.6f 而不是直接写 number：Lua 的 number→string 在极小值时会输出
-- 科学计数法（如 1e-05），tonumber 能读回来但可读性差、也容易被误判成脏数据。
redis.call('HSET', key, 'tokens', string.format('%.6f', tokens), 'ts', now)

-- TTL：桶空了之后最多 capacity/rate 秒重新充满，取两倍时间足够，
-- 保证闲置用户的桶会被自动回收，不会无限占用内存。
local ttl = math.ceil(capacity / rate * 2000) + 1000
redis.call('PEXPIRE', key, ttl)

return allowed
