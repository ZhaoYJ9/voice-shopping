package com.zhaoyijin.voiceshopping.service;

import com.zhaoyijin.voiceshopping.dto.RecommendedItem;
import com.zhaoyijin.voiceshopping.dto.SessionScope;
import com.zhaoyijin.voiceshopping.entity.ProductEntity;
import com.zhaoyijin.voiceshopping.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecommendCandidatesServiceTest {
    private final ProductVectorService vector = mock(ProductVectorService.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final RecommendCandidatesService service =
            new RecommendCandidatesService(vector, products, new ScopeFilterBuilder());

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void excludesUnavailableDetailsAndKeepsRemainingVectorOrder(Integer stock) {
        List<Long> rankedIds = List.of(30L, 10L, 20L, 40L);
        when(vector.search(anyString(), anyString(), anyList(), eq(20))).thenReturn(rankedIds);
        // Details arrive in database order; 10 sold out since retrieval, 40 was removed.
        when(products.findByIdInWithScope(rankedIds, List.of(7L))).thenReturn(List.of(
                product(10L, stock, "ON_SALE"), product(20L, 1, "ON_SALE"), product(30L, 2, "ON_SALE")));

        var result = service.fetchCandidates("跑鞋", Map.of("category", "跑鞋", "budget", 100),
                new SessionScope(1L, List.of(7L), null), 20);

        assertEquals(List.of(30L, 20L), result.stream().map(RecommendedItem::productId).toList());
        assertEquals(new BigDecimal("80"), result.get(0).price());
        verify(products).findByIdInWithScope(rankedIds, List.of(7L));
    }

    @Test void excludesProductTakenOffSaleAfterVectorSearch() {
        when(vector.search(anyString(), anyString(), anyList(), eq(20))).thenReturn(List.of(10L, 20L));
        when(products.findByIdInWithScope(List.of(10L, 20L), null)).thenReturn(List.of(
                product(10L, 10, "OFF_SALE"), product(20L, 1, "ON_SALE")));

        var result = service.fetchCandidates("跑鞋", Map.of(), new SessionScope(1L, null, null), 20);

        assertEquals(List.of(20L), result.stream().map(RecommendedItem::productId).toList());
    }

    @Test void returnsEmptyWhenEveryCandidateHasSoldOut() {
        when(vector.search(anyString(), anyString(), anyList(), eq(20))).thenReturn(List.of(10L));
        when(products.findByIdInWithScope(List.of(10L), null))
                .thenReturn(List.of(product(10L, 0, "ON_SALE")));

        assertTrue(service.fetchCandidates("跑鞋", Map.of(), new SessionScope(1L, null, null), 20).isEmpty());
    }

    private ProductEntity product(Long id, Integer stock, String status) {
        ProductEntity p = new ProductEntity();
        p.setId(id); p.setMerchantId(7L); p.setSkuCode("test-" + id);
        p.setName("测试跑鞋" + id); p.setCategoryL1("运动"); p.setCategoryL2("跑鞋");
        p.setStock(stock); p.setStatus(status); p.setPrice(new BigDecimal("80"));
        p.setAttributes(Map.of());
        return p;
    }
}
