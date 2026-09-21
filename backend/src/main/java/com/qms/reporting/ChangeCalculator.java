package com.qms.reporting;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FR-RPT-010 / FR-MON-011's own "absolute and percentage change" against the previous equivalent period: for every
 * numeric metric present in both {@code current} and {@code previous}, the absolute difference and, when the
 * previous value is non-zero, the percentage difference. A metric missing from either side, or whose previous value
 * is zero (percentage change is undefined, not infinite), is left with a {@code null} percent — never a fabricated
 * number. Pure and stateless so it is tested without a database.
 */
final class ChangeCalculator {

    private ChangeCalculator() {}

    static Map<String, Object> changes(Map<String, Object> current, Map<String, Object> previous) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : current.entrySet()) {
            String key = entry.getKey();
            Object currentValue = entry.getValue();
            Object previousValue = previous.get(key);
            if (!(currentValue instanceof Number currentNumber) || !(previousValue instanceof Number previousNumber)) continue;
            BigDecimal currentDecimal = toDecimal(currentNumber);
            BigDecimal previousDecimal = toDecimal(previousNumber);
            BigDecimal absolute = currentDecimal.subtract(previousDecimal);
            Double percent = previousDecimal.signum() == 0
                    ? null
                    : absolute.divide(previousDecimal.abs(), MathContext.DECIMAL64).doubleValue() * 100.0;
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("absolute", stripToNumber(absolute));
            change.put("percent", percent);
            out.put(key, change);
        }
        return out;
    }

    private static BigDecimal toDecimal(Number n) {
        return n instanceof BigDecimal bd ? bd : new BigDecimal(n.toString());
    }

    /** A whole-number absolute change reads as a whole number (counts), not "3.0000". */
    private static Object stripToNumber(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.longValueExact() : stripped.doubleValue();
    }
}
