package com.zhaoyijin.voiceshopping.service;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PendingOrderStore {

    private static final DefaultRedisScript<Long> REMOVE_MATCHING_ORDER = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current and cjson.decode(current).orderNo == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public record PendingOrder(
            String sessionId,
            Long userId,
            Long merchantId,
            Long productId,
            String productName,
            String skuCode,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal totalAmount,
            String orderNo
    ) {}

    private String key(String sessionId) {
        return "vs:pending_order:" + sessionId;
    }

    @SneakyThrows
    public void put(PendingOrder o) {
        redis.opsForValue().set(key(o.sessionId()),
                mapper.writeValueAsString(o), Duration.ofMinutes(10));
    }

    @SneakyThrows
    public PendingOrder get(String sessionId) {
        String raw = redis.opsForValue().get(key(sessionId));
        return raw == null ? null : mapper.readValue(raw, PendingOrder.class);
    }

    public void remove(String sessionId) {
        redis.delete(key(sessionId));
    }

    /** A completed purchase must not delete a newer preview in the same session. */
    public void removeIfMatches(String sessionId, String orderNo) {
        redis.execute(REMOVE_MATCHING_ORDER, List.of(key(sessionId)), orderNo);
    }
}
