package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询店铺类型列表并缓存
     * @return
     */
    public Result queryList() {
        String key = "shoptype";
        String StrJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(StrJson)){
            return Result.ok(JSONUtil.toList(StrJson, ShopType.class));
        }
        //不存在缓存中，查询整个数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //不存在报错
        if (CollectionUtil.isEmpty(typeList)) {
            return Result.fail("出错了，数据不存在");
        }
        //将数据存入缓存中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);
    }
}
