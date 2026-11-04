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
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class LoginInterceptor implements HandlerInterceptor {

//    private StringRedisTemplate stringRedisTemplate;
//    public LoginInterceptor(StringRedisTemplate stringRedisTemplate){
//        this.stringRedisTemplate = stringRedisTemplate;
//    }
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //1.获取Session
//        HttpSession session = request.getSession();
        //获取请求头中的token
        String token = request.getHeader("authorization");
//        //2.获取session中的对象
//        Object user = session.getAttribute("user");
        //基于token获取redis中的用户
        if(StrUtil.isBlank(token)){
            response.setStatus(401);
            return false;
        }
        String key=RedisConstants.LOGIN_USER_KEY+ token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //判断用户是否存在
        if(userMap.isEmpty()){
            response.setStatus(401);
            return false;
        }
        //将查询到的Hash对象转换为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //存在，把用户保存在ThreadLocal中
        UserHolder.saveUser(userDTO);
        //在拦截器中刷新token的有效期
        stringRedisTemplate.expire(key,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //放行
        return true;
//        //3.判断用户是否存在
//        if(user ==null){
//            response.setStatus(401);
//            return false;
//        }
//        //存在，保存用户信息到ThreadLocal并放行
//        UserHolder.saveUser((UserDTO) user);
//        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
