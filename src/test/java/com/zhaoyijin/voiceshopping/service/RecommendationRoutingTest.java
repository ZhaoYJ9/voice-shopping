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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecommendationRoutingTest {
    final IntentService intents = mock(IntentService.class);
    final ParallelRecommendService recommendations = mock(ParallelRecommendService.class);
    final EmotionStreamingService streaming = mock(EmotionStreamingService.class);
    final EmotionService emotion = mock(EmotionService.class);
    final ClarifyService clarify = mock(ClarifyService.class);
    final SessionStateService states = mock(SessionStateService.class);
    final PendingOrderStore pending = mock(PendingOrderStore.class);
    final OrderService orders = mock(OrderService.class);
    final ShortTermMemory memory = mock(ShortTermMemory.class);
    final SessionStateEntity state = new SessionStateEntity();
    final ProductRepository products = mock(ProductRepository.class);
    final OrchestratorService service;

    RecommendationRoutingTest() {
        state.setSessionId("routing"); state.setPhase("RECOMMEND");
        state.setSlots(new HashMap<>(Map.of("category", "跑鞋", "budget", 500)));
        state.setLastRecommendations(List.of());
        when(states.load("routing")).thenReturn(state);
        when(clarify.decide(anyString(), anyString(), anyMap())).thenReturn(ClarifyResult.ready());
        when(recommendations.recommend(anyString(), anyLong(), anyString(), anyMap()))
                .thenReturn(new RecommendResult(List.of(), "empty"));
        when(streaming.streamWrap(anyString(), anyString(), any())).thenReturn(Flux.just("马上给你推荐几款。"));
        when(emotion.wrap(anyString(), anyString(), anyString(), any()))
                .thenReturn(new EmotionResult("马上给你推荐几款。", List.of()));
        when(intents.classify(anyString(), anyString())).thenAnswer(call ->
                new IntentService(null, null, null, null, null).classify(call.getArgument(0), call.getArgument(1)));
        ComplianceChecker compliance = mock(ComplianceChecker.class);
        when(compliance.ensureCompliant(anyString(), anyLong(), any())).thenAnswer(call -> call.getArgument(2));
        TtsService tts = mock(TtsService.class);
        when(tts.synthesize(any())).thenReturn(Flowable.empty());
        service = new OrchestratorService(intents, clarify, recommendations, emotion, null,
                states, mock(SessionService.class), memory, mock(VoiceEventPublisher.class), compliance,
                new SimpleMeterRegistry(), products, new TurnSummarizer(), pending,
                new OrderReferenceResolver(), orders, streaming, tts, mock(CachedTtsPhrases.class));
    }

    @Test void emptyStreamReportsNoMatchesAndAsksForAnExplicitBudget() {
        doReturn(new IntentResult(Intent.PRODUCT_RECOMMENDATION, Map.of("category", "跑鞋", "budget", 500), .99))
                .when(intents).classify("routing", "买跑鞋预算500");
        String text = streamText("买跑鞋预算500");
        assertTrue(text.contains("没有找到"));
        assertTrue(text.contains("500"));
        assertTrue(text.contains("预算") && text.contains("多少"));
        assertEquals("budget", state.getPendingAsk());
        assertEquals("CLARIFY", state.getPhase());
        verifyNoInteractions(streaming);
        verify(memory).append(eq("routing"), any());
    }

    @Test void synchronousEmptySearchDoesNotPromiseImaginaryRecommendations() {
        doReturn(new IntentResult(Intent.PRODUCT_RECOMMENDATION, Map.of("budget", 500), .99))
                .when(intents).classify("routing", "买跑鞋预算500");
        String text = service.handle("routing", 1L, "买跑鞋预算500").speechText();
        assertTrue(text.contains("没有找到"));
        assertEquals("budget", state.getPendingAsk());
        verifyNoInteractions(emotion);
    }

    @Test void agreementToRaiseBudgetDoesNotEnterPurchaseOrInventABudget() {
        state.setPhase("CLARIFY"); state.setPendingAsk("budget");
        String text = streamText("可以。");
        assertTrue(text.contains("预算") && text.contains("多少"));
        assertEquals(500, state.getSlots().get("budget"));
        assertEquals("CLARIFY", state.getPhase());
        verifyNoInteractions(orders, recommendations);
    }

    @Test void agreementAfterARecommendationAsksWhatWasAgreedToWithoutBuying() {
        state.setLastRecommendations(List.of(6L, 7L));
        String text = service.handle("routing", 1L, "可以。").speechText();
        assertTrue(text.contains("哪一款"));
        assertEquals("CLARIFY_NEEDED", state.getCurrentIntent());
        verifyNoInteractions(orders);
    }

    @Test void agreementStillConfirmsAnActualPendingOrder() {
        state.setPhase("ORDER_CONFIRM");
        when(pending.get("routing")).thenReturn(new PendingOrderStore.PendingOrder(
                "routing", 1L, 1L, 6L, "Pegasus 39", "shoe6", 1, BigDecimal.valueOf(599), BigDecimal.valueOf(599)));
        OrderEntity order = new OrderEntity(); order.setOrderNo("abcdef123456");
        when(orders.confirm("routing")).thenReturn(order);
        assertTrue(service.handle("routing", 1L, "可以。").speechText().contains("下单成功"));
        assertEquals("ENDED", state.getPhase());
    }

    @Test void nextChoiceIsNotAnOrderConfirmation() {
        IntentResult result = new IntentService(null, null, null, null, null).classify("routing", "换一个。");
        assertEquals(Intent.PRODUCT_RECOMMENDATION, result.intent());
    }

    @Test void catalogQuestionDoesNotReuseTheOldShoeBudgetSearch() {
        when(recommendations.availableCategories("routing"))
                .thenReturn(List.of("口红", "手表", "耳机", "跑鞋"));
        doReturn(new IntentResult(Intent.CLARIFY_NEEDED, Map.of(), .8))
                .when(intents).classify("routing", "你这里都有什么？");
        String text = streamText("你这里都有什么？");
        assertTrue(text.contains("口红、手表、耳机、跑鞋"));
        assertFalse(text.contains("衣服"));
        assertFalse(text.contains("500"));
        assertTrue(state.getSlots().isEmpty());
        assertNull(state.getPendingAsk());
        verify(recommendations, never()).recommend(anyString(), anyLong(), anyString(), anyMap());
        verifyNoInteractions(orders);
    }

    @Test void explicitNewBudgetSearchesAgainAndKeepsTheShoeCategory() {
        state.setPhase("CLARIFY"); state.setPendingAsk("budget");
        doReturn(new IntentResult(Intent.CLARIFY_NEEDED, Map.of("budget", 800), .99))
                .when(intents).classify("routing", "那就800以内");
        RecommendedItem shoe = new RecommendedItem(6L, "Pegasus 39", BigDecimal.valueOf(599),
                "日常跑步", 1.0, Map.of());
        when(recommendations.recommend(eq("routing"), eq(1L), eq("那就800以内"),
                argThat(slots -> Objects.equals("跑鞋", slots.get("category"))
                        && Objects.equals(800, slots.get("budget")))))
                .thenReturn(new RecommendResult(List.of(shoe), "professional"));
        List<StreamChunk> chunks = service.streamHandle("routing", 1L, "那就800以内").collectList().block();
        assertEquals(List.of(shoe), chunks.get(0).products());
        assertEquals(800, state.getSlots().get("budget"));
        assertEquals("RECOMMEND", state.getPhase());
        assertNull(state.getPendingAsk());
        verifyNoInteractions(orders);
    }

    @Test void catalogOnlyReportsCategoriesWithStockInsideTheSessionScope() {
        ProductEntity shoe = catalogProduct(1L, "跑鞋", 2);
        ProductEntity foreignWatch = catalogProduct(2L, "手表", 3);
        ProductEntity emptyHeadphones = catalogProduct(1L, "耳机", 0);
        when(products.findByStatus("ON_SALE")).thenReturn(List.of(shoe, foreignWatch, emptyHeadphones));
        RecommendCandidatesService candidates = new RecommendCandidatesService(null, products, null);
        assertEquals(List.of("跑鞋"), candidates.availableCategories(new SessionScope(1L, List.of(1L), null)));
        assertEquals(List.of("手表", "跑鞋"), candidates.availableCategories(null));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitSevenHundredOverridesCheaperAndKeepsEligiblePreviousShoes(boolean stream) {
        state.setSlots(new HashMap<>(Map.of("category", "跑鞋", "budget", 1000)));
        state.setLastRecommendations(List.of(7L, 10L, 6L));
        List<RecommendedItem> affordable = affordableShoes();
        doReturn(new IntentResult(Intent.PRODUCT_COMPARE,
                Map.of("category", "跑鞋", "budget", 700, "priceDirection", "cheaper"), .99))
                .when(intents).classify("routing", "七百再推荐一下。");
        when(recommendations.recommend(eq("routing"), eq(1L), eq("七百再推荐一下。"),
                argThat(slots -> Objects.equals(700, slots.get("budget"))
                        && !slots.containsKey("excludeProductIds") && !slots.containsKey("priceMin"))))
                .thenReturn(new RecommendResult(affordable, "professional"));
        when(emotion.wrap(anyString(), anyString(), anyString(), any()))
                .thenAnswer(i -> new EmotionResult("预算内的跑鞋", ((RecommendResult) i.getArgument(3)).items()));

        assertEquals(affordable, replyItems(stream, "七百再推荐一下。"));
        assertEquals(700, state.getSlots().get("budget"));
        assertFalse(state.getSlots().containsKey("priceDirection"));
        assertEquals(List.of(6L, 7L), state.getLastRecommendations());
        verifyNoInteractions(products, orders);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void newBudgetRemovesLegacyComparisonFiltersButKeepsShoppingPreferences(boolean stream) {
        state.setPhase("CLARIFY"); state.setPendingAsk("budget");
        state.setSlots(new HashMap<>(Map.of("category", "跑鞋", "budget", 719, "budgetMin", 600,
                "priceDirection", "cheaper", "priceMin", 900, "excludeProductIds", List.of(7L, 10L, 6L),
                "brand", "Nike", "scenario", "水泥路", "gender", "male")));
        doReturn(new IntentResult(Intent.PRODUCT_RECOMMENDATION, Map.of("budget", 800), .99))
                .when(intents).classify("routing", "预算800元。");
        List<RecommendedItem> shoes = List.of(affordableShoes().get(0));
        when(recommendations.recommend(eq("routing"), eq(1L), eq("预算800元。"),
                argThat(slots -> Objects.equals(800, slots.get("budget"))
                        && !slots.containsKey("excludeProductIds") && !slots.containsKey("priceMin"))))
                .thenReturn(new RecommendResult(shoes, "professional"));
        when(emotion.wrap(anyString(), anyString(), anyString(), any()))
                .thenAnswer(i -> new EmotionResult("预算内的跑鞋", ((RecommendResult) i.getArgument(3)).items()));

        assertEquals(shoes, replyItems(stream, "预算800元。"));
        assertEquals(Map.of("category", "跑鞋", "budget", 800, "brand", "Nike",
                "scenario", "水泥路", "gender", "male"), state.getSlots());
        assertNull(state.getPendingAsk());
        assertEquals("RECOMMEND", state.getPhase());
    }

    @Test void eightHundredStillWorksWhenModelCallsItAComparison() {
        state.setSlots(new HashMap<>(Map.of("category", "跑鞋", "budget", 719,
                "priceDirection", "cheaper", "excludeProductIds", List.of(7L, 10L, 6L))));
        doReturn(new IntentResult(Intent.PRODUCT_COMPARE, Map.of("budget", 800), .99))
                .when(intents).classify("routing", "预算800元。");
        streamText("预算800元。");
        verify(recommendations).recommend("routing", 1L, "预算800元。", Map.of("category", "跑鞋", "budget", 800));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitFinancialNumeralReplacesOldBudgetEvenWhenModelMissesIt(boolean stream) {
        state.getSlots().put("budget", 800);
        String input = "我把预算调整到七佰再推荐一下。";
        doReturn(new IntentResult(Intent.CLARIFY_NEEDED, Map.of(), .8))
                .when(intents).classify("routing", input);
        if (stream) streamText(input);
        else service.handle("routing", 1L, input);
        verify(recommendations).recommend("routing", 1L, input, Map.of("category", "跑鞋", "budget", 700));
        assertEquals(700, state.getSlots().get("budget"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unfinishedBudgetAsksForAmountWithoutReusingOldBudget(boolean stream) {
        state.setSlots(new HashMap<>(Map.of("category", "跑鞋", "budget", 1000)));
        state.setLastRecommendations(List.of(7L, 10L, 6L));
        String text = stream ? streamText("我把预算调整到。")
                : service.handle("routing", 1L, "我把预算调整到。").speechText();
        assertTrue(text.contains("预算") && text.contains("多少"));
        assertEquals("budget", state.getPendingAsk());
        assertEquals("CLARIFY", state.getPhase());
        assertEquals("跑鞋", state.getSlots().get("category"));
        assertEquals(1000, state.getSlots().get("budget"));
        verifyNoInteractions(intents, recommendations, orders);
    }

    @Test void relativeCheaperStillUsesPreviousPricesWithoutReusingOldExclusions() {
        state.setLastRecommendations(List.of(7L, 10L, 6L));
        state.getSlots().put("excludeProductIds", List.of(99L));
        state.getSlots().put("priceMin", 1000);
        List<ProductEntity> previous = new ArrayList<>();
        for (int price : List.of(699, 899, 599)) {
            ProductEntity p = new ProductEntity(); p.setPrice(BigDecimal.valueOf(price)); previous.add(p);
        }
        when(products.findByIdIn(List.of(7L, 10L, 6L))).thenReturn(previous);
        doReturn(new IntentResult(Intent.PRODUCT_COMPARE, Map.of("priceDirection", "cheaper"), .99))
                .when(intents).classify("routing", "再便宜一点");
        streamText("再便宜一点");
        assertEquals(719, state.getSlots().get("budget"));
        assertEquals(List.of(7L, 10L, 6L), state.getSlots().get("excludeProductIds"));
        assertFalse(state.getSlots().containsKey("priceMin"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitRangeOverridesCheaperAndClearsOldComparisonFilters(boolean stream) {
        state.setSlots(new HashMap<>(Map.of("category", "跑鞋", "budget", 2000,
                "priceMin", 1800, "excludeProductIds", List.of(9L, 8L, 2L))));
        state.setLastRecommendations(List.of(9L, 8L, 2L));
        String input = "感觉有点贵了，我预算嗯1000到1500吧。";
        doReturn(new IntentResult(Intent.PRODUCT_COMPARE,
                Map.of("budget", 1500, "priceDirection", "cheaper"), .99))
                .when(intents).classify("routing", input);
        if (stream) streamText(input);
        else service.handle("routing", 1L, input);
        verify(recommendations).recommend("routing", 1L, input,
                Map.of("category", "跑鞋", "budget", 1500, "budgetMin", 1000));
        verify(intents, times(1)).classify("routing", input);
        verifyNoInteractions(products, emotion);
    }

    @Test void laterBrandChangeRetainsUserRange() {
        state.setSlots(new HashMap<>(Map.of("category", "跑鞋", "budget", 1500, "budgetMin", 1000)));
        doReturn(new IntentResult(Intent.PRODUCT_RECOMMENDATION, Map.of("brand", "Nike"), .99))
                .when(intents).classify("routing", "看看耐克的");
        streamText("看看耐克的");
        verify(recommendations).recommend("routing", 1L, "看看耐克的",
                Map.of("category", "跑鞋", "brand", "Nike", "budget", 1500, "budgetMin", 1000));
    }

    @Test void emptyRangeReportsBothBounds() {
        doReturn(new IntentResult(Intent.PRODUCT_RECOMMENDATION, Map.of(), .99))
                .when(intents).classify("routing", "预算1000到1500");
        assertTrue(streamText("预算1000到1500").contains("1000到1500元之间"));
    }

    @Test void explicitRangeStreamsFirstSentenceWithoutWaitingForTheFullReply() {
        state.setLastRecommendations(List.of(9L, 8L, 2L));
        String input = "感觉贵了，预算1000到1500";
        doReturn(new IntentResult(Intent.PRODUCT_COMPARE, Map.of("priceDirection", "cheaper"), .99))
                .when(intents).classify("routing", input);
        List<RecommendedItem> items = List.of(new RecommendedItem(5L, "Clifton 9",
                BigDecimal.valueOf(1180), "轻量缓震", 1.0, Map.of()));
        when(recommendations.recommend(anyString(), anyLong(), anyString(), anyMap()))
                .thenReturn(new RecommendResult(items, "professional"));
        Sinks.Many<String> tokens = Sinks.many().unicast().onBackpressureBuffer();
        when(streaming.streamWrap(eq("routing"), eq(input), any())).thenReturn(tokens.asFlux());
        List<StreamChunk> chunks = new ArrayList<>();
        boolean[] complete = {false};
        var subscription = service.streamHandle("routing", 1L, input)
                .subscribe(chunks::add, e -> fail(e), () -> complete[0] = true);
        try {
            assertEquals(items, chunks.get(0).products());
            tokens.tryEmitNext("预算1000到1500元。失配商品已排除。");
            assertTrue(chunks.stream().anyMatch(c -> c.text() != null && c.text().contains("预算1000")));
            assertFalse(complete[0], "First caption must arrive before all model output completes");
            verifyNoInteractions(emotion, products);
            verify(intents, times(1)).classify("routing", input);
            tokens.tryEmitComplete();
            assertTrue(complete[0]);
        } finally { subscription.dispose(); }
    }

    Object replyItems(boolean stream, String input) {
        if (!stream) return service.handle("routing", 1L, input).displayBlocks();
        return service.streamHandle("routing", 1L, input).collectList().block().stream()
                .filter(c -> c.type() == StreamChunk.Type.PRODUCTS).findFirst().orElseThrow().products();
    }

    List<RecommendedItem> affordableShoes() {
        return List.of(new RecommendedItem(6L, "Pegasus 39", BigDecimal.valueOf(599), "日常跑步", 1.0, Map.of()),
                new RecommendedItem(7L, "粉色跑鞋", BigDecimal.valueOf(699), "轻便", 1.0, Map.of()));
    }

    ProductEntity catalogProduct(Long merchant, String category, int stock) {
        ProductEntity p = new ProductEntity(); p.setMerchantId(merchant);
        p.setCategoryL2(category); p.setStock(stock); p.setStatus("ON_SALE");
        return p;
    }

    @Test void compliancePreservesOrdinalsButStillSoftensSuperlatives() throws Exception {
        ComplianceChecker checker = new ComplianceChecker();
        ReflectionTestUtils.setField(checker, "absolutePatterns", List.of("最好", "第一", "保证", "绝对"));
        checker.init();
        String text = checker.ensureCompliant("routing", 1L,
                new EmotionResult("第一款、第二款、第一个。这款最好，销量第一。", List.of())).speechText();
        assertTrue(text.contains("第一款") && text.contains("第一个"));
        assertFalse(text.contains("最好") || text.contains("销量第一"));
    }

    String streamText(String input) {
        List<StreamChunk> chunks = service.streamHandle("routing", 1L, input).collectList().block();
        return chunks.stream().map(StreamChunk::text).filter(Objects::nonNull).reduce("", String::concat);
    }
}
