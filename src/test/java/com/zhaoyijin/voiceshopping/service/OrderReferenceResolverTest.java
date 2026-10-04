package com.zhaoyijin.voiceshopping.service;

import com.zhaoyijin.voiceshopping.entity.SessionStateEntity;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderReferenceResolverTest {
    final OrderReferenceResolver resolver = new OrderReferenceResolver();
    SessionStateEntity state() {
        SessionStateEntity state = new SessionStateEntity();
        state.setLastRecommendations(List.of(7L, 10L, 6L));
        return state;
    }
    @Test void requestToRecommendClothesDoesNotSelectFirstShoe() {
        assertEquals(Optional.empty(), resolver.resolve(state(), "你给我推荐一下衣服吧"));
        assertEquals(Optional.empty(), resolver.resolve(state(), "再买一双衣服，再买一套衣服"));
        assertEquals(Optional.empty(), resolver.resolve(state(), "预算1000元"));
    }
    @Test void explicitOrdinalStillSelectsDisplayedProduct() {
        assertEquals(Optional.of(10L), resolver.resolve(state(), "我要第二款"));
        assertEquals(Optional.of(7L), resolver.resolve(state(), "就第1款"));
    }
    @Test void longerOrdinalDoesNotSelectFirstProduct() {
        assertEquals(Optional.empty(), resolver.resolve(state(), "第10款"));
        assertEquals(Optional.empty(), resolver.resolve(state(), "第十一款"));
    }
}
