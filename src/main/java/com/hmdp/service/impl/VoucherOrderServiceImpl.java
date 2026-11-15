package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    //获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }*/
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    //获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断获取消息是否成功
                    if(list == null ||list.isEmpty()){
                        //如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //如果获取成功，可以下单
                        //首先解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    //获取消息中的entry
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values,new VoucherOrder(),true);
                    //下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }
        }
        private void handlePendingList(){
            while(true){
                try {
                    //获取pendingList中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断获取消息是否成功
                    if(list == null ||list.isEmpty()){
                        //如果获取失败，说明pendingList中没有消息，break
                        break;
                    }
                    //首先解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    //获取消息中的entry
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values,new VoucherOrder(),true);
                    //下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理pendingList异常",e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    private IVoucherOrderService proxy;
    //在线程池中，这里面不能使用ThreadLocal
    private void handleVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {
        Long userId = voucherOrder.getUserId();
        //创建锁对象，获取锁
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        //如果获取锁不成功
        if(!isLock){
            log.error("不允许重复下单");
        }
        try{
            proxy.createVoucherOrder(voucherOrder);
        }finally{
            lock.unlock();
        }
    }

    public void createVoucherOrder(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //一人一单，根据优惠卷id和用户id进行查询订单是否存在
        int count= query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count>0){
            log.error("该用户已经购买过了");
        }
        //扣除库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id",voucherId).gt("stock",0)
                .update();
        if(!success){
            log.error("库存不足");
        }
        save(voucherOrder);
    }

    /**
     * 消息队列实现秒杀
     * @param voucherId
     * @return
     * @throws InterruptedException
     */
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //创建订单id
        long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本，判断是否有购买资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        int r = result.intValue();
        //2.如果有购买资格，将下单信息保存到阻塞队列中
        if(r!=0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        //获取成员变量
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //3.返回订单id
        return Result.ok(orderId);
    }




    /**
     * 实现异步秒杀
     * @param voucherId
     * @return
     * @throws InterruptedException
     */

    /*
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本，判断是否有购买资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        int r = result.intValue();
        //2.如果有购买资格，将下单信息保存到阻塞队列中
        if(r!=0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        //保存阻塞队列
        //创建订单id
        long orderId = redisIdWorker.nextId("order");
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);
        //代金卷id
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        //获取成员变量
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //3.返回订单id
        return Result.ok(orderId);
    }

    */


    /**
     * 实现秒杀
     * @param voucherId
     * @return
     */
    /*
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        SeckillVoucher voucher  = seckillVoucherService.getById(voucherId);
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //尚未开始
            return Result.fail("秒杀尚未开始!");
        }
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            //已经结束
            return Result.fail("秒杀已经结束");
        }
        //查看库存是否充足
        if(voucher.getStock()<1){
            return Result.fail("库存不足");
        }
        //返回订单id
        Long userId = UserHolder.getUser().getId();
        //根据用户一人一锁并且在代理对象上设置事务。
//        synchronized(userId.toString().intern()) {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
        //创建锁对象，获取锁
        //需要在key上添加一个userId，因为不同的用户是不同的锁
        //SimpleRedisLock lock = new SimpleRedisLock("order"+userId,stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        //如果获取锁不成功
        if(!isLock){
            return Result.fail("一人只允许下单一次");
        }
        try{
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally{
            lock.unlock();
        }
    }
     */


    @Transactional
    public  Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
            //一人一单，根据优惠卷id和用户id进行查询订单是否存在
            int count= query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if(count>0){
                return Result.fail("该用户已经购买过了");
            }
            //扣除库存
            boolean success = seckillVoucherService.update().setSql("stock=stock-1")
                    .eq("voucher_id",voucherId).gt("stock",0)
                    .update();
            if(!success){
                return Result.fail("库存不足");
            }
            //创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //创建订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //用户id
            voucherOrder.setUserId(userId);
            //代金卷id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            return Result.ok();
    }


}
