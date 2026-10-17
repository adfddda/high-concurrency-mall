--[[
================================================================================
 秒杀原子扣减（库存校验 + 扣减 + 一人一单去重 + 在途计数）

 为什么要用 Lua：
   「查库存 → 判断 → 扣库存 → 记录用户」这四步如果分多次 Redis 命令发送，
   中间必然插入其他请求，Redis 单线程也救不了你，会出现超卖。
   Lua 脚本在 Redis 内部【整体串行执行且不可被打断】，四步天然成为原子操作。
   对比分布式锁方案：无需加锁/解锁两次网络往返，也没有锁超时、锁续期、
   误删他人锁等一系列问题，吞吐通常高一个数量级。

 为什么 KEYS 要带 Hash Tag：
   KEYS[1..3] 都写成 seckill:xxx:{act:2001} 形式，大括号里的内容就是
   Redis Cluster 计算槽位（CRC16(key) % 16384）时唯一参考的部分。
   三个 key 同槽，EVAL 才能在集群模式下正常执行；
   否则会直接抛 CROSSSLOT Keys in request don't hash to the same slot。

 参数：
   KEYS[1] 库存              seckill:stock:{act:N}
   KEYS[2] 已购用户集合       seckill:bought:{act:N}
   KEYS[3] 在途计数           seckill:inflight:{act:N}
   ARGV[1] userId
   ARGV[2] 是否统计在途 "1"/"0"（关掉可省一次写，但对账能力会下降）

 返回码：
    0  扣减成功
    1  库存不足（售罄）
    2  重复购买（一人一单）
    3  活动未预热 / 预热数据已过期
================================================================================
]]

local stockKey    = KEYS[1]
local boughtKey   = KEYS[2]
local inflightKey = KEYS[3]
local userId      = ARGV[1]
local trackInflight = ARGV[2]

-- 1. 活动是否已预热。GET 到 nil 说明活动信息已被清理或从未预热，
--    这种情况必须快速失败，绝不能"当作有库存"放过去打 DB。
local stock = redis.call('GET', stockKey)
if not stock then
    return 3
end

-- 2. 一人一单。先判重再判库存：对已经抢到过的用户，
--    返回"重复购买"比返回"已售罄"更准确，前端提示也更友好。
if redis.call('SISMEMBER', boughtKey, userId) == 1 then
    return 2
end

-- 3. 库存判断与扣减。DECR 的原子性保证了 stock 不会为负
--    （MySQL 侧还有 available_stock >= 0 的 CHECK 兜底）。
if tonumber(stock) <= 0 then
    return 1
end

redis.call('DECR', stockKey)
redis.call('SADD', boughtKey, userId)

-- 4. 在途计数：此刻 Redis 已经扣了库存，但订单还没落库。
--    对账任务必须把这个数修掉，否则会把"正常在途"误判成"库存漂移"。
if trackInflight == '1' then
    redis.call('INCR', inflightKey)
end

return 0
