package com.lxt.mall.product.event;

import org.springframework.context.ApplicationEvent;

/**
 * 商品数据变更事件。
 *
 * <p>用 Spring 的进程内事件而不是直接调用缓存删除，是为了让
 * 「业务写库」和「缓存失效」之间保持一层解耦：
 * ProductService 不需要知道有几个缓存层、也不需要知道谁在监听。
 * 后续要接入 binlog 订阅（Canal）时，只需要换一个监听器，
 * 业务代码一行都不用动。
 */
public class ProductChangedEvent extends ApplicationEvent {

    private final Long productId;

    public ProductChangedEvent(Object source, Long productId) {
        super(source);
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
