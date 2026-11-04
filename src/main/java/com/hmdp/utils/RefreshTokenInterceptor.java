package com.hmdp.utils;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取请求头中的token
        String token = request.getHeader("authorization");
        //基于token获取redis中的用户
        if(StrUtil.isBlank(token)){
            return true;
        }
        String key=RedisConstants.LOGIN_USER_KEY+ token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //判断用户是否存在
        if(userMap.isEmpty()){
            return true;
        }
        //将查询到的Hash对象转换为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //存在，把用户保存在ThreadLocal中
        UserHolder.saveUser(userDTO);
        //在拦截器中刷新token的有效期
        stringRedisTemplate.expire(key,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
