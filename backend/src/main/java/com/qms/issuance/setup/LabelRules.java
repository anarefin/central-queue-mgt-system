package com.qms.issuance.setup;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.util.List;
import java.util.Map;

/** Field rules for a label override value (SRS §3.2, ticket 69): required, at most 60 characters. */
final class LabelRules {

    private static final int MAX_VALUE = 60;

    private LabelRules() {}

    static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    static String value(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) throw invalid("value", "NotBlank");
        if (trimmed.length() > MAX_VALUE) throw invalid("value", "Size");
        return trimmed;
    }
}
