package com.zhaoyijin.voiceshopping.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class BudgetUtteranceTest {
    @ParameterizedTest
    @ValueSource(strings = {"我把预算调整到。", "预算", "预算提高到", "预算控制在……", "调整一下预算",
            "预算嗯1000到。", "1000到", "预算嗯"})
    void waitsForMissingAmount(String text) {
        assertTrue(BudgetUtterance.isIncomplete(text));
        assertTrue(BudgetUtterance.explicitBudget(text).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"预算800元。", "七百再推荐一下。", "那就800以内", "预算一千五", "预算699.5元"})
    void recognizesExplicitBudgetInFollowUps(String text) {
        assertTrue(BudgetUtterance.hasExplicitAmount(text));
        assertFalse(BudgetUtterance.isIncomplete(text));
    }

    @ParameterizedTest
    @CsvSource({"我把预算调整到七佰再推荐一下。,700", "预算壹仟伍佰元,1500", "预算一千五,1500",
            "预算一千零五,1005", "预算一万五,15000", "预算两千,2000", "预算699.5元,699.5",
            "预算1.5千元,1500", "七百点五再推荐一下。,700.5", "那就800以内,800"})
    void parsesSpokenAndFinancialNumerals(String text, String expected) {
        Number amount = BudgetUtterance.explicitAmount(text).orElseThrow();
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(amount.toString())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"感觉有点贵了，我预算嗯1000到1500吧。", "预算1000~1500", "1000～1500",
            "预算1000-1500", "预算摁1000元到1500元。", "预算一千至一千五百", "那就1000到1500元吧", "预算呃，一千元到一千五百元之间"})
    void keepsBothEndsOfAnExplicitRange(String text) {
        BudgetUtterance.Budget budget = BudgetUtterance.explicitBudget(text).orElseThrow();
        assertEquals(1000, budget.minimum());
        assertEquals(1500, budget.maximum());
        assertFalse(BudgetUtterance.isIncomplete(text));
    }

    @Test
    void fillerBeforeSingleAmountDoesNotHideIt() {
        assertEquals(new BudgetUtterance.Budget(null, 800),
                BudgetUtterance.explicitBudget("预算嗯800元").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"再便宜一点", "介绍第二款", "买43码的鞋", "就买第二款", "这款700元吗？", "预算不限", "预算降低二百"})
    void doesNotMistakeOtherNumbersOrRequestsForBudgetChanges(String text) {
        assertFalse(BudgetUtterance.hasExplicitAmount(text));
        assertFalse(BudgetUtterance.isIncomplete(text));
    }
}
