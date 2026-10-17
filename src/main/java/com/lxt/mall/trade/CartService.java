package com.lxt.mall.trade;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lxt.mall.common.api.ResultCode;
import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.domain.entity.CartItem;
import com.lxt.mall.domain.entity.Sku;
import com.lxt.mall.domain.mapper.CartItemMapper;
import com.lxt.mall.domain.mapper.SkuMapper;
import com.lxt.mall.trade.dto.CartItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 购物车。
 *
 * <h3>两个刻意的设计</h3>
 * <ol>
 *   <li><b>不缓存价格</b>：只存 sku + 数量，价格实时读 SKU。
 *       购物车显示的是"参考价"，结算才是"成交价"，
 *       混在一起会让调价变成一场数据同步噩梦。</li>
 *   <li><b>加购是幂等累加</b>：同一个人重复加同一 SKU 是"数量 +1"，
 *       由 {@code uk_user_sku} + {@code ON DUPLICATE KEY UPDATE} 一条语句保证，
 *       不需要分布式锁，也不会产生重复行。</li>
 * </ol>
 *
 * <p>下架/无货的 SKU 不删行，而是在返回里标记不可结算——
 * 用户加购后商品下架是常态，直接删掉购物车会让用户困惑"我加的东西呢"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private static final int MAX_QUANTITY_PER_SKU = 99;

    private final CartItemMapper cartItemMapper;
    private final SkuMapper skuMapper;

    public CartItemVO add(Long userId, Long skuId, int quantity) {
        if (quantity <= 0 || quantity > MAX_QUANTITY_PER_SKU) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "加购数量必须在 1~99 之间");
        }
        Sku sku = skuMapper.selectById(skuId);
        if (sku == null || !Integer.valueOf(1).equals(sku.getStatus())) {
            throw new BusinessException(ResultCode.SKU_NOT_FOUND);
        }

        cartItemMapper.upsert(IdWorker.getId(), userId, skuId, quantity);
        log.debug("加购成功 userId={} skuId={} qty={}", userId, skuId, quantity);
        return toVO(requireItem(userId, skuId));
    }

    /** 修改数量；数量为 0 等价于删除。 */
    public CartItemVO updateQuantity(Long userId, Long skuId, int quantity) {
        if (quantity <= 0) {
            remove(userId, skuId);
            return null;
        }
        int capped = Math.min(quantity, MAX_QUANTITY_PER_SKU);
        int rows = cartItemMapper.update(null, Wrappers.<CartItem>lambdaUpdate()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getSkuId, skuId)
                .set(CartItem::getQuantity, capped));
        if (rows == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "购物车中没有该商品");
        }
        return toVO(requireItem(userId, skuId));
    }

    public void remove(Long userId, Long skuId) {
        cartItemMapper.delete(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getSkuId, skuId));
    }

    public void clear(Long userId) {
        cartItemMapper.delete(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId));
    }

    public List<CartItemVO> list(Long userId) {
        List<CartItem> items = cartItemMapper.selectList(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId)
                .orderByDesc(CartItem::getUpdateTime));
        return items.stream().map(this::toVO).toList();
    }

    private CartItem requireItem(Long userId, Long skuId) {
        CartItem item = cartItemMapper.selectOne(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getSkuId, skuId));
        if (item == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "购物车中没有该商品");
        }
        return item;
    }

    private CartItemVO toVO(CartItem item) {
        Sku sku = skuMapper.selectById(item.getSkuId());
        BigDecimal price = sku == null ? BigDecimal.ZERO : sku.getPrice();
        int quantity = item.getQuantity() == null ? 0 : item.getQuantity();

        CartItemVO vo = new CartItemVO();
        vo.setSkuId(item.getSkuId());
        vo.setQuantity(quantity);
        vo.setChecked(item.getChecked());
        vo.setSubtotal(price.multiply(BigDecimal.valueOf(quantity)));
        if (sku != null) {
            vo.setProductId(sku.getProductId());
            vo.setSkuCode(sku.getSkuCode());
            vo.setSpecJson(sku.getSpecJson());
            vo.setPrice(price);
            vo.setAvailableStock(sku.getStock());
        }
        // 下架或库存不足时标记不可结算，而不是从购物车删除
        vo.setAvailable(sku != null
                && Integer.valueOf(1).equals(sku.getStatus())
                && sku.getStock() != null && sku.getStock() >= quantity);
        return vo;
    }
}
