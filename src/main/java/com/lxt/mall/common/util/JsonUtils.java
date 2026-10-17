package com.lxt.mall.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 轻量 JSON 工具。
 *
 * <p>用独立的 ObjectMapper 实例，而不是直接复用 Spring MVC 的那个：
 * MVC 的 ObjectMapper 可能会被 {@code @JsonView}、自定义序列化器、
 * {@code spring.jackson.*} 配置影响（例如 NON_NULL 会让 null 字段消失），
 * 而缓存/消息序列化要求"写入什么就能原样读回来"，
 * 两者诉求不同，共用会埋雷。
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // LocalDateTime 支持
            .registerModule(new JavaTimeModule())
            // 写时间用 ISO-8601 字符串而不是时间戳数组，方便人肉排查 Redis 里的值
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // 反序列化时遇到未知字段不要炸：消息体未来加字段时旧消费者仍能工作
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonUtils() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化失败: " + obj.getClass().getName(), e);
        }
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化失败: " + clazz.getName(), e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化失败", e);
        }
    }
}
