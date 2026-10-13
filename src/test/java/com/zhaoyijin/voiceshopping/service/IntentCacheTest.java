package com.zhaoyijin.voiceshopping.service;

import com.zhaoyijin.voiceshopping.dto.Intent;
import com.zhaoyijin.voiceshopping.dto.IntentResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntentCacheTest {
    @Test
    @SuppressWarnings("unchecked")
    void reusesEquivalentUtteranceOnlyWithinTheSameSession() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        Map<String, String> stored = new HashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        doAnswer(call -> {
            stored.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));

        IntentCache cache = new IntentCache(redis, new ObjectMapper());
        IntentResult result = new IntentResult(Intent.PRODUCT_RECOMMENDATION,
                Map.of("category", "手机", "budget", 1000), 0.95);
        cache.put("session-a", "嗯，我想买手机，预算1000元", result);

        assertEquals(result, cache.get("session-a", "想买手机预算1000元"));
        assertNull(cache.get("session-b", "想买手机预算1000元"));
        assertNull(cache.get("session-a", "不想买手机预算1000元"));
        assertNull(cache.get("session-a", "想买手机预算2000元"));
        verify(values).set(anyString(), anyString(), eq(Duration.ofMinutes(5)));
    }
}
