package com.lxt.mall.search;

import com.lxt.mall.common.api.PageResult;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.product.dto.ProductVO;
import com.lxt.mall.support.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品搜索测试。
 *
 * <p>两条路径都要测：
 * <ul>
 *   <li><b>降级路径</b>：ES 未启用时必须仍能用数据库搜到结果——
 *       这是"搜索挂了不能拖垮商城"这条设计的可验证证据；</li>
 *   <li><b>ES 路径</b>：CI 里起真实 ES 后验证相关性搜索。</li>
 * </ul>
 */
class ProductSearchTest extends BaseIntegrationTest {

    @Autowired
    private ProductSearchService searchService;

    @Autowired
    private MallProperties properties;

    @Test
    @DisplayName("搜索降级：无论 ES 是否启用，关键词都能搜到商品")
    void searchFindsProductWithoutEs() {
        // 种子数据里有 "Xiaomi 15 Ultra 16GB+512GB"（id=1001）
        PageResult<ProductVO> result = searchService.search("Xiaomi", 1, 10);

        assertThat(result.getTotal()).isGreaterThanOrEqualTo(1);
        assertThat(result.getRecords())
                .anyMatch(p -> p.getTitle() != null && p.getTitle().contains("Xiaomi"));
    }

    @Test
    @DisplayName("空关键词退化为浏览列表，不返回空结果")
    void emptyKeywordFallsBackToList() {
        PageResult<ProductVO> result = searchService.search("   ", 1, 10);
        assertThat(result.getRecords()).isNotEmpty();
    }

    @Test
    @DisplayName("搜不到时返回空列表，而不是报错")
    void noMatchReturnsEmpty() {
        PageResult<ProductVO> result = searchService.search("不存在的商品关键词XYZ", 1, 10);
        assertThat(result.getRecords()).isEmpty();
    }

    /**
     * ES 链路测试。
     *
     * <p>当 {@code mall.search.enabled=false}（本地开发默认值）时自动跳过；
     * CI 里通过环境变量打开，真正验证"写入索引 → 按相关性命中"。
     */
    @Test
    @DisplayName("ES 已启用时：索引命中并按关键词返回")
    void esSearchReturnsHits() {
        org.junit.jupiter.api.Assumptions.assumeTrue(properties.getSearch().isEnabled(),
                "ES 未启用，跳过 ES 链路测试");

        PageResult<ProductVO> result = searchService.search("MacBook", 1, 10);
        assertThat(result.getTotal()).isGreaterThanOrEqualTo(1);
        assertThat(result.getRecords())
                .anyMatch(p -> p.getTitle() != null && p.getTitle().contains("MacBook"));
    }
}
