package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //这个是下方计算出来的2000年1月1日0分0s的秒数
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    //序列号的位数
    private static final int COUNT_BITS = 32;
    public long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //2.生成序列号
        //如果都使用一个相同的key，当时间一长就会越来越大，Redis单个key的值会有一个上限
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+data);
        //3.拼接并返回
        return timestamp<<COUNT_BITS | count;


    }

    public static void main(String[] args){
        LocalDateTime time = LocalDateTime.of(2022,1,1,0,0,0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second= "+second);
    }
}
