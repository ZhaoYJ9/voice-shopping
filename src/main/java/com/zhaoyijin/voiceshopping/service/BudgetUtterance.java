package com.zhaoyijin.voiceshopping.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 识别预算话语边界，并解析明确说出的金额，避免模型沿用历史预算。 */
public final class BudgetUtterance {
    private static final String AMOUNT = "(?:[0-9]+(?:\\.[0-9]+)?(?:千|万)?|[零〇一二两三四五六七八九十百千万]+(?:点[零〇一二两三四五六七八九]+)?)";
    private static final String PAUSE = "(?:嗯+|呃+|额+|啊+|摁+|[\\s，,：:])*";
    private static final String LABELED_PREFIX = "(?:预算|价格|价位|上限|不超过|最多|控制在)"
            + "(?:上限|(?:调整|提高|降低|增加|减少|控制|改|调|降|升|提)(?:为|到|至|成|在)|"
            + "再|一下|是|为|到|至|成|在|大概|大约|最多|不超过|嗯+|呃+|额+|啊+|摁+|[\\s，,：:])*";
    private static final String REPLY_PREFIX = "^(?:那就|就|改成|改为|调整到)?" + PAUSE;
    private static final String REPLY_SUFFIX = "\\s*(?:元|块钱|块)?\\s*(?:以内|以下|左右|之间|封顶)?"
            + "\\s*(?:吧|呢)?\\s*(?:[。！!，,\\s]*|[，,\\s]*(?:再)?推荐.*)$";
    private static final String RANGE_SEPARATOR = "\\s*(?:元|块钱|块)?\\s*(?:到|至|[-~～—－])" + PAUSE;
    private static final String RANGE = "(" + AMOUNT + ")" + RANGE_SEPARATOR + "(" + AMOUNT + ")";
    private static final Pattern INCOMPLETE = Pattern.compile(
            ".*(?:预算|上限)(?:上限|调整|提高|降低|增加|减少|控制|改|调|降|升|提|再|一下|是|为|到|至|成|在|大概|大约|最多|不超过|嗯+|呃+|额+|啊+|摁+|\\s)*[。！？!?，,：:…\\s]*$");
    private static final Pattern INCOMPLETE_RANGE = Pattern.compile(
            "(?:.*" + LABELED_PREFIX + "|" + REPLY_PREFIX + ")" + AMOUNT + RANGE_SEPARATOR + "[。！？!?，,：:…\\s]*$");
    private static final Pattern LABELED_RANGE = Pattern.compile(LABELED_PREFIX + RANGE);
    private static final Pattern RANGE_REPLY = Pattern.compile(REPLY_PREFIX + RANGE + REPLY_SUFFIX);
    private static final Pattern LABELED_AMOUNT = Pattern.compile(LABELED_PREFIX + "(" + AMOUNT + ")");
    private static final Pattern AMOUNT_REPLY = Pattern.compile(REPLY_PREFIX + "(" + AMOUNT + ")" + REPLY_SUFFIX);
    private static final Pattern AMOUNT_START = Pattern.compile("^(?:那就|就)?" + PAUSE + AMOUNT);

    public record Budget(Number minimum, Number maximum) {}

    private BudgetUtterance() {}

    public static boolean isIncomplete(String text) {
        if (text == null) return false;
        String normalized = normalizeNumerals(text.strip());
        return INCOMPLETE.matcher(normalized).matches() || INCOMPLETE_RANGE.matcher(normalized).matches();
    }

    public static boolean hasExplicitAmount(String text) {
        return explicitBudget(text).isPresent();
    }

    public static Optional<Number> explicitAmount(String text) {
        return explicitBudget(text).map(Budget::maximum);
    }

    public static Optional<Budget> explicitBudget(String text) {
        if (text == null || isIncomplete(text)) return Optional.empty();
        String normalized = normalizeNumerals(text.strip());
        // 区间必须先于单金额识别，避免把“1000 到 1500”截成“1000 以内”。
        Matcher range = LABELED_RANGE.matcher(normalized);
        if (!range.find()) {
            range = RANGE_REPLY.matcher(normalized);
            if (!range.matches()) range = null;
        }
        if (range != null) {
            BigDecimal first = parseAmount(range.group(1));
            BigDecimal second = parseAmount(range.group(2));
            return Optional.of(new Budget(asNumber(first.min(second)), asNumber(first.max(second))));
        }
        Matcher labeled = LABELED_AMOUNT.matcher(normalized);
        Matcher reply = AMOUNT_REPLY.matcher(normalized);
        if (labeled.find()) return Optional.of(new Budget(null, asNumber(parseAmount(labeled.group(1)))));
        if (reply.matches()) return Optional.of(new Budget(null, asNumber(parseAmount(reply.group(1)))));
        return Optional.empty();
    }

    private static Number asNumber(BigDecimal value) {
        try { return value.intValueExact(); }
        catch (ArithmeticException e) { return value.stripTrailingZeros(); }
    }

    public static boolean startsWithAmount(String text) {
        return text != null && AMOUNT_START.matcher(normalizeNumerals(text.strip())).find();
    }

    private static String normalizeNumerals(String text) {
        String from = "〇两壹贰叁肆伍陆柒捌玖拾佰仟萬";
        String to =   "零二一二三四五六七八九十百千万";
        StringBuilder result = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            int index = from.indexOf(c);
            result.append(index < 0 ? c : to.charAt(index));
        }
        return result.toString();
    }

    private static BigDecimal parseAmount(String text) {
        if (Character.isDigit(text.charAt(0))) {
            char last = text.charAt(text.length() - 1);
            int multiplier = last == '千' ? 1000 : last == '万' ? 10000 : 1;
            return new BigDecimal(multiplier == 1 ? text : text.substring(0, text.length() - 1))
                    .multiply(BigDecimal.valueOf(multiplier));
        }
        String[] parts = text.split("点");
        String integer = parts[0];
        String digits = "零一二三四五六七八九";
        long total = 0, section = 0, number = 0, lastUnit = 0;
        boolean zeroAfterUnit = false;
        for (char c : integer.toCharArray()) {
            int digit = digits.indexOf(c);
            if (digit >= 0) {
                number = number * 10 + digit;
                if (digit == 0) zeroAfterUnit = true;
                continue;
            }
            int unit = switch (c) { case '十' -> 10; case '百' -> 100; case '千' -> 1000; default -> 10000; };
            if (unit == 10000) { total += (section + number) * unit; section = 0; }
            else section += (number == 0 ? 1 : number) * unit;
            number = 0; lastUnit = unit; zeroAfterUnit = false;
        }
        // 口语“一千五/一万五”分别表示 1500/15000；“一千零五”仍是 1005。
        if (lastUnit >= 100 && number > 0 && number < 10 && !zeroAfterUnit) number *= lastUnit / 10;
        BigDecimal value = BigDecimal.valueOf(total + section + number);
        if (parts.length > 1) {
            StringBuilder fraction = new StringBuilder("0.");
            for (char c : parts[1].toCharArray()) fraction.append(digits.indexOf(c));
            value = value.add(new BigDecimal(fraction.toString()));
        }
        return value;
    }
}
