package com.zhaoyijin.voiceshopping.service;

import com.zhaoyijin.voiceshopping.compliance.ComplianceChecker;
import com.zhaoyijin.voiceshopping.dto.*;
import com.zhaoyijin.voiceshopping.entity.OrderEntity;
import com.zhaoyijin.voiceshopping.entity.ProductEntity;
import com.zhaoyijin.voiceshopping.entity.SessionStateEntity;
import com.zhaoyijin.voiceshopping.entity.StreamChunk;
import com.zhaoyijin.voiceshopping.event.VoiceEventPublisher;
import com.zhaoyijin.voiceshopping.memory.ShortTermMemory;
import com.zhaoyijin.voiceshopping.memory.TurnSummarizer;
import com.zhaoyijin.voiceshopping.repository.ProductRepository;
import com.zhaoyijin.voiceshopping.voice.SentenceAggregator;
import com.zhaoyijin.voiceshopping.voice.TtsService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.reactivex.Flowable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestratorService {

    private static final Pattern CATEGORY_MENTION =
            Pattern.compile("运动鞋|跑鞋|手表|耳机|口红|衣服|服装|T恤|鞋", Pattern.CASE_INSENSITIVE);

    private final IntentService intentService;
    private final ClarifyService clarifyService;
    private final ParallelRecommendService recommendService;
    private final EmotionService emotionService;
    private final PerspectiveHubService perspectiveHub;
    private final SessionStateService stateService;
    private final SessionService sessionService;
    private final ShortTermMemory memory;
    private final VoiceEventPublisher eventPublisher;
    private final ComplianceChecker compliance;
    private final MeterRegistry metrics;
    private final ProductRepository productRepo;
    private final TurnSummarizer turnSummarizer;
    private final PendingOrderStore pendingStore;
    private final OrderReferenceResolver referenceResolver;
    private final OrderService orderService;
    private final EmotionStreamingService emotionStreamingService;
    private final TtsService tts;
    private final CachedTtsPhrases ttsCache;

    @Value("${voice-shopping.perspective.enabled:false}")
    private boolean perspectiveEnabled;

    /**
     * 处理一次用户输入的主入口。
     *
     * @return 封装好的结果（speechText + displayBlocks），交给 TTS 下发
     */
    public EmotionResult handle(String sessionId, Long userId, String utterance) {

        Timer.Sample sample = Timer.start(metrics);
        try {
            // 幂等开启 session 主表（session_state 外键依赖，首轮必须先创建）
            sessionService.openIfAbsent(sessionId, userId, "HOME_ENTRY");

            eventPublisher.publishUserSpoken(sessionId, userId, utterance);
            //memory.append(sessionId, new ShortTermMemory.Turn("USER", null, utterance, System.currentTimeMillis()));

            SessionStateEntity state = stateService.load(sessionId);

            IntentResult intent = resolveIntent(sessionId, state, utterance);
            log.info("[Orc] sessionId={} intent={} slots={}", sessionId, intent.intent(), intent.slots());

            leaveOrderConfirmationForShopping(sessionId, state, intent);
            state.setCurrentIntent(intent.intent().name());

            EmotionResult result = switch (intent.intent()) {
                case PRODUCT_RECOMMENDATION -> handleRecommendation(sessionId, userId, state, utterance, intent);
                case CLARIFY_NEEDED -> handleClarify(sessionId, state, utterance, intent);
                case PRODUCT_COMPARE -> handleCompare(sessionId, userId, state, utterance, intent);
                case ORDER_CONFIRM -> isOrderStatusQuestion(utterance)
                        ? handleOrderStatus(sessionId, userId, state)
                        : handleOrderConfirm(sessionId, userId, state, utterance);
                case CHITCHAT -> isCatalogQuestion(utterance)
                        ? handleCatalog(sessionId, state) : handleChitchat(sessionId, userId, utterance);
                case OUT_OF_SCOPE -> handleOutOfScope(sessionId, utterance);
            };

            // 合规兜底
            result = compliance.ensureCompliant(sessionId, userId, result);

            String summary = turnSummarizer.summarize(utterance, intent.intent(), result.speechText());
            memory.append(sessionId, new ShortTermMemory.Turn(
                    "TURN", intent.intent().name(), summary, System.currentTimeMillis()));
//            memory.append(sessionId, new ShortTermMemory.Turn(
//                    "ASSISTANT", intent.intent().name(), result.speechText(), System.currentTimeMillis()));
            stateService.save(state);
            return result;

        } finally {
            sample.stop(metrics.timer("voice_shopping.orchestrator.latency"));
        }
    }

    /* ========== 四条分支的具体实现 ========== */

    private EmotionResult handleRecommendation(String sessionId, Long userId,
                                               SessionStateEntity state,
                                               String utterance, IntentResult intent) {
        // 合并历史槽位 + 本轮新槽位
        Map<String, Object> slots = recommendationSlots(state, intent, utterance);

        state.setSlots(slots);
        state.setCurrentIntent("PRODUCT_RECOMMENDATION");

        // 澄清判断
        ClarifyResult clarify = clarifyService.decide(sessionId, utterance, slots);
        if (clarify.action() == ClarifyResult.Action.ASK) {
            state.setPhase("CLARIFY");
            state.setPendingAsk(clarify.missingSlots().get(0));
            return new EmotionResult(clarify.questionToAsk(), List.of());
        }

        // 推荐
        state.setPhase("RECOMMEND");
        RecommendResult rec = recommendService.recommend(sessionId, userId, utterance, slots);
        state.setLastRecommendations(rec.items().stream().map(RecommendedItem::productId).toList());
        if (rec.items().isEmpty()) return emptyRecommendation(state, slots);
        state.setPendingAsk(null);

        // 旁路：多视角点评团（15 节 PerspectiveHubService）。失败/关闭时降级为原始 utterance
        String contextForEmotion = utterance;
        if (perspectiveEnabled && !rec.items().isEmpty()) {
            String digest = perspectiveHub.discuss(sessionId, utterance, rec.items());
            if (digest != null && !digest.isBlank()) {
                contextForEmotion = utterance + "\n\n【点评团意见】\n" + digest;
            }
        }

        String userNeeds = formatUserNeeds(slots);
        return emotionService.wrap(sessionId, contextForEmotion, userNeeds, rec);
    }

    private EmotionResult handleClarify(String sessionId, SessionStateEntity state,
                                        String utterance, IntentResult intent) {
        if (BudgetUtterance.isIncomplete(utterance)) {
            state.setPhase("CLARIFY");
            state.setPendingAsk("budget");
            return new EmotionResult("新的预算上限是多少元？", List.of());
        }
        if (intent.slots() != null && Boolean.TRUE.equals(intent.slots().get("confirmer"))) {
            // 对“可以”的指向不作猜测，也不把旧预算悄悄改成无限制。
            if ("budget".equals(state.getPendingAsk())) {
                state.setPhase("CLARIFY");
                return new EmotionResult("可以，新的预算上限是多少元？", List.of());
            }
            if (state.getLastRecommendations() != null && !state.getLastRecommendations().isEmpty()) {
                return new EmotionResult("你想选哪一款，还是继续看其他商品？", List.of());
            }
            state.setPhase("CLARIFY");
            state.setPendingAsk("category");
            return new EmotionResult("你想看哪一类商品？也可以告诉我预算和用途。", List.of());
        }
        // 用户第一次就说得很模糊（"最近想买点东西"），用意图里抽到的 slots 开始
        Map<String, Object> slots = new HashMap<>(intent.slots());
        state.setSlots(slots);
        state.setPhase("CLARIFY");

        ClarifyResult clarify = clarifyService.decide(sessionId, utterance, slots);
        state.setPendingAsk(clarify.action() == ClarifyResult.Action.ASK
                ? clarify.missingSlots().get(0) : null);
        return new EmotionResult(
                clarify.action() == ClarifyResult.Action.ASK
                        ? clarify.questionToAsk()
                        : "好，我这就帮你挑。",
                List.of());
    }

    private EmotionResult handleCompare(String sessionId, Long userId,
                                        SessionStateEntity state,
                                        String utterance, IntentResult intent) {
        // 对比类 = 在上一次推荐结果基础上，根据当前诉求重新排序/过滤
        Map<String, Object> slots = recommendationSlots(state, intent, utterance);

        // "便宜点/贵点"要以**上一轮推荐的实际价格**为锚，不能直接用 budget——
        // budget 只是用户给的上限，上一轮推出来的商品可能远低于 budget，
        // 若直接按 budget * 0.8 下调，新上限可能还比上一轮最高价高，起不到"便宜"的效果。
        boolean explicitBudget = hasExplicitBudget(intent, utterance);
        String pd = (String) slots.get("priceDirection");
        List<Long> lastIds = state.getLastRecommendations();
        // 介绍/对比上一轮商品时保留原商品及顺序，不重新检索出另一批。
        if (!explicitBudget && pd == null && lastIds != null && !lastIds.isEmpty()
                && (utterance.contains("介绍") || utterance.contains("对比")
                || utterance.contains("比较") || utterance.contains("这三款")
                || utterance.contains("这几款") || utterance.contains("哪个好"))) {
            Map<Long, ProductEntity> previous = productRepo.findByIdIn(lastIds).stream()
                    .collect(Collectors.toMap(ProductEntity::getId, p -> p));
            List<RecommendedItem> items = lastIds.stream().map(previous::get)
                    .filter(Objects::nonNull)
                    .map(p -> new RecommendedItem(p.getId(), p.getName(), p.getPrice(),
                            p.getDescription(), 1.0,
                            p.getAttributes() == null ? Map.of() : p.getAttributes()))
                    .toList();
            return emotionService.wrap(sessionId, utterance, formatUserNeeds(slots),
                    new RecommendResult(items, "professional"));
        }
        if (pd != null && lastIds != null && !lastIds.isEmpty()) {
            List<BigDecimal> lastPrices = productRepo.findByIdIn(lastIds).stream()
                    .map(ProductEntity::getPrice).toList();
            if (!lastPrices.isEmpty()) {
                BigDecimal maxP = lastPrices.stream().max(BigDecimal::compareTo).get();
                BigDecimal minP = lastPrices.stream().min(BigDecimal::compareTo).get();
                // cheaper：新上限 = 上轮最高价 * 0.8，保证比上一轮任何一款都便宜
                // expensive：新下限 = 上轮最低价 * 1.2（这里先放 budget 里，推荐层读 priceMin 字段）
                if ("cheaper".equals(pd)) {
                    slots.put("budget", maxP.multiply(BigDecimal.valueOf(0.8)).intValue());
                } else if ("expensive".equals(pd)) {
                    slots.put("priceMin", minP.multiply(BigDecimal.valueOf(1.2)).intValue());
                }
                // 排除上轮已推过的，避免重复推同一款
                slots.put("excludeProductIds", lastIds);
            }
        }

        RecommendResult rec = recommendService.recommend(sessionId, userId, utterance, slots);
        state.setSlots(slots);
        state.setPhase("RECOMMEND");
        state.setLastRecommendations(rec.items().stream().map(RecommendedItem::productId).toList());
        log.info("[Compare-Rec] sessionId={} slotsForRecommend={} recCount={}",
                sessionId, slots, rec.items().size());
        if (rec.items().isEmpty()) return emptyRecommendation(state, slots);
        state.setPendingAsk(null);
        String userNeeds = formatUserNeeds(slots);
        return emotionService.wrap(sessionId, utterance, userNeeds, rec);
    }

    private EmotionResult handleOrderConfirm(String sessionId, Long userId,
                                             SessionStateEntity state, String utterance) {
        // 已经有 pending 单了，判断是 YES 还是 NO
        PendingOrderStore.PendingOrder pending = pendingStore.get(sessionId);
        if (pending != null) {
            // 否定优先，避免把“不要确认下单”里的“确认”当成同意。
            if (containsNo(utterance)) {
                orderService.cancel(sessionId);
                state.setPhase("RECOMMEND");
                return new EmotionResult("好的，我没有为你下单。想再聊点别的还是换款看看？", List.of());
            }
            if (containsYes(utterance)) {
                OrderEntity order = orderService.confirm(sessionId);
                state.setPhase("ENDED");
                return new EmotionResult(
                        String.format("下单成功，订单尾号 %s，1-2 天送达。还有想看的吗？",
                                order.getOrderNo().substring(0, 6)),
                        List.of());
            }
            return new EmotionResult("那你是确认要这款还是不要？", List.of());
        }

        // 没有 pending，新起一个
        Optional<Long> pidOpt = referenceResolver.resolve(state, utterance);
        if (pidOpt.isEmpty()) {
            return new EmotionResult(
                    "你想要的是刚才推荐的哪一款？可以说第一款、第二款或者商品名。",
                    List.of());
        }
        PendingOrderStore.PendingOrder po = orderService.preview(sessionId, userId, pidOpt.get(), 1);
        state.setPhase("ORDER_CONFIRM");
        return new EmotionResult(
                String.format("好，帮你准备下单：%s，¥%s，一共 %s 元。确认下单吗？",
                        po.productName(), po.unitPrice(), po.totalAmount()),
                List.of());
    }

    private boolean containsYes(String s) {
        if (s == null) return false;
        String reply = s.replaceAll("[，。！？,.!?\\s]", "");
        return switch (reply) {
            case "确认", "确认下单", "确认购买", "确认要这款", "就这", "就这款", "就它",
                    "好", "好的", "好吧", "可以", "对", "是", "是的", "嗯", "行",
                    "要", "我要", "我要这款", "买吧", "下单", "下单吧", "帮我下单" -> true;
            default -> false;
        };
    }

    private boolean containsNo(String s) {
        return s != null && (s.contains("不要") || s.contains("算了") || s.contains("取消")
                || s.contains("不买") || s.contains("不确认") || s.contains("不下单")
                || s.contains("别下单") || s.contains("再想想") || s.contains("等下"));
    }

    private boolean isOrderStatusQuestion(String utterance) {
        return utterance != null
                && (utterance.contains("买过") || utterance.contains("已经买")
                    || utterance.contains("下单了吗") || utterance.contains("下单成功了吗"))
                && !(utterance.contains("再买") || utterance.contains("推荐")
                    || utterance.contains("想买") || utterance.contains("换")
                    || utterance.contains("再来"));
    }

    private EmotionResult handleOrderStatus(String sessionId, Long userId, SessionStateEntity state) {
        Optional<OrderEntity> latest = orderService.latestForSession(sessionId, userId);
        boolean hasPending = pendingStore.get(sessionId) != null;
        if (latest.isPresent()) {
            OrderEntity order = latest.get();
            if (!hasPending) state.setPhase("ENDED");
            String status = "CANCELLED".equals(order.getStatus()) ? "订单已经取消" : "订单已经生成";
            return new EmotionResult(String.format("刚才的%s，金额 %s 元。%s", status,
                    order.getTotalAmount(), hasPending
                            ? "另有一笔待确认的订单，还没有下单。" : "想再买的话，可以告诉我新的需求。"), List.of());
        }
        if (!hasPending) state.setPhase("RECOMMEND");
        return new EmotionResult(hasPending ? "目前只是准备了待确认订单，还没有下单。"
                : "当前会话还没有生成订单，可以先选一款商品。", List.of());
    }

    private IntentResult resolveIntent(String sessionId, SessionStateEntity state, String utterance) {
        if (isCatalogQuestion(utterance)) {
            return new IntentResult(Intent.CHITCHAT, Map.of(), 1.0);
        }
        if (isOrderStatusQuestion(utterance)) {
            return new IntentResult(Intent.ORDER_CONFIRM, Map.of(), 1.0);
        }
        if (CommonConfirmer.isCommonConfirmer(utterance) && !CommonConfirmer.isContinuation(utterance)) {
            return pendingStore != null && pendingStore.get(sessionId) != null
                    ? new IntentResult(Intent.ORDER_CONFIRM, Map.of(), 1.0)
                    : CommonConfirmer.CONFIRMER_INTENT;
        }
        // ASR 可能把“预算调整到”和金额分成两条 final，不能拿旧预算补全前半句。
        if (BudgetUtterance.isIncomplete(utterance)) {
            return new IntentResult(Intent.CLARIFY_NEEDED, Map.of(), 1.0);
        }
        IntentResult intent = intentService.classify(sessionId, utterance);
        // “七佰”等 ASR 数字变体可能让模型漏抽金额，不能因此回退到旧预算。
        Optional<BudgetUtterance.Budget> explicitBudget = BudgetUtterance.explicitBudget(utterance);
        if (explicitBudget.isPresent() && (intent.intent() == Intent.PRODUCT_RECOMMENDATION
                || intent.intent() == Intent.PRODUCT_COMPARE || intent.intent() == Intent.CLARIFY_NEEDED)) {
            Map<String, Object> slots = new HashMap<>(intent.slots() == null ? Map.of() : intent.slots());
            slots.put("budget", explicitBudget.get().maximum());
            if (explicitBudget.get().minimum() != null) slots.put("budgetMin", explicitBudget.get().minimum());
            else slots.remove("budgetMin");
            slots.remove("priceDirection");
            intent = new IntentResult(intent.intent(), slots, intent.confidence());
        }
        // 明确说出的品类不能因模型漏抽而沿用旧品类。
        if (intent.intent() == Intent.PRODUCT_RECOMMENDATION || intent.intent() == Intent.CLARIFY_NEEDED
                || intent.intent() == Intent.ORDER_CONFIRM) {
            Map<String, Object> slots = new HashMap<>(intent.slots() == null ? Map.of() : intent.slots());
            if (slots.get("category") == null) {
                Matcher mentions = CATEGORY_MENTION.matcher(utterance);
                String category = null;
                while (mentions.find()) category = mentions.group();
                if (category != null) {
                    slots.put("category", "服装".equals(category) ? "衣服" : category);
                    intent = new IntentResult(intent.intent(), slots, intent.confidence());
                }
            }
        }
        intent = reviseIntentByContext(state, intent, utterance);
        Object category = intent.slots() == null ? null : intent.slots().get("category");
        Object previousCategory = state.getSlots() == null ? null : state.getSlots().get("category");
        // 购买另一品类是新的购物需求，不能确认上一轮的商品。
        if (intent.intent() == Intent.ORDER_CONFIRM && category != null
                && !Objects.equals(category, previousCategory)
                && referenceResolver.resolve(state, utterance).isEmpty()) {
            return new IntentResult(Intent.PRODUCT_RECOMMENDATION, intent.slots(), intent.confidence());
        }
        return intent;
    }

    private void leaveOrderConfirmationForShopping(String sessionId, SessionStateEntity state, IntentResult intent) {
        if ("ORDER_CONFIRM".equals(state.getPhase())
                && (intent.intent() == Intent.PRODUCT_RECOMMENDATION
                    || intent.intent() == Intent.CLARIFY_NEEDED || intent.intent() == Intent.PRODUCT_COMPARE)) {
            // 只移除临时预览，不影响已写入的订单。
            orderService.cancel(sessionId);
            state.setPhase("RECOMMEND");
            state.setPendingAsk(null);
        }
    }

    private EmotionResult handleChitchat(String sessionId, Long userId, String utterance) {
        // 闲聊走 EmotionService 的闲聊模式（EmotionAgent 内部可判断 products 为空）
        return emotionService.wrap(sessionId, utterance, "",
                new RecommendResult(List.of(), "chitchat"));
    }

    private boolean isCatalogQuestion(String utterance) {
        return utterance != null && utterance.matches(
                ".*(?:这里|这儿|店里|你们|你这边).*(?:都有什么|有什么商品|卖什么|有哪些商品|有什么卖).*"
                        + "|.*(?:有什么商品|有哪些商品|卖些什么).*");
    }

    private EmotionResult handleCatalog(String sessionId, SessionStateEntity state) {
        if (pendingStore != null && pendingStore.get(sessionId) != null) orderService.cancel(sessionId);
        state.setSlots(new HashMap<>());
        state.setLastRecommendations(List.of());
        state.setPendingAsk(null);
        state.setPhase("INTENT");
        List<String> categories = recommendService.availableCategories(sessionId);
        return new EmotionResult(categories.isEmpty() ? "当前范围内没有有库存的在售商品。"
                : "目前有" + String.join("、", categories) + "。你想先看哪一类？", List.of());
    }

    private EmotionResult emptyRecommendation(SessionStateEntity state, Map<String, Object> slots) {
        state.setPhase("CLARIFY");
        String category = Objects.toString(slots.get("category"), "商品");
        Object budget = slots.get("budget");
        if (budget instanceof Number) {
            state.setPendingAsk("budget");
            String range = slots.get("budgetMin") instanceof Number minimum
                    ? minimum + "到" + budget + "元之间" : budget + "元以内";
            return new EmotionResult("没有找到符合当前条件、" + range + "的" + category
                    + "。如果想调整预算，新的预算上限是多少元？", List.of());
        }
        state.setPendingAsk("category");
        return new EmotionResult("没有找到符合当前条件的" + category + "。想换个品类或调整用途吗？", List.of());
    }

    private EmotionResult handleOutOfScope(String sessionId, String utterance) {
        return new EmotionResult(
                "我现在只负责帮你挑商品，这个问题可以找客服处理。我们继续聊想买什么？",
                List.of());
    }


    /**
     * 意图后处理：LLM 在两类场景下容易判错，这里做兜底矫正。
     * <p>
     * 1) 上下文比较：上一轮已有推荐 + 本轮抽到 priceDirection →
     * CLARIFY_NEEDED / PRODUCT_RECOMMENDATION 统一改写成 PRODUCT_COMPARE
     * <p>
     * 2) 信息已足：state.slots + 本轮 slots 合并后，category + (budget|scenario|brand)
     * 任一组合齐全，就不该再 CLARIFY_NEEDED，强制改写为 PRODUCT_RECOMMENDATION
     */
    private IntentResult reviseIntentByContext(SessionStateEntity state, IntentResult intent, String utterance) {
        Map<String, Object> curSlots = intent.slots() == null ? Map.of() : intent.slots();

        // 明确金额/区间是一次新的检索，直接走流式推荐，不能被“贵了”带回整段对比回复。
        boolean hasCategory = curSlots.get("category") != null
                || (state.getSlots() != null && state.getSlots().get("category") != null);
        if (hasCategory && hasExplicitBudget(intent, utterance)
                && (intent.intent() == Intent.PRODUCT_RECOMMENDATION
                || intent.intent() == Intent.PRODUCT_COMPARE || intent.intent() == Intent.CLARIFY_NEEDED)) {
            return new IntentResult(Intent.PRODUCT_RECOMMENDATION, curSlots, intent.confidence());
        }

        // ① 上下文价格对比
        boolean hasLastRecommend = "RECOMMEND".equals(state.getPhase())
                && state.getLastRecommendations() != null
                && !state.getLastRecommendations().isEmpty();
        boolean hasPriceDirection = curSlots.get("priceDirection") != null;
        if (hasLastRecommend && hasPriceDirection
                && (intent.intent() == Intent.CLARIFY_NEEDED
                || intent.intent() == Intent.PRODUCT_RECOMMENDATION)) {
            log.info("[Orc] 意图矫正 {} -> PRODUCT_COMPARE, priceDirection={}",
                    intent.intent(), curSlots.get("priceDirection"));
            return new IntentResult(Intent.PRODUCT_COMPARE, curSlots, intent.confidence());
        }

        // ② 信息已足阈值：合并历史 slots + 本轮 slots
        if (intent.intent() == Intent.CLARIFY_NEEDED) {
            Map<String, Object> merged = recommendationSlots(state, intent, utterance);

            boolean hasMergedCategory = merged.get("category") != null;
            boolean hasAnyAnchor = merged.get("budget") != null
                    || merged.get("scenario") != null
                    || merged.get("brand") != null;
            if (hasMergedCategory && hasAnyAnchor) {
                log.info("[Orc] 意图矫正 CLARIFY_NEEDED -> PRODUCT_RECOMMENDATION, mergedSlots={}", merged);
                return new IntentResult(Intent.PRODUCT_RECOMMENDATION, merged, intent.confidence());
            }
        }

        return intent;
    }

    private static boolean hasExplicitBudget(IntentResult intent, String utterance) {
        return intent.slots() != null && intent.slots().get("budget") instanceof Number
                && BudgetUtterance.hasExplicitAmount(utterance);
    }

    private static Map<String, Object> recommendationSlots(SessionStateEntity state,
                                                           IntentResult intent, String utterance) {
        Map<String, Object> slots = new HashMap<>();
        if (state.getSlots() != null) slots.putAll(state.getSlots());
        // 比较产生的下限和排除列表只用于当轮，不能永久污染后续检索。
        slots.remove("priceDirection");
        slots.remove("priceMin");
        slots.remove("excludeProductIds");
        if (intent.slots() != null) intent.slots().forEach((k, v) -> {
            if (v != null) slots.put(k, v);
        });
        if (hasExplicitBudget(intent, utterance)) {
            // “700 元”比模型推断的“更便宜”优先；允许重推仍符合新预算的商品。
            slots.remove("priceDirection");
            slots.remove("priceMin");
            slots.remove("excludeProductIds");
            // 用户给出的区间下限是持久偏好，只有新预算才能替换或清除。
            Number minimum = BudgetUtterance.explicitBudget(utterance).orElseThrow().minimum();
            if (minimum != null) slots.put("budgetMin", minimum);
            else slots.remove("budgetMin");
        }
        return slots;
    }

    private static String formatUserNeeds(Map<String, Object> slots) {
        if (slots == null || slots.isEmpty()) return "";
        return slots.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("，"));
    }

    public Flux<StreamChunk> streamHandle(String sessionId, Long userId, String utterance) {
        sessionService.openIfAbsent(sessionId, userId, "HOME_ENTRY");
        SessionStateEntity state = stateService.load(sessionId);
        IntentResult intent = resolveIntent(sessionId, state, utterance);
        log.info("[Stream] sessionId={} intent={} slots={}",
                sessionId, intent.intent(), intent.slots());

        if (intent.intent() != Intent.PRODUCT_RECOMMENDATION) {
            EmotionResult r = handle(sessionId, userId, utterance);
            Flux<StreamChunk> products = r.displayBlocks() == null || r.displayBlocks().isEmpty()
                    ? Flux.empty() : Flux.just(StreamChunk.products(r.displayBlocks()));
            return Flux.concat(
                    products,
                    Flux.just(StreamChunk.text(r.speechText())),
                    ttsAudio(r.speechText()).map(StreamChunk::audio)
            );
        }

        leaveOrderConfirmationForShopping(sessionId, state, intent);

        Map<String, Object> slots = recommendationSlots(state, intent, utterance);
        eventPublisher.publishUserSpoken(sessionId, userId, utterance);
        RecommendResult rec = recommendService.recommend(sessionId, userId, utterance, slots);
        state.setSlots(slots);
        state.setCurrentIntent("PRODUCT_RECOMMENDATION");
        state.setPhase("RECOMMEND");
        state.setPendingAsk(null);
        state.setLastRecommendations(rec.items().stream().map(RecommendedItem::productId).toList());
        EmotionResult emptyReply = rec.items().isEmpty() ? emptyRecommendation(state, slots) : null;
        stateService.save(state);
        log.info("[Stream-Rec] sessionId={} slotsForRecommend={} recCount={}",
                sessionId, slots, rec.items().size());

        if (emptyReply != null) {
            EmotionResult reply = compliance.ensureCompliant(sessionId, userId, emptyReply);
            String summary = turnSummarizer.summarize(utterance, intent.intent(), reply.speechText());
            memory.append(sessionId, new ShortTermMemory.Turn(
                    "TURN", intent.intent().name(), summary, System.currentTimeMillis()));
            return Flux.concat(Flux.just(StreamChunk.products(List.of())),
                    Flux.just(StreamChunk.text(reply.speechText())),
                    ttsAudio(reply.speechText()).map(StreamChunk::audio));
        }

        // 先把商品卡片发下去（用户立刻看到 UI）
        Flux<StreamChunk> productsFlow = Flux.just(StreamChunk.products(rec.items()));

        // EmotionAgent 流式文本 → 句子聚合 → TTS 流式合成
        Flux<String> rawTokens = emotionStreamingService.streamWrap(sessionId, utterance, rec);
        StringBuilder reply = new StringBuilder();
        Flux<String> sentences = SentenceAggregator.aggregate(rawTokens).doOnNext(reply::append);

        Flux<StreamChunk> textFlow = sentences.concatMap(sentence -> Flux.merge(
                Flux.just(StreamChunk.text(sentence)),
                ttsAudio(sentence).map(StreamChunk::audio)
        ));

        return Flux.concat(productsFlow, textFlow).doOnComplete(() -> {
            String summary = turnSummarizer.summarize(utterance, Intent.PRODUCT_RECOMMENDATION, reply.toString());
            memory.append(sessionId, new ShortTermMemory.Turn(
                    "TURN", Intent.PRODUCT_RECOMMENDATION.name(), summary, System.currentTimeMillis()));
        });
    }


//    private Flux<ByteBuffer> ttsAudio(String text) {
//        Flowable<ByteBuffer> flow = tts.synthesize(Flowable.just(text));
//        return Flux.from(flow);
//    }

    private Flux<ByteBuffer> ttsAudio(String text) {
        byte[] cached = ttsCache.get(text);
        if (cached != null) {
            log.debug("[Cost] TTS 命中缓存 text={} bytes={}", text, cached.length);
            return Flux.just(java.nio.ByteBuffer.wrap(cached));
        }
        Flowable<ByteBuffer> flow = tts.synthesize(Flowable.just(text));
        return Flux.from(flow);
    }

}
