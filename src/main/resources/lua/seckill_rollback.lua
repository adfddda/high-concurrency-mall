--[[
================================================================================
 秒杀库存回补（补偿脚本）

 触发场景（都是「Redis 已扣减，但订单最终没能落库」）：
   a) 消息投递失败（Confirm 收到 nack，或 Return 回调路由失败）
   b) 消费者落库异常且重试耗尽，消息进死信
   c) 消费者落库时撞上 uk_user_activity / uk_order_no，说明是重复消息
   d) 订单超时未支付被关闭，库存需要放出来给别人抢

 参数里的两个开关决定了语义差异，这是同一个脚本能覆盖四类场景的关键：

   releaseQualification = 1（a/b/c 场景）：
       把用户从「已购集合」里移除，用户重新获得抢购资格。
       适用前提是订单确实没落库，用户不该被判定为"已经买过"。

   releaseQualification = 0（d 场景，超时关单）：
       库存放回去给别人抢，但【不】回收该用户的资格。
       因为一人一单是硬规则（DB 的 uk_user_activity 也不允许同一用户
       再建一单），资格一旦发放就不再回收，否则用户会反复
       在 Redis 通过、在数据库撞唯一键，造成大量无意义的补偿。

 幂等性说明：
   本脚本刻意不做幂等判断，因为它的调用方都已经在更可靠的地方做了去重——
     · a/b/c 场景由 Java 侧的「补偿守卫 key」(SETNX) 保证只执行一次
     · d 场景由数据库 t_stock_flow 的 uk_order_change(order_no, change_type)
       唯一键保证只执行一次（DB 事务先于 Redis 调用成功）
   在 Lua 里再做一层半吊子幂等，只会让语义变模糊、增加出错的可能。

 参数：
   KEYS[1] 库存          seckill:stock:{act:N}
   KEYS[2] 已购用户集合   seckill:bought:{act:N}
   KEYS[3] 在途计数       seckill:inflight:{act:N}
   ARGV[1] userId
   ARGV[2] totalStock           总库存（封顶值）
   ARGV[3] decInflight          "1"/"0" 是否递减在途计数
   ARGV[4] releaseQualification "1"/"0" 是否回收购买资格

 返回：
   -1 库存 key 不存在（活动已结束/被清理），未做任何变更
   其余为回补后的库存值
================================================================================
]]

local stockKey    = KEYS[1]
local boughtKey   = KEYS[2]
local inflightKey = KEYS[3]
local userId      = ARGV[1]
local totalStock  = tonumber(ARGV[2])

local stock = redis.call('GET', stockKey)
if not stock then
    return -1
end

if ARGV[4] == '1' then
    redis.call('SREM', boughtKey, userId)
end

-- 用 INCR 而不是 SET key (stock+1) KEEPTTL：
--   1. INCR 天然保留 key 的 TTL，不需要 KEEPTTL 语法
--      （KEEPTTL 是 Redis 6.0 才引入的，在 5.x 上会直接报语法错误，
--       而很多开发机/老环境跑的正是 5.x）；
--   2. INCR 是原子自增，语义上就比"读出来加一再写回去"更准确。
-- 封顶检查放在自增之前：达到总库存就什么都不做。
-- 这是"宁可少卖，绝不超卖"原则的最后一道保险——
-- 即使补偿逻辑被执行了多次，库存也不可能超过总库存。
local current = tonumber(stock)
if current >= totalStock then
    if ARGV[3] == '1' then
        local inflight = tonumber(redis.call('GET', inflightKey) or '0')
        if inflight > 0 then
            redis.call('DECR', inflightKey)
        end
    end
    return current
end

local newStock = redis.call('INCR', stockKey)

if ARGV[3] == '1' then
    local inflight = tonumber(redis.call('GET', inflightKey) or '0')
    if inflight > 0 then
        redis.call('DECR', inflightKey)
    end
end

return newStock
