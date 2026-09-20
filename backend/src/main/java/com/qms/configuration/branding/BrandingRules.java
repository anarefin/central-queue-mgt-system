package com.qms.configuration.branding;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Field rules for branding and the print template. Every failure is {@code validation_failed} naming the field (SRS §20.3). */
final class BrandingRules {

    private static final int MAX_ORG_NAME = 200;
    /** Generous enough for a small embedded {@code data:} URI logo, not so large a request can blow out the row. */
    private static final int MAX_LOGO_URL = 500_000;
    private static final int MAX_NOTICE_LINE = 500;
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private BrandingRules() {}

    static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    static String orgName(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) throw invalid("org_name", "NotBlank");
        if (trimmed.length() > MAX_ORG_NAME) throw invalid("org_name", "Size");
        return trimmed;
    }

    static String primaryColor(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!HEX_COLOR.matcher(trimmed).matches()) throw invalid("primary_color", "hex_color");
        return trimmed;
    }

    /** Null and blank both mean "no logo"; anything else must at least fit the column's generous cap. */
    static String logoUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > MAX_LOGO_URL) throw invalid("logo_url", "Size");
        return trimmed;
    }

    /** Every entry must be one of {@link PrintField}'s fixed wire values; duplicates collapse, order is kept. */
    static List<String> fields(List<String> given) {
        if (given == null || given.isEmpty()) throw invalid("fields", "NotEmpty");
        Set<String> kept = new LinkedHashSet<>();
        for (String value : given) {
            PrintField field = PrintField.fromWire(value).orElseThrow(() -> invalid("fields", "unknown_field"));
            kept.add(field.wire());
        }
        return List.copyOf(kept);
    }

    static String noticeLine(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > MAX_NOTICE_LINE) throw invalid("notice_line", "Size");
        return trimmed;
    }
}
