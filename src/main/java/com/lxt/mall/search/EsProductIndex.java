package com.lxt.mall.search;

import com.lxt.mall.common.util.JsonUtils;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.entity.Product;
import com.lxt.mall.product.dto.ProductVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 商品索引（HTTP 直连，不引入官方客户端）。
 *
 * <h3>为什么不用 spring-data-elasticsearch</h3>
 * <ol>
 *   <li><b>启动期耦合</b>：自动装配会在容器启动时创建 repository 代理，
 *       ES 不可用时会拖慢启动甚至报错。而搜索在本项目里是<b>可选能力</b>，
 *       它挂了不能让下单也跟着挂；</li>
 *   <li><b>版本矩阵</b>：Spring Data ES 与 ES 服务端版本有严格对应关系，
 *       为一个"锦上添花"的功能引入整条依赖链，性价比不高；</li>
 *   <li><b>用到的 API 很少</b>：建索引、写文档、删文档、查询四个动作，
 *       用 {@link RestClient} 直连 REST API 反而更透明、更好排查。</li>
 * </ol>
 *
 * <p>代价是要自己处理 JSON 与错误码——但所有方法都返回 boolean，
 * <b>失败一律降级</b>，不会把异常抛给业务线程。
 */
@Slf4j
@Component
public class EsProductIndex {

    private final RestClient client;
    private final String index;

    public EsProductIndex(MallProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(3).toMillis());
        this.client = RestClient.builder()
                .baseUrl(properties.getSearch().getEndpoint())
                .requestFactory(factory)
                .build();
        this.index = properties.getSearch().getIndex();
    }

    /** 索引是否可写：建索引 + 探测连通性。 */
    public boolean ensureIndex() {
        try {
            // 已存在会返回 400 resource_already_exists_exception，视为成功
            client.put()
                    .uri("/" + index)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapping())
                    .retrieve()
                    .toBodilessEntity();
            log.info("ES 索引已创建 index={}", index);
            return true;
        } catch (Exception e) {
            return ping();
        }
    }

    public boolean ping() {
        try {
            client.head().uri("/").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.debug("ES 不可达: {}", e.getMessage());
            return false;
        }
    }

    public void index(Product product) {
        try {
            client.put()
                    .uri("/" + index + "/_doc/" + product.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toDocument(product))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // 索引失败不影响主流程：搜索会降级到数据库
            log.warn("ES 写入失败 productId={} cause={}", product.getId(), e.getMessage());
        }
    }

    public void delete(Long productId) {
        try {
            client.delete().uri("/" + index + "/_doc/" + productId)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.debug("ES 删除失败 productId={} cause={}", productId, e.getMessage());
        }
    }

    /**
     * 搜索。命中 title / subTitle 两个字段，title 权重更高（^2）。
     *
     * <p>返回 null 表示"ES 这一路不可用"，由调用方决定降级——
     * 而不是在这里返回空列表：空列表是<b>合法的搜索结果</b>，
     * 与"ES 挂了"是两件完全不同的事，混在一起会让故障变得不可观测。
     */
    @SuppressWarnings("unchecked")
    public SearchResult search(String keyword, int page, int size) {
        try {
            Map<String, Object> body = Map.of(
                    "from", Math.max(0, (page - 1) * size),
                    "size", size,
                    "query", Map.of("bool", Map.of(
                            "must", Map.of("multi_match", Map.of(
                                    "query", keyword,
                                    "fields", List.of("title^2", "subTitle"),
                                    // operator=and：所有分词都必须命中。
                                    // 默认是 or，而标准分词器会把中文按字切分，
                                    // 于是"不存在的商品"里的"商""品"都能命中，
                                    // 用户搜一个不存在的词却得到一堆无关结果。
                                    // 对商品搜索来说，召回宁可少一点，也不能全是噪声。
                                    "operator", "and")),
                            "filter", Map.of("term", Map.of("status", 1)))),
                    "sort", List.of(Map.of("_score", "desc")));

            Map<String, Object> response = client.post()
                    .uri("/" + index + "/_search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return null;
            }
            Map<String, Object> hits = (Map<String, Object>) response.get("hits");
            if (hits == null) {
                return null;
            }
            long total = 0;
            Object totalNode = hits.get("total");
            if (totalNode instanceof Map<?, ?> totalMap && totalMap.get("value") instanceof Number n) {
                total = n.longValue();
            }
            List<ProductVO> items = new ArrayList<>();
            for (Object hitObj : (List<Object>) hits.getOrDefault("hits", List.of())) {
                Map<String, Object> hit = (Map<String, Object>) hitObj;
                Map<String, Object> source = (Map<String, Object>) hit.get("_source");
                if (source != null) {
                    items.add(fromDocument(source));
                }
            }
            return new SearchResult(total, items);
        } catch (Exception e) {
            log.warn("ES 查询失败，将降级到数据库：{}", e.getMessage());
            return null;
        }
    }

    // ========================================================================

    private Map<String, Object> mapping() {
        return Map.of(
                "mappings", Map.of("properties", Map.of(
                        "title", Map.of("type", "text"),
                        "subTitle", Map.of("type", "text"),
                        "price", Map.of("type", "double"),
                        "stock", Map.of("type", "integer"),
                        "status", Map.of("type", "integer"),
                        "coverImg", Map.of("type", "keyword"))));
    }

    private Map<String, Object> toDocument(Product p) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", p.getId());
        doc.put("title", p.getTitle());
        doc.put("subTitle", p.getSubTitle());
        doc.put("price", p.getPrice());
        doc.put("stock", p.getStock());
        doc.put("status", p.getStatus());
        doc.put("coverImg", p.getCoverImg());
        return doc;
    }

    private ProductVO fromDocument(Map<String, Object> source) {
        return new ProductVO(
                source.get("id") == null ? null : Long.valueOf(source.get("id").toString()),
                (String) source.get("title"),
                (String) source.get("subTitle"),
                (String) source.get("coverImg"),
                source.get("price") == null ? null : new java.math.BigDecimal(source.get("price").toString()),
                source.get("stock") == null ? null : Integer.valueOf(source.get("stock").toString()),
                source.get("status") == null ? null : Integer.valueOf(source.get("status").toString()));
    }

    /** 搜索结果：total 用于分页，items 是当前页数据。 */
    public record SearchResult(long total, List<ProductVO> items) {
    }
}
