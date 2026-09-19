package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.issuance.TokenNumbering.ResetBoundary;
import com.qms.platform.ApiException;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Field rules of a numbering rule (FR-CFG-018): defaults, ranges and the field named in every refusal. */
class NumberingRulesTest {

    private static NumberingRuleRequest request(String source, String fixed, Long start, Integer padding, String boundary, String time, String separator) {
        return new NumberingRuleRequest(source, fixed, start, padding, boundary, time, separator);
    }

    @SuppressWarnings("unchecked")
    private static String refusedField(NumberingRuleRequest request) {
        try {
            NumberingRules.parse(request);
            return null;
        } catch (ApiException e) {
            return (String) ((Map<String, Object>) ((List<Object>) e.details().get("fields")).getFirst()).get("field");
        }
    }

    @Test
    void anEmptyRuleTakesTheDefaultsOfTheSrs() {
        NumberingSpec spec = NumberingRules.parse(request(null, null, null, null, null, null, null));

        assertThat(spec.prefixSource()).as("prefix source defaults to the service group").isEqualTo("service_group");
        assertThat(spec.start()).isEqualTo(1);
        assertThat(spec.padding()).isEqualTo(3);
        assertThat(spec.boundary()).isEqualTo(ResetBoundary.DAILY);
        assertThat(spec.resetTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(spec.separator()).isEqualTo("-");
        assertThat(NumberingRules.parse(null)).isEqualTo(spec);
    }

    @Test
    void everyParameterCanBeSet() {
        NumberingSpec spec = NumberingRules.parse(request("fixed", " VIP ", 100L, 6, "monthly", "04:30", ""));

        assertThat(spec.prefixSource()).isEqualTo("fixed");
        assertThat(spec.fixedPrefix()).isEqualTo("VIP");
        assertThat(spec.start()).isEqualTo(100);
        assertThat(spec.padding()).isEqualTo(6);
        assertThat(spec.boundary()).isEqualTo(ResetBoundary.MONTHLY);
        assertThat(spec.resetTime()).isEqualTo(LocalTime.of(4, 30));
        assertThat(spec.separator()).as("the separator may be empty").isEmpty();
        for (String source : List.of("service", "service_group", "priority_class")) {
            assertThat(NumberingRules.parse(request(source, "ignored", null, null, null, null, null)).fixedPrefix()).as(source).isNull();
        }
        for (String boundary : List.of("daily", "weekly", "monthly", "never")) {
            assertThat(NumberingRules.parse(request(null, null, null, null, boundary, null, null)).boundary().wire()).isEqualTo(boundary);
        }
    }

    @Test
    void anInvalidParameterIsRefusedNamingTheField() {
        assertThat(refusedField(request("colour", null, null, null, null, null, null))).isEqualTo("prefix_source");
        assertThat(refusedField(request("fixed", null, null, null, null, null, null))).as("fixed needs a prefix").isEqualTo("fixed_prefix");
        assertThat(refusedField(request("fixed", "TOO-LONG-PREFIX", null, null, null, null, null))).isEqualTo("fixed_prefix");
        assertThat(refusedField(request("fixed", "a b", null, null, null, null, null))).isEqualTo("fixed_prefix");
        assertThat(refusedField(request(null, null, -1L, null, null, null, null))).isEqualTo("sequence_start");
        assertThat(refusedField(request(null, null, 1_000_000_000L, null, null, null, null))).isEqualTo("sequence_start");
        assertThat(refusedField(request(null, null, null, -1, null, null, null))).isEqualTo("padding");
        assertThat(refusedField(request(null, null, null, 7, null, null, null))).as("padding is 0 to 6").isEqualTo("padding");
        assertThat(refusedField(request(null, null, null, null, "yearly", null, null))).isEqualTo("reset_boundary");
        assertThat(refusedField(request(null, null, null, null, null, "24:00", null))).isEqualTo("reset_time");
        assertThat(refusedField(request(null, null, null, null, null, "4pm", null))).isEqualTo("reset_time");
        assertThat(refusedField(request(null, null, null, null, null, null, "123456789"))).isEqualTo("separator");
        assertThat(refusedField(request(null, null, null, null, null, null, "a\nb"))).isEqualTo("separator");
        assertThatThrownBy(() -> NumberingRules.parse(request("colour", null, null, null, null, null, null))).isInstanceOf(ApiException.class);
    }
}
