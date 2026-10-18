-- ============================================================================
--  库存段领取（segment claim）
--
--  KEYS[1] = 活动库存 key       seckill:stock:{act:N}
--  KEYS[2] = 段登记 hash        seckill:segment:{act:N}
--  ARGV[1] = 实例 ID
--  ARGV[2] = 期望领取数量
--  ARGV[3] = 段登记 TTL（毫秒）
--
--  返回：领取到的数量；-1 表示活动未预热；0 表示已无库存可领。
--
--  为什么必须是 Lua：
--    "读库存 → 判断够不够 → 扣减 → 登记"四步如果分开做，
--    两个实例会在"判断够不够"之后同时扣减，把库存扣成负数。
--
--  为什么登记要带 TTL：
--    段是"已经从 Redis 拿走、但还没卖出去"的库存。实例崩溃时它不会自己回来，
--    TTL 到期后登记消失 ⇒ 对账任务把它们算作"实例已死、库存未归还"，
--    从而把这批库存补回去。没有 TTL，崩溃一次就永久少卖一批。
-- ============================================================================

local stock = redis.call('GET', KEYS[1])
if not stock then
    return -1
end

local available = tonumber(stock)
if available <= 0 then
    return 0
end

local want = tonumber(ARGV[2])
local claim = want
if claim > available then
    claim = available
end
if claim <= 0 then
    return 0
end

redis.call('DECRBY', KEYS[1], claim)
redis.call('HINCRBY', KEYS[2], ARGV[1], claim)
redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[3]))

return claim
