package com.lxt.mall.search;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lxt.mall.common.api.PageResult;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.entity.Product;
import com.lxt.mall.domain.mapper.ProductMapper;
import com.lxt.mall.product.dto.ProductVO;
import com.lxt.mall.product.event.ProductChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 商品搜索：ES 优先，数据库兜底。
 *
 * <h3>为什么"降级"在这里是必须的，而不是可选的</h3>
 * 搜索是<b>锦上添花</b>的能力：它挂了，用户还能按分类浏览、按 ID 看详情、正常下单。
 * 但如果在搜索路径上直接抛异常，一个次要组件的故障就会被放大成"商城主流程不可用"。
 *
 * <p>所以这里的策略是：
 * <pre>
 *   ES 正常  → 走 ES（相关性排序 + 分词）
 *   ES 超时/报错 → 记 WARN，走数据库 LIKE
 *   ES 未启用 → 直接走数据库
 * </pre>
 * 三种情况返回的都是"能用的结果"，区别只在相关性和性能。
 *
 * <h3>索引维护为什么挂在事务提交之后</h3>
 * 商品写入与索引更新必须<b>先库后索引</b>：如果先写索引再到数据库回滚，
 * 搜索里就会出现一个数据库里不存在的商品（用户点进去 404）。
 * 用 {@code AFTER_COMMIT} 保证"只有真正提交的商品才会被搜到"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final EsProductIndex esIndex;
    private final ProductMapper productMapper;
    private final MallProperties properties;

    public PageResult<ProductVO> search(String keyword, long pageNum, long pageSize) {
        if (!StringUtils.hasText(keyword)) {
            return searchByDb(null, pageNum, pageSize);
        }
        String kw = keyword.trim();

        if (properties.getSearch().isEnabled()) {
            EsProductIndex.SearchResult result =
                    esIndex.search(kw, (int) Math.max(1, pageNum), (int) Math.max(1, pageSize));
            if (result != null) {
                return PageResult.of(result.total(), pageNum, pageSize, result.items());
            }
            log.warn("ES 搜索不可用，降级到数据库 keyword={}", kw);
        }
        return searchByDb(kw, pageNum, pageSize);
    }

    private PageResult<ProductVO> searchByDb(String keyword, long pageNum, long pageSize) {
        Page<Product> page = new Page<>(pageNum, pageSize);
        IPage<Product> result = productMapper.selectPage(page, Wrappers.<Product>lambdaQuery()
                .eq(Product::getStatus, 1)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Product::getTitle, keyword)
                        .or()
                        .like(Product::getSubTitle, keyword))
                .orderByDesc(Product::getId));

        List<ProductVO> records = result.getRecords().stream()
                .map(p -> new ProductVO(p.getId(), p.getTitle(), p.getSubTitle(),
                        p.getCoverImg(), p.getPrice(), p.getStock(), p.getStatus()))
                .toList();
        return PageResult.of(result.getTotal(), pageNum, pageSize, records);
    }

    /**
     * 启动时全量重建索引。
     *
     * <p>为什么在 {@code ApplicationReadyEvent} 而不是 {@code @PostConstruct}：
     * 后者执行时数据库迁移/种子数据可能还没完成，会把空表"索引"成空索引。
     * 应用就绪意味着所有初始化都结束了。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reindexOnStartup() {
        if (!properties.getSearch().isEnabled() || !properties.getSearch().isReindexOnStartup()) {
            return;
        }
        if (!esIndex.ensureIndex()) {
            log.warn("ES 不可达，跳过索引重建（搜索将走数据库）");
            return;
        }
        List<Product> products = productMapper.selectList(
                Wrappers.<Product>lambdaQuery().eq(Product::getStatus, 1));
        products.forEach(esIndex::index);
        log.info("ES 索引重建完成，共 {} 条商品", products.size());
    }

    /** 商品变更后同步索引（事务提交之后）。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProductChanged(ProductChangedEvent event) {
        if (!properties.getSearch().isEnabled()) {
            return;
        }
        Product product = productMapper.selectById(event.getProductId());
        if (product == null || !Integer.valueOf(1).equals(product.getStatus())) {
            // 商品被删除或下架：索引里也必须消失，否则会搜到打不开的商品
            esIndex.delete(event.getProductId());
            return;
        }
        esIndex.index(product);
    }
}
