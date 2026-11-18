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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.statement.select.KSQLWindow;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.hmdp.utils.SystemConstants;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
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

    @Override
    public Result signCount() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = "sign:"+userId+keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取本月截至今天为止的所有签到数据,返回的是一个十进制的数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
                );
        if(result==null||result.isEmpty()){
            return Result.ok(0);
        }
        Long num  = result.get(0);
        if(num==null||num==0){
            return Result.ok(0);
        }
        //循环遍历
        int count=0;
        while(true){
            //让这个数字与1做与运算，然后右移，最终与运算结果为1的数量就是截至今天为止连续签到天数
            if((num&1)==0){
                //如果为0说明未签到结束
                break;
            }else{
                count++;
            }
            num=num>>1;
        }
        return Result.ok(count);
    }

    @Override
    public Result sign() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = "sign:"+userId+keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //写入redis SETBIT KEY OFFSET 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }




    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 用户登录
     * @param loginForm
     * @param session
     * @return
     */

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
