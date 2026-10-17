package com.lxt.mall.seckill;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lxt.mall.common.api.ResultCode;
import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.common.util.JsonUtils;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.entity.Product;
import com.lxt.mall.domain.entity.SeckillActivity;
import com.lxt.mall.domain.enums.ActivityStatus;
import com.lxt.mall.domain.mapper.ProductMapper;
import com.lxt.mall.domain.mapper.SeckillActivityMapper;
import com.lxt.mall.seckill.dto.SeckillActivityVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀活动查询与快照缓存。
 *
 * <h3>三级读取</h3>
 * <pre>
 *   本地 Caffeine（5 秒）  →  Redis 快照  →  MySQL
 * </pre>
 * <p>为什么本地缓存只有 5 秒？因为活动信息（开始/结束时间）一旦被修改，
 * 多实例的本地缓存无法互相失效，5 秒就是这个不一致窗口的上界。
 * 5 秒之内秒杀接口完全不访问 Redis 和数据库，这是它能扛住
 * 突发流量的根本原因——大部分请求在第一个 if 判断里就被处理完了。
 *
 * <p>为什么库存不放在这个快照里？库存需要被 Lua 原子 DECR，
 * 而 JSON 里的数值没法做原子操作。库存单独用 String key 存，
 * 这是"哪种数据用哪种 Redis 结构"的典型判断。
 */
@Slf4j
@Service
public class SeckillActivityService {

    private final SeckillActivityMapper activityMapper;
    private final ProductMapper productMapper;
    private final StringRedisTemplate redis;
    private final MallProperties properties;
    private final Clock clock;

    /** L1：活动快照的进程内缓存。 */
    private final Cache<Long, SeckillActivitySnapshot> localCache;

    public SeckillActivityService(SeckillActivityMapper activityMapper,
                                  ProductMapper productMapper,
                                  StringRedisTemplate redis,
                                  MallProperties properties,
                                  Clock clock) {
        this.activityMapper = activityMapper;
        this.productMapper = productMapper;
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;

        MallProperties.Seckill seckill = properties.getSeckill();
        this.localCache = Caffeine.newBuilder()
                .maximumSize(seckill.getActivityLocalMaxSize())
                .expireAfterWrite(seckill.getActivityLocalTtl().toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    // ========================================================================
    // 热路径：取快照
    // ========================================================================

    public SeckillActivitySnapshot getSnapshot(Long activityId) {
        SeckillActivitySnapshot cached = localCache.getIfPresent(activityId);
        if (cached != null) {
            return cached;
        }

        // L2：Redis 快照（预热时写入）
        String key = com.lxt.mall.common.constant.RedisKeys.seckillActivity(activityId);
        String json = redis.opsForValue().get(key);
        if (json != null) {
            try {
                SeckillActivitySnapshot snapshot = JsonUtils.fromJson(json, SeckillActivitySnapshot.class);
                if (snapshot != null) {
                    localCache.put(activityId, snapshot);
                    return snapshot;
                }
            } catch (Exception e) {
                log.warn("活动快照反序列化失败，将回源数据库 activityId={}", activityId, e);
            }
        }

        // L3：数据库回源（冷启动、缓存被清、活动未预热）
        SeckillActivitySnapshot snapshot = loadFromDb(activityId);
        if (snapshot == null) {
            return null;
        }
        long ttlMillis = Math.max(60_000L, properties.getSeckill().getActivityLocalTtl().toMillis() * 12);
        try {
            redis.opsForValue().set(key, JsonUtils.toJson(snapshot), Duration.ofMillis(ttlMillis));
        } catch (Exception e) {
            log.warn("回填活动快照到 Redis 失败 activityId={}", activityId, e);
        }
        localCache.put(activityId, snapshot);
        return snapshot;
    }

    public SeckillActivitySnapshot requireSnapshot(Long activityId) {
        SeckillActivitySnapshot snapshot = getSnapshot(activityId);
        if (snapshot == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        return snapshot;
    }

    private SeckillActivitySnapshot loadFromDb(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            return null;
        }
        Product product = productMapper.selectById(activity.getProductId());
        return new SeckillActivitySnapshot(
                activity.getId(),
                activity.getActivityName(),
                activity.getProductId(),
                product == null ? "未知商品" : product.getTitle(),
                activity.getSeckillPrice(),
                product == null ? null : product.getPrice(),
                activity.getTotalStock(),
                activity.getStartTime(),
                activity.getEndTime(),
                activity.getStatus(),
                LocalDateTime.now(clock));
    }

    // ========================================================================
    // 列表查询
    // ========================================================================

    public List<SeckillActivityVO> listActivities(Integer status) {
        List<SeckillActivity> activities = activityMapper.selectList(
                Wrappers.<SeckillActivity>lambdaQuery()
                        .eq(status != null, SeckillActivity::getStatus, status)
                        .orderByAsc(SeckillActivity::getStartTime));

        List<SeckillActivityVO> result = new ArrayList<>(activities.size());
        for (SeckillActivity activity : activities) {
            result.add(toVO(activity));
        }
        return result;
    }

    public SeckillActivityVO getActivityVO(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        return toVO(activity);
    }

    private SeckillActivityVO toVO(SeckillActivity activity) {
        Product product = productMapper.selectById(activity.getProductId());
        SeckillActivityVO vo = new SeckillActivityVO();
        vo.setId(activity.getId());
        vo.setActivityName(activity.getActivityName());
        vo.setProductId(activity.getProductId());
        vo.setProductTitle(product == null ? null : product.getTitle());
        vo.setCoverImg(product == null ? null : product.getCoverImg());
        vo.setOriginalPrice(product == null ? null : product.getPrice());
        vo.setSeckillPrice(activity.getSeckillPrice());
        vo.setTotalStock(activity.getTotalStock());
        vo.setAvailableStock(activity.getAvailableStock());
        vo.setStartTime(activity.getStartTime());
        vo.setEndTime(activity.getEndTime());
        vo.setStatus(activity.getStatus());
        vo.setStatusDesc(ActivityStatus.of(activity.getStatus()).getDesc());
        vo.setServerTime(LocalDateTime.now(clock));
        return vo;
    }

    // ========================================================================
    // 缓存维护
    // ========================================================================

    public void evictLocal(Long activityId) {
        localCache.invalidate(activityId);
    }

    public void evictAllLocal() {
        localCache.invalidateAll();
    }
}
