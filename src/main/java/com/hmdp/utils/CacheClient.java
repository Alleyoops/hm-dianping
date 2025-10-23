package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    public <T,ID> T queryWithPassThrough(
            String keyPrefix, ID id, Class<T> type, Function<ID,T> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否是空值
        if (json != null) {
            return null;
        }
        T t = dbFallback.apply(id);
        if (t == null) {
            //不存在则将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        set(key, t, time, unit);
        return t;
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1");
        return flag != null && flag;
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
    public <T,ID> T queryWithLogicExpire(
            String keyPrefix, ID id, Class<T> type, Function<ID,T> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        T t = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            return t;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    dbFallback.apply(id);
                    setWithLogicExpire(key, t, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        return t;
    }
}
