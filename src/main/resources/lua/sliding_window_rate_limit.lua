--[[
================================================================================
 滑动窗口限流（ZSET 实现）

 原理：
   用一个 ZSET 存窗口内的请求，score = 请求时间戳(ms)，member = 请求唯一ID。
   每次请求先 ZREMRANGEBYSCORE 清掉 [0, now-window] 的旧记录，
   再 ZCARD 数窗口内的请求数，未超阈值就 ZADD 记录本次请求。
   整套动作在 Lua 里是原子的，不存在"检查完再写"之间的竞态。

 与固定窗口（INCR + EXPIRE）的区别：
   固定窗口在窗口边界会被打穿——第 1 秒最后 10ms 打 100 个，
   第 2 秒最初 10ms 再打 100 个，2 秒内实际放过了 200 个，
   而配置的阈值是"每秒 100"。滑动窗口没有这个问题。

 代价（面试常追问）：
   内存 O(window 内请求数)，每个请求都是一个 ZSET 成员（约 100 字节）。
   限流阈值很高时（比如 10w/s）内存开销不可忽略，此时应换令牌桶（O(1)）,
   或者用 Redis 的滑动窗口分桶近似实现。
   本项目秒杀接口阈值很低（5/s），ZSET 方案的精度收益远大于内存成本。

 参数：
   KEYS[1] 限流 key（已按 维度+接口+用户/IP 拼好）
   ARGV[1] now     当前时间戳(ms) —— 由【应用侧】传入而不是脚本里 redis.call('TIME')，
                   一是能复用同一份时钟避免多命令时间漂移，
                   二是测试时可以注入时间做确定性验证。
   ARGV[2] window  窗口长度(ms)
   ARGV[3] limit   窗口内允许的最大请求数
   ARGV[4] member  本次请求的唯一标识

 返回：
    >= 0  放行，值为放行后本窗口剩余额度
      -1  被限流
================================================================================
]]

local key    = KEYS[1]
local now    = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit  = tonumber(ARGV[3])

-- 1. 清理滑出窗口的历史请求
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- 2. 统计窗口内请求数
local count = redis.call('ZCARD', key)

-- 3. 超限直接拒绝（注意：被拒绝的请求不写入 ZSET，
--    否则持续压测会不断把窗口"续命"，把攻击者自己锁死的同时也推高内存）
if count >= limit then
    return -1
end

-- 4. 记录本次请求
redis.call('ZADD', key, now, ARGV[4])

-- 5. 续期。+1000ms 是为了覆盖窗口内最后一条记录的存活时间，
--    否则极端情况下 ZSET 会在窗口结束前被清理，导致限流失效。
redis.call('PEXPIRE', key, window + 1000)

return limit - count - 1
