package com.lxt.mall.trade;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lxt.mall.common.api.ResultCode;
import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.domain.entity.Coupon;
import com.lxt.mall.domain.entity.CouponTemplate;
import com.lxt.mall.domain.mapper.CouponMapper;
import com.lxt.mall.domain.mapper.CouponTemplateMapper;
import com.lxt.mall.trade.dto.CouponVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 优惠券。
 *
 * <h3>发券和扣库存是同一个问题</h3>
 * "限量 1000 张券" 与 "限量 200 件库存" 在并发上是同一类问题：
 * 判断余量 → 变更数量，必须原子。所以这里复用了订单链路的思路：
 * <pre>
 *   UPDATE t_coupon_template SET issued_count = issued_count + 1
 *    WHERE id = ? AND issued_count &lt; total_count
 * </pre>
 * 影响行数为 0 就是领完了。
 *
 * <h3>为什么核销也要条件更新</h3>
 * 用户可能在两个设备上同时下单，两次都带上同一张券。
 * {@code WHERE status = 0 AND user_id = ?} 让只有一个事务能改到行，
 * 另一个拿到 0 行直接失败——<b>券不会在两笔订单里同时生效</b>。
 *
 * <h3>退款还券</h3>
 * 券是用户资产，订单退款后应当退回。用 {@code order_no} 反查并置回未使用，
 * 同样是一条带条件的 UPDATE，天然幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponTemplateMapper templateMapper;
    private final CouponMapper couponMapper;
    private final Clock clock;

    /**
     * 领券（幂等）。
     *
     * <p>先查一次是为了给"已领取"一个明确的提示；真正的并发正确性
     * 由 {@code uk_template_user} 唯一键保证——两个请求同时查到"没领过"，
     * 一起走到 INSERT，其中一个必然撞唯一键。
     */
    @Transactional(rollbackFor = Exception.class)
    public CouponVO claim(Long templateId, Long userId) {
        CouponTemplate template = templateMapper.selectById(templateId);
        if (template == null || !Integer.valueOf(1).equals(template.getStatus())) {
            throw new BusinessException(ResultCode.COUPON_NOT_FOUND);
        }

        Coupon existing = findByTemplateAndUser(templateId, userId);
        if (existing != null) {
            return CouponVO.from(existing);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (templateMapper.claimOne(templateId, now) == 0) {
            throw new BusinessException(ResultCode.COUPON_SOLD_OUT);
        }

        Coupon coupon = new Coupon();
        coupon.setId(IdWorker.getId());
        coupon.setCouponNo("CP" + IdWorker.getIdStr());
        coupon.setTemplateId(templateId);
        coupon.setUserId(userId);
        coupon.setStatus(0);
        coupon.setExpireTime(template.getEndTime());
        try {
            couponMapper.insert(coupon);
        } catch (DuplicateKeyException e) {
            // 并发领取：另一个请求先插入了。回滚名额占用，返回已存在的那张。
            throw new BusinessException(ResultCode.COUPON_ALREADY_CLAIMED);
        }
        log.info("领券成功 userId={} templateId={} couponNo={}", userId, templateId, coupon.getCouponNo());
        return CouponVO.from(coupon);
    }

    public List<CouponVO> listMine(Long userId, Integer status) {
        List<Coupon> coupons = couponMapper.selectList(Wrappers.<Coupon>lambdaQuery()
                .eq(Coupon::getUserId, userId)
                .eq(status != null, Coupon::getStatus, status)
                .orderByAsc(Coupon::getExpireTime));
        return coupons.stream().map(this::withTemplate).toList();
    }

    /**
     * 下单核销，返回实际优惠金额。
     *
     * <p>调用方（{@link TradeService}）在同一个事务里先扣库存再核销券：
     * 任何一步失败都整体回滚，不会出现"券扣了但订单没建"。
     */
    public BigDecimal useForOrder(Long userId, Long couponId, BigDecimal totalAmount, String orderNo) {
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null || !userId.equals(coupon.getUserId())) {
            // 不区分"不存在"和"不是你的"，避免探测他人券
            throw new BusinessException(ResultCode.COUPON_NOT_FOUND);
        }
        CouponTemplate template = templateMapper.selectById(coupon.getTemplateId());
        if (template == null) {
            throw new BusinessException(ResultCode.COUPON_NOT_FOUND);
        }
        if (totalAmount.compareTo(template.getThresholdAmount()) < 0) {
            throw new BusinessException(ResultCode.COUPON_THRESHOLD_NOT_MET);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (couponMapper.markUsed(couponId, userId, orderNo, now, now) == 0) {
            throw new BusinessException(ResultCode.COUPON_UNAVAILABLE);
        }
        // 优惠金额不能超过订单金额，否则会出现负数应付
        return template.getDiscountAmount().min(totalAmount);
    }

    /** 订单取消/退款时还券（幂等）。 */
    public void restoreByOrder(String orderNo) {
        int rows = couponMapper.restoreByOrder(orderNo);
        if (rows > 0) {
            log.info("订单退还优惠券 orderNo={} 数量={}", orderNo, rows);
        }
    }

    public CouponTemplate getTemplate(Long templateId) {
        return templateMapper.selectById(templateId);
    }

    private Coupon findByTemplateAndUser(Long templateId, Long userId) {
        return couponMapper.selectOne(Wrappers.<Coupon>lambdaQuery()
                .eq(Coupon::getTemplateId, templateId)
                .eq(Coupon::getUserId, userId));
    }

    private CouponVO withTemplate(Coupon coupon) {
        CouponVO vo = CouponVO.from(coupon);
        CouponTemplate template = templateMapper.selectById(coupon.getTemplateId());
        if (template != null) {
            vo.setThresholdAmount(template.getThresholdAmount());
            vo.setDiscountAmount(template.getDiscountAmount());
        }
        return vo;
    }
}
