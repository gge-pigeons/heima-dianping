package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    //加上业务前缀，我们这里是下单功能的锁
    private String name;

    private StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREDIX = UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate){
        this.name=name;
        this.stringRedisTemplate=stringRedisTemplate;
    }
    /**
     * 尝试获取锁
     * @param timeoutSec
     * @return
     */
    public boolean tryLock(long timeoutSec) {
        String key = KEY_PREFIX+name;
        //这里value值我们应该加上线程的标识
        String threadId =ID_PREDIX+Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, threadId, timeoutSec, TimeUnit.SECONDS);
        //避免自动拆箱时空指针错误，因为方法的返回值需要boolean,我们提供的是Boolean
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放锁
     */
    public void unlock() {
        String key = KEY_PREFIX+name;
        //释放锁时先判断线程标识是否一致
        String threadId = ID_PREDIX+Thread.currentThread().getId();
        //调用Lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(key),
                threadId
                );
    }
    /*
    public void unlock() {
        String key = KEY_PREFIX+name;
        //释放锁时先判断线程标识是否一致
        String threadId = ID_PREDIX+Thread.currentThread().getId();
        String id = stringRedisTemplate.opsForValue().get(key);
        if (threadId.equals(id)) {
            stringRedisTemplate.delete(key);
        }
    }
    */
}
