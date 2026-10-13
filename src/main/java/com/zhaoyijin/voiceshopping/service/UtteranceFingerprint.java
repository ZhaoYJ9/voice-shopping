package com.zhaoyijin.voiceshopping.service;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

public class UtteranceFingerprint {
    private static final String SEPARATOR = "[，。！？,.!?\\s]";
    // 有歧义的“那个/就是”仅在独立成词时作为口头禅，保留“那个手机/就是这个”。
    private static final Pattern FILLER = Pattern.compile(
            "(^|" + SEPARATOR + ")(?:嗯+|呃+|那个|就是说|就是)(?=" + SEPARATOR + "|$)");
    private static final Pattern LEADING_HESITATION = Pattern.compile("^[嗯呃]+");
    private static final Pattern FORMATTING = Pattern.compile(
            "[，。！？,!?\\s]|(?<![0-9])\\.|\\.(?![0-9])");
    private static final Pattern PURCHASE_PREFIX = Pattern.compile("^(?:我)?想(?:要)?买");

    /**
     * 只合并已知的口头表达变体，不做任意句子的语义相似度判断。
     * 否定词、疑问词、商品和数值条件仍参与指纹计算。
     */
    public static String compute(String utterance) {
        String normalized = normalize(utterance);
        try {
            byte[] bytes = MessageDigest.getInstance("MD5").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    private static String normalize(String utterance) {
        String text = utterance == null ? "" : utterance.trim().toLowerCase(Locale.ROOT);
        // 先识别独立口头禅再移除分隔符，避免把商品名中的片段当作口头禅。
        String withoutFillers = FILLER.matcher(text).replaceAll("$1");
        withoutFillers = FORMATTING.matcher(withoutFillers).replaceAll("");
        withoutFillers = LEADING_HESITATION.matcher(withoutFillers).replaceFirst("");
        // “嗯”等独立回答仍有意义，不能与空输入共用缓存。
        if (withoutFillers.isEmpty()) {
            return FORMATTING.matcher(text).replaceAll("");
        }
        return PURCHASE_PREFIX.matcher(withoutFillers).replaceFirst("想买");
    }
}
