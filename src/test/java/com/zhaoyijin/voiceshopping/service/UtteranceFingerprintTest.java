package com.zhaoyijin.voiceshopping.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class UtteranceFingerprintTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "我想买 | 想买",
            "我想买手机 | 想买手机",
            "我想要买手机 | 想买手机",
            "想要买手机 | 想买手机",
            "嗯，我想买手机 | 想买手机",
            "呃呃我想买手机 | 想买手机",
            "嗯，那个，我想买手机 | 想买手机",
            "就是，我想买手机 | 想买手机",
            "我想买手机，嗯，预算1000元 | 想买手机预算1000元",
            "我想买手机，那个，预算1000元 | 想买手机预算1000元",
            " 我 想 买 IPHONE！ | 想买iphone",
            "我想买手机。 | 想买手机"
    })
    void mergesKnownEquivalentShoppingExpressions(String first, String second) {
        assertEquals(UtteranceFingerprint.compute(first), UtteranceFingerprint.compute(second));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "我不想买手机 | 我想买手机",
            "我想买手机 | 我想买电脑",
            "我想买两台手机 | 我想买一台手机",
            "我想买手机预算1000 | 我想买手机预算2000",
            "我想买手机预算699.5 | 我想买手机预算6995",
            "想买手机吗 | 想买手机",
            "想买手机吧 | 想买手机",
            "那个手机 | 手机",
            "就是这个手机 | 这个手机",
            "我想买娃哈哈 | 我想买娃",
            "我想买啊哈耳机 | 我想买哈耳机",
            "我想买手机 | 他想买手机",
            "我想买手机 | 我已经买了手机",
            "我想买手机 | 我想买手机不要黑色"
    })
    void preservesMeaningfulDifferences(String first, String second) {
        assertNotEquals(UtteranceFingerprint.compute(first), UtteranceFingerprint.compute(second));
    }

    @Test
    void remainsStableAcrossDefaultLocales() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(UtteranceFingerprint.compute("iphone"), UtteranceFingerprint.compute("IPHONE"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void acceptsNullAndBlankInput() {
        assertEquals(UtteranceFingerprint.compute(null), UtteranceFingerprint.compute(" \n\t"));
        assertNotEquals(UtteranceFingerprint.compute(""), UtteranceFingerprint.compute("想买手机"));
        assertNotEquals(UtteranceFingerprint.compute(""), UtteranceFingerprint.compute("嗯"));
        assertNotEquals(UtteranceFingerprint.compute("嗯"), UtteranceFingerprint.compute("呃"));
    }
}
