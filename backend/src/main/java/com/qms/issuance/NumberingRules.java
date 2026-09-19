package com.qms.issuance;

import com.qms.issuance.TokenNumbering.ResetBoundary;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** Field rules for a numbering rule (FR-CFG-018). Every failure is {@code validation_failed} naming the field (SRS §20.3). */
final class NumberingRules {

    static final int MAX_PADDING = 6;
    static final long MAX_START = 999_999_999L;
    static final int MAX_SEPARATOR = 8;
    static final int MAX_FIXED_PREFIX = 8;

    private NumberingRules() {}

    static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    /** An empty body is a rule of all defaults. */
    static NumberingSpec parse(NumberingRuleRequest given) {
        NumberingRuleRequest request = given == null ? new NumberingRuleRequest(null, null, null, null, null, null, null) : given;
        String source = request.prefixSource() == null ? NumberingSpec.SERVICE_GROUP : request.prefixSource();
        if (!NumberingSpec.PREFIX_SOURCES.contains(source)) throw invalid("prefix_source", "Pattern");

        String fixed = null;
        if (NumberingSpec.FIXED.equals(source)) {
            fixed = request.fixedPrefix() == null ? "" : request.fixedPrefix().trim();
            if (fixed.isEmpty()) throw invalid("fixed_prefix", "NotBlank");
            if (fixed.length() > MAX_FIXED_PREFIX) throw invalid("fixed_prefix", "Size");
            if (!fixed.matches("[A-Za-z0-9]+")) throw invalid("fixed_prefix", "Pattern");
        }

        long start = request.sequenceStart() == null ? 1 : request.sequenceStart();
        if (start < 0 || start > MAX_START) throw invalid("sequence_start", "Range");

        int padding = request.padding() == null ? TokenNumbering.PADDING : request.padding();
        if (padding < 0 || padding > MAX_PADDING) throw invalid("padding", "Range");

        ResetBoundary boundary = request.resetBoundary() == null ? ResetBoundary.DAILY : ResetBoundary.fromWire(request.resetBoundary());
        if (boundary == null) throw invalid("reset_boundary", "Pattern");

        LocalTime resetTime = LocalTime.MIDNIGHT;
        if (request.resetTime() != null) {
            try {
                resetTime = LocalTime.parse(request.resetTime(), NumberingRuleView.TIME);
            } catch (DateTimeParseException e) {
                throw invalid("reset_time", "Pattern");
            }
        }

        String separator = request.separator() == null ? TokenNumbering.SEPARATOR : request.separator();
        if (separator.length() > MAX_SEPARATOR) throw invalid("separator", "Size");
        if (separator.chars().anyMatch(Character::isISOControl)) throw invalid("separator", "Pattern");

        return new NumberingSpec(source, fixed, start, padding, boundary, resetTime, separator);
    }
}
