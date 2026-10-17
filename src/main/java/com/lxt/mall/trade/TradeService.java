package com.lxt.mall.trade;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lxt.mall.common.api.ResultCode;
import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.entity.Order;
import com.lxt.mall.domain.entity.OrderItem;
import com.lxt.mall.domain.entity.Product;
import com.lxt.mall.domain.entity.Sku;
import com.lxt.mall.domain.enums.OrderBizType;
import com.lxt.mall.domain.enums.OrderStatus;
import com.lxt.mall.domain.mapper.OrderItemMapper;
import com.lxt.mall.domain.mapper.OrderMapper;
import com.lxt.mall.domain.mapper.ProductMapper;
import com.lxt.mall.domain.mapper.SkuMapper;
import com.lxt.mall.order.dto.OrderVO;
import com.lxt.mall.trade.dto.SkuVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 普通交易（SKU 下单）。
 *
 * <h3>它和秒杀最大的差别：这里是同步的</h3>
 * 秒杀走「Redis 预扣 → MQ 削峰 → 异步落库」，代价是用户要轮询结果。
 * 普通下单<b>不需要</b>这套机制：并发量级差了几个数量级，用户也期望
 * "点一下就看到订单号"。
 *
 * <p>所以这里老老实实做同步事务：
 * <pre>
 *   条件 UPDATE 扣 SKU 库存 → 写订单 → 写订单项
 * </pre>
 * 三条语句在同一个事务里，失败全回滚。
 *
 * <p><b>这个对照本身就是设计的一部分</b>：很多项目把"高并发"当信仰，
 * 给所有写链路都套上 MQ 和缓存，结果普通下单也要轮询、退款延迟几分钟。
 * 正确的做法是按场景选架构——秒杀要吞吐，普通交易要确定性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeService {

    private final SkuMapper skuMapper;
    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final CouponService couponService;
    private final MallProperties properties;
    private final Clock clock;

    /** 商品的可售 SKU 列表。 */
    public List<SkuVO> listSkus(Long productId) {
        List<Sku> skus = skuMapper.selectList(Wrappers.<Sku>lambdaQuery()
                .eq(Sku::getProductId, productId)
                .eq(Sku::getStatus, 1)
                .orderByAsc(Sku::getId));
        return skus.stream().map(SkuVO::from).toList();
    }

    /**
     * 普通下单（同步事务）。
     *
     * <p>库存扣减用条件更新，影响行数为 0 就是"卖完了"：
     * 不需要先查再判断，也就没有"查的时候有货、写的时候没了"的窗口。
     *
     * <p>注意这里每个公开重载都标了 {@code @Transactional}：
     * Spring 的事务是<b>代理</b>实现的，同类内部调用（this.createOrder(...)）
     * 不会经过代理，也就不会开事务。把每个入口都标上，才能保证
     * 无论从哪个重载进来都在事务里。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderVO createOrder(Long userId, Long skuId, int quantity) {
        return createOrder(userId, skuId, quantity, null, OrderBizType.NORMAL);
    }

    /**
     * 普通下单（可叠加优惠券）。
     *
     * <p>库存扣减、券核销、订单落库在<b>同一个事务</b>里：
     * 任何一步失败都整体回滚，不会出现"券扣了但订单没建"这种资损形态。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderVO createOrder(Long userId, Long skuId, int quantity, Long couponId) {
        return createOrder(userId, skuId, quantity, couponId, OrderBizType.NORMAL);
    }

    /** 下单（指定订单来源：普通 / 拼团）。 */
    @Transactional(rollbackFor = Exception.class)
    public OrderVO createOrder(Long userId, Long skuId, int quantity, Long couponId, OrderBizType bizType) {
        Sku sku = skuMapper.selectById(skuId);
        if (sku == null || !Integer.valueOf(1).equals(sku.getStatus())) {
            throw new BusinessException(ResultCode.SKU_NOT_FOUND);
        }

        int deducted = skuMapper.deductStock(skuId, quantity);
        if (deducted == 0) {
            throw new BusinessException(ResultCode.SKU_STOCK_NOT_ENOUGH);
        }

        Product product = productMapper.selectById(sku.getProductId());
        BigDecimal amount = sku.getPrice().multiply(BigDecimal.valueOf(quantity));
        String orderNo = "NO" + IdWorker.getIdStr();

        BigDecimal discount = BigDecimal.ZERO;
        if (couponId != null) {
            discount = couponService.useForOrder(userId, couponId, amount, orderNo);
        }

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setBizType(bizType.getCode());
        order.setSkuId(skuId);
        order.setProductId(sku.getProductId());
        order.setProductTitle(product == null ? sku.getSkuCode() : product.getTitle());
        // 字段名沿用 seckill_price 是历史包袱：它的真实语义是"成交单价快照"。
        // 改名需要改表 + 改所有引用，收益只有可读性，留到订单模型重构时一起做。
        order.setSeckillPrice(sku.getPrice());
        order.setQuantity(quantity);
        order.setCouponId(couponId);
        order.setDiscountAmount(discount);
        order.setPayAmount(amount.subtract(discount));
        order.setStatus(OrderStatus.CREATED);
        order.setExpireTime(LocalDateTime.now(clock).plus(properties.getTrade().getOrderExpire()));
        orderMapper.insert(order);

        OrderItem item = new OrderItem();
        item.setId(IdWorker.getId());
        item.setOrderId(order.getId());
        item.setOrderNo(order.getOrderNo());
        item.setProductId(sku.getProductId());
        item.setProductTitle(order.getProductTitle());
        item.setSeckillPrice(sku.getPrice());
        item.setQuantity(quantity);
        orderItemMapper.insert(item);

        log.info("普通订单创建成功 orderNo={} skuId={} quantity={} amount={} discount={} payable={}",
                order.getOrderNo(), skuId, quantity, amount, discount, order.getPayAmount());
        return OrderVO.from(order);
    }
}
