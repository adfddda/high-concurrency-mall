package com.lxt.mall.product;

import com.lxt.mall.cache.ProductCacheService;
import com.lxt.mall.common.api.PageResult;
import com.lxt.mall.common.api.Result;
import com.lxt.mall.common.enums.RateLimitDimension;
import com.lxt.mall.product.dto.ProductDetailVO;
import com.lxt.mall.product.dto.ProductSaveRequest;
import com.lxt.mall.product.dto.ProductVO;
import com.lxt.mall.ratelimit.RateLimit;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ProductCacheService cacheService;

    @GetMapping("/list")
    public Result<PageResult<ProductVO>> list(@RequestParam(defaultValue = "1") long pageNum,
                                              @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(productService.list(pageNum, pageSize));
    }

    /**
     * 商品详情。
     *
     * <p>限流维度只开 IP 和 API：这是公开读接口，游客也能访问，
     * 没有 userId 可依据。而且它本身走多级缓存，抗压能力最强，
     * 限流阈值可以设得比写接口宽松得多——限流阈值应该按
     * 「接口的真实成本」来定，而不是所有接口一刀切。
     */
    @GetMapping("/detail/{productId}")
    @RateLimit(rule = "product-detail", dimensions = {RateLimitDimension.IP, RateLimitDimension.API})
    public Result<ProductDetailVO> detail(@PathVariable Long productId) {
        return Result.ok(productService.getDetail(productId));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody ProductSaveRequest request) {
        return Result.ok(productService.create(request));
    }

    @PutMapping("/{productId}")
    public Result<Void> update(@PathVariable Long productId,
                               @Valid @RequestBody ProductSaveRequest request) {
        productService.update(productId, request);
        return Result.ok();
    }

    /** 手动清空本地缓存，便于本地验证"多实例下 L1 不一致"的现象。 */
    @PostMapping("/cache/local/flush")
    public Result<Void> flushLocalCache() {
        cacheService.invalidateAllLocal();
        return Result.ok();
    }
}
