--[[
================================================================================
 在途计数归还（订单成功落库后调用）

 正常链路：Lua 扣减时 INCR，消费者落库成功后 DECR。
 inflight = Redis 已扣减 - 已落库，是订单落库链路的「在途水位」。
 它同时是一个非常好用的监控指标：长时间不归零说明消费端堆积了。

 参数：
   KEYS[1] seckill:inflight:{act:N}
 返回：归还后的在途数
================================================================================
]]

local key = KEYS[1]

local inflight = tonumber(redis.call('GET', key) or '0')
if inflight <= 0 then
    return 0
end
return redis.call('DECR', key)
