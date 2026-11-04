package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.statement.select.KSQLWindow;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.hmdp.utils.SystemConstants;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
//这里继承了ServiceImpl，是Mybatis-plus的用法，后面还指定了实体类和mapper
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    /**
     * 用户登录
     * @param loginForm
     * @param session
     * @return
     */

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
//        //2.校验验证码
//        Object cacheCode = session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        if(cacheCode==null||!cacheCode.equals(code)){
            //不一致，报错
            return Result.fail("验证码错误");
        }
        //都一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        if(user ==null){
            //不存在，创建用户并存在数据库
            user = createUserWithPhone(phone);
        }
        //保存用户信息到redis中
        //随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //将UserDTO对象转为Hash值存储
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        //这样会报错Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);因为User中有Long类型的数据，long类型的数据不能转换为String
        Map<String,Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        //存储
        String tokenKey = LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //设置有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
//        //此时保存的session信息不宜过多，防止前端拿到私密数据或者内存压力过大
//        session.setAttribute("user", BeanUtil.copyProperties(user,UserDTO.class));
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone){
        //创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
        //保存用户到数据库
        save(user);
        return user;
    }

    /**
     * 发送验证码并保存
     * @param phone
     * @param session
     * @return
     */
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
//        //4.保存验证码到session
//        session.setAttribute("code",code);
        //保存验证码到Redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码(这一步需要手机运营商短信发送到用户，我们这里模拟)
        log.debug("发送短信验证码成功，验证码:{}",code);
        return Result.ok();
    }
}
