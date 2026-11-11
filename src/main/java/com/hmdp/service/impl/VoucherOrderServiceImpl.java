package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    /**
     * 实现秒杀
     * @param voucherId
     * @return
     */

    public Result seckillVoucher(Long voucherId) {
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
        synchronized(userId.toString().intern()) {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

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
