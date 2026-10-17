package com.lxt.mall.product;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lxt.mall.cache.ProductBloomFilter;
import com.lxt.mall.cache.ProductCacheService;
import com.lxt.mall.common.api.PageResult;
import com.lxt.mall.common.api.ResultCode;
import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.domain.entity.Product;
import com.lxt.mall.domain.mapper.ProductMapper;
import com.lxt.mall.product.dto.ProductDetailVO;
import com.lxt.mall.product.dto.ProductSaveRequest;
import com.lxt.mall.product.dto.ProductVO;
import com.lxt.mall.product.event.ProductChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商品服务。
 *
 * <p>读写路径刻意是不对称的：
 * <ul>
 *   <li><b>读</b>走多级缓存（{@link ProductCacheService}），承压能力是数据库的几十倍</li>
 *   <li><b>写</b>直接落库，然后<b>通过事件</b>通知缓存失效</li>
 * </ul>
 * 这个不对称就是 Cache Aside 的本质：缓存只是数据库的一份派生数据，
 * 一致性由"写库后删缓存"来维护，而不是让缓存参与写事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;
    private final ProductCacheService cacheService;
    private final ProductBloomFilter bloomFilter;
    private final ApplicationEventPublisher eventPublisher;

    public PageResult<ProductVO> list(long pageNum, long pageSize) {
        Page<Product> page = new Page<>(pageNum, pageSize);
        IPage<Product> result = productMapper.selectPage(page, Wrappers.<Product>lambdaQuery()
                .eq(Product::getStatus, 1)
                .orderByDesc(Product::getId));

        List<ProductVO> records = result.getRecords().stream()
                .map(p -> new ProductVO(p.getId(), p.getTitle(), p.getSubTitle(),
                        p.getCoverImg(), p.getPrice(), p.getStock(), p.getStatus()))
                .toList();
        return PageResult.of(result.getTotal(), pageNum, pageSize, records);
    }

    public ProductDetailVO getDetail(Long productId) {
        return cacheService.getDetail(productId);
    }

    /**
     * 新增商品：先落库，再发事件让缓存失效。
     *
     * <p>新增场景其实没有"旧缓存"需要删，但缓存里可能存在
     * 【空值占位】（有人提前查过这个还不存在的 ID），
     * 不清掉的话新商品会在一段时间内查不到。这就是空值缓存引入的副作用，
     * 必须在写路径上一并处理。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(ProductSaveRequest request) {
        Product product = new Product();
        applyRequest(product, request);
        product.setVersion(0);
        productMapper.insert(product);

        bloomFilter.add(product.getId());
        eventPublisher.publishEvent(new ProductChangedEvent(this, product.getId()));
        return product.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long productId, ProductSaveRequest request) {
        Product exists = productMapper.selectById(productId);
        if (exists == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }
        Product product = new Product();
        product.setId(productId);
        applyRequest(product, request);
        productMapper.updateById(product);

        // 事件在事务提交后才会被 ProductCacheInvalidator 消费（AFTER_COMMIT）
        eventPublisher.publishEvent(new ProductChangedEvent(this, productId));
    }

    private void applyRequest(Product product, ProductSaveRequest request) {
        product.setTitle(request.getTitle());
        product.setSubTitle(request.getSubTitle());
        product.setCoverImg(request.getCoverImg());
        product.setPrice(request.getPrice());
        product.setStock(request.getStock());
        product.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        product.setDetail(request.getDetail());
    }
}
