package com.zhaoyijin.voiceshopping.service;

import com.zhaoyijin.voiceshopping.compliance.ComplianceChecker;
import com.zhaoyijin.voiceshopping.dto.*;
import com.zhaoyijin.voiceshopping.entity.*;
import com.zhaoyijin.voiceshopping.event.VoiceEventPublisher;
import com.zhaoyijin.voiceshopping.memory.*;
import com.zhaoyijin.voiceshopping.repository.ProductRepository;
import com.zhaoyijin.voiceshopping.voice.TtsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.reactivex.Flowable;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrchestratorOrderStateTest {
    final IntentService intents = mock(IntentService.class);
    final SessionStateService states = mock(SessionStateService.class);
    final PendingOrderStore pending = mock(PendingOrderStore.class);
    final OrderService orders = mock(OrderService.class);
    final ParallelRecommendService recommendations = mock(ParallelRecommendService.class);
    final EmotionStreamingService streaming = mock(EmotionStreamingService.class);
    final AtomicReference<SessionStateEntity> saved = new AtomicReference<>();

    OrchestratorService service() {
        ComplianceChecker compliance = mock(ComplianceChecker.class);
        when(compliance.ensureCompliant(anyString(), anyLong(), any())).thenAnswer(call -> call.getArgument(2));
        doAnswer(call -> { saved.set(call.getArgument(0)); return null; }).when(states).save(any());
        TtsService tts = mock(TtsService.class);
        when(tts.synthesize(any())).thenReturn(Flowable.empty());
        return new OrchestratorService(intents, null, recommendations, mock(EmotionService.class), null,
                states, mock(SessionService.class), mock(ShortTermMemory.class), mock(VoiceEventPublisher.class),
                compliance, new SimpleMeterRegistry(), mock(ProductRepository.class), new TurnSummarizer(),
                pending, new OrderReferenceResolver(), orders, streaming, tts, mock(CachedTtsPhrases.class));
    }
    void awaitingConfirmation() {
        SessionStateEntity state = new SessionStateEntity();
        state.setSessionId("purchase"); state.setPhase("ORDER_CONFIRM");
        state.setSlots(new HashMap<>(Map.of("category", "跑鞋", "budget", 1000)));
        state.setLastRecommendations(List.of(7L, 10L, 6L));
        when(states.load("purchase")).thenReturn(state);
        when(pending.get("purchase")).thenReturn(new PendingOrderStore.PendingOrder(
                "purchase", 1L, 1L, 7L, "鞋7", "sku7", 1, BigDecimal.valueOf(699), BigDecimal.valueOf(699)));
        when(orders.confirm("purchase")).thenReturn(completed());
        when(intents.classify(anyString(), anyString())).thenReturn(new IntentResult(Intent.ORDER_CONFIRM, Map.of(), .99));
    }
    static OrderEntity completed() {
        OrderEntity order = new OrderEntity(); order.setOrderNo("123456abcdef");
        order.setStatus("PAID"); order.setTotalAmount(BigDecimal.valueOf(699));
        return order;
    }
    @Test void successfulConfirmationPersistsEndedPhase() {
        awaitingConfirmation();
        EmotionResult reply = service().handle("purchase", 1L, "确认下单");
        assertTrue(reply.speechText().contains("下单成功"));
        assertNotNull(saved.get(), "Purchase phase must be saved before returning");
        assertEquals("ENDED", saved.get().getPhase());
    }
    @Test void newClothingRequestEscapesOldConfirmation() {
        awaitingConfirmation();
        when(intents.classify(anyString(), anyString())).thenReturn(
                new IntentResult(Intent.PRODUCT_RECOMMENDATION, Map.of("category", "衣服"), .99));
        when(recommendations.recommend(anyString(), anyLong(), anyString(), anyMap()))
                .thenReturn(new RecommendResult(List.of(), "empty"));
        List<StreamChunk> response = service().streamHandle("purchase", 1L, "推荐一下衣服")
                .collectList().block();
        assertTrue(response.stream().anyMatch(c -> c.text() != null && c.text().contains("衣服") && c.text().contains("没有找到")));
        assertEquals("衣服", saved.get().getSlots().get("category"));
        assertEquals("CLARIFY", saved.get().getPhase());
        verify(orders, never()).confirm(anyString());
        verify(orders, never()).preview(anyString(), anyLong(), anyLong(), anyInt());
    }
    @Test void purchaseStatusQuestionDoesNotStartAnotherOrder() {
        awaitingConfirmation(); when(pending.get("purchase")).thenReturn(null);
        when(orders.latestForSession("purchase", 1L)).thenReturn(Optional.of(completed()));
        EmotionResult reply = service().handle("purchase", 1L, "我刚才不是已经买过了吗？");
        assertTrue(reply.speechText().contains("订单已经生成"));
        assertTrue(reply.speechText().contains("699"));
        assertEquals("ENDED", saved.get().getPhase());
        verify(orders).latestForSession("purchase", 1L);
        verify(orders, never()).confirm(anyString());
        verify(orders, never()).preview(anyString(), anyLong(), anyLong(), anyInt());
    }
    @Test void negativeConfirmationDoesNotBuy() {
        awaitingConfirmation();
        EmotionResult reply = service().handle("purchase", 1L, "不要确认下单");
        assertTrue(reply.speechText().contains("没有为你下单"));
        verify(orders, never()).confirm(anyString());
        assertEquals("RECOMMEND", saved.get().getPhase());
    }
    @Test void newCategoryIsShoppingEvenWhenClassifiedAsPurchase() {
        awaitingConfirmation();
        when(intents.classify(anyString(), anyString())).thenReturn(
                new IntentResult(Intent.ORDER_CONFIRM, Map.of("category", "衣服"), .99));
        when(recommendations.recommend(anyString(), anyLong(), anyString(), anyMap()))
                .thenReturn(new RecommendResult(List.of(), "empty"));
        List<StreamChunk> response = service().streamHandle("purchase", 1L,
                "我刚才不是已经买过了吗？我说再买一双衣服，再买一套衣服。")
                .collectList().block();
        assertTrue(response.stream().anyMatch(c -> c.text() != null && c.text().contains("衣服") && c.text().contains("没有找到")));
        assertEquals("衣服", saved.get().getSlots().get("category"));
        verify(orders, never()).confirm(anyString());
        verify(orders, never()).preview(anyString(), anyLong(), anyLong(), anyInt());
    }
    @Test void ambiguousRecognitionKeepsPendingOrderWithoutBuying() {
        awaitingConfirmation();
        service().handle("purchase", 1L, "有。");
        assertNotNull(saved.get());
        assertEquals("ORDER_CONFIRM", saved.get().getPhase());
        verify(orders, never()).confirm(anyString());
    }
    @Test void questionContainingAffirmationDoesNotConfirmPurchase() {
        awaitingConfirmation();
        EmotionResult reply = service().handle("purchase", 1L, "这是好的吗？");
        assertFalse(reply.speechText().contains("下单成功"));
        verify(orders, never()).confirm(anyString());
    }
    @Test void missingCategoryInModelOutputDoesNotReuseOldShoes() {
        awaitingConfirmation();
        Map<String, Object> missingSlots = new HashMap<>();
        missingSlots.put("category", null);
        when(intents.classify(anyString(), anyString())).thenReturn(
                new IntentResult(Intent.CLARIFY_NEEDED, missingSlots, .8));
        when(recommendations.recommend(anyString(), anyLong(), anyString(), anyMap()))
                .thenReturn(new RecommendResult(List.of(), "empty"));
        List<StreamChunk> response = service().streamHandle("purchase", 1L, "你给我推荐一下衣服吧。")
                .collectList().block();
        assertEquals("衣服", saved.get().getSlots().get("category"));
        assertTrue(response.stream().anyMatch(c -> c.text() != null && c.text().contains("衣服") && c.text().contains("没有找到")));
        verify(orders, never()).confirm(anyString());
        verify(orders, never()).preview(anyString(), anyLong(), anyLong(), anyInt());
    }
}
