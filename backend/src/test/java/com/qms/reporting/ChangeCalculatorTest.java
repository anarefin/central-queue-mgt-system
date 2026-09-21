package com.qms.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** FR-RPT-010 / FR-MON-011's own absolute-and-percentage change, in isolation from any database. */
class ChangeCalculatorTest {

    @Test
    void absoluteAndPercentChangeForEveryNumericMetric() {
        Map<String, Object> current = Map.of("issued", 120L, "served", 100L);
        Map<String, Object> previous = Map.of("issued", 100L, "served", 100L);

        Map<String, Object> change = ChangeCalculator.changes(current, previous);

        Map<String, Object> issued = (Map<String, Object>) change.get("issued");
        assertThat(number(issued.get("absolute"))).isEqualTo(20.0);
        assertThat(number(issued.get("percent"))).isEqualTo(20.0);

        Map<String, Object> served = (Map<String, Object>) change.get("served");
        assertThat(number(served.get("absolute"))).isEqualTo(0.0);
        assertThat(number(served.get("percent"))).isEqualTo(0.0);
    }

    @Test
    void aZeroPreviousValueLeavesPercentNullRatherThanInfinite() {
        Map<String, Object> current = Map.of("issued", 5L);
        Map<String, Object> previous = Map.of("issued", 0L);

        Map<String, Object> change = ChangeCalculator.changes(current, previous);

        Map<String, Object> issued = (Map<String, Object>) change.get("issued");
        assertThat(number(issued.get("absolute"))).isEqualTo(5.0);
        assertThat(issued.get("percent")).isNull();
    }

    @Test
    void aMetricMissingFromEitherSideIsLeftOutRatherThanFabricated() {
        Map<String, Object> current = Map.of("issued", 5L, "avg_wait_seconds", 30.0);
        Map<String, Object> previous = Map.of("issued", 4L);

        Map<String, Object> change = ChangeCalculator.changes(current, previous);

        assertThat(change).containsOnlyKeys("issued");
    }

    @Test
    void aDecreaseIsANegativeAbsoluteAndPercent() {
        Map<String, Object> current = Map.of("wait", 45.0);
        Map<String, Object> previous = Map.of("wait", 60.0);

        Map<String, Object> change = ChangeCalculator.changes(current, previous);

        Map<String, Object> wait = (Map<String, Object>) change.get("wait");
        assertThat(number(wait.get("absolute"))).isEqualTo(-15.0);
        assertThat(number(wait.get("percent"))).isEqualTo(-25.0);
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }
}
