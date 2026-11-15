--1.参数列表  需要读取redis中的key，前半部分是固定的，优惠卷id需要传
--1.1优惠卷id
local voucherId = ARGV[1]
--1.2用户id
local userId = ARGV[2]

--2.数组key
--2.1库存key
local stockKey = 'seckill:stock:'..voucherId
--2.2订单key
local orderKey = 'seckill:order'..voucherId

--3.脚本业务
--3.1判断库存是否充足 get stockKey
if(tonumber(redis.call('get',stockKey))<=0) then
    return 1
end
--3.2判断用户是否下单 SISMEMBER orderKey
if(redis.call('sismember',orderKey,userId)==1) then
    return 2
end
--3.3用户有下单资格，扣库存 increby stockKey -1
redis.call('incrby',stockKey,-1)
--3.4下单（保存用户） sadd orderKey userId
redis.call('sadd',orderKey,userId)
return 0
