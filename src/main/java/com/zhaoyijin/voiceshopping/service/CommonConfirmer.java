package com.zhaoyijin.voiceshopping.service;

import com.zhaoyijin.voiceshopping.dto.Intent;
import com.zhaoyijin.voiceshopping.dto.IntentResult;

import java.util.Map;
import java.util.Set;

final class CommonConfirmer {

    /**
     * 简短回答本身不能证明用户同意下单，订单确认由编排层结合待确认订单判断。
     */
    private static final Set<String> WORDS = Set.of(
            "嗯", "好", "好的", "对", "是", "行", "可以",
            "继续", "下一个", "再来", "换一个"
    );

    static final IntentResult CONFIRMER_INTENT =
            new IntentResult(Intent.CLARIFY_NEEDED, Map.of("confirmer", true), 0.95);

    static final IntentResult CONTINUE_INTENT =
            new IntentResult(Intent.PRODUCT_RECOMMENDATION, Map.of(), 0.95);

    static boolean isContinuation(String utterance) {
        return utterance != null && Set.of("继续", "下一个", "再来", "换一个")
                .contains(utterance.trim().replaceAll("[，。！？,.!?\\s]", ""));
    }

    static boolean isCommonConfirmer(String utterance) {
        if (utterance == null) return false;
        String s = utterance.trim().replaceAll("[，。！？,.!?\\s]", "");
        return s.length() <= 3 && WORDS.contains(s);
    }
}
