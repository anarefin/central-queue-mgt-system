package com.qms.configuration.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Branding and print-template fields without a database (FR-CFG-030, FR-CFG-031). */
class BrandingRulesTest {

    @Test
    void orgNameMustBePresentAndWithinLength() {
        assertThat(BrandingRules.orgName("  Main Campus  ")).isEqualTo("Main Campus");
        assertThatThrownBy(() -> BrandingRules.orgName(null)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> BrandingRules.orgName("   ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> BrandingRules.orgName("x".repeat(201))).isInstanceOf(ApiException.class);
        assertThat(BrandingRules.orgName("x".repeat(200))).hasSize(200);
    }

    @Test
    void primaryColorMustBeAHexTriplet() {
        assertThat(BrandingRules.primaryColor("#0b5FFa")).isEqualTo("#0b5FFa");
        for (String bad : new String[] {null, "", "0b5fff", "#0b5ff", "#0b5fffx", "red"}) {
            assertThatThrownBy(() -> BrandingRules.primaryColor(bad)).isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }

    @Test
    void logoUrlIsOptionalAndBlankMeansNoLogo() {
        assertThat(BrandingRules.logoUrl(null)).isNull();
        assertThat(BrandingRules.logoUrl("   ")).isNull();
        assertThat(BrandingRules.logoUrl(" https://example.org/logo.png ")).isEqualTo("https://example.org/logo.png");
        assertThatThrownBy(() -> BrandingRules.logoUrl("x".repeat(500_001))).isInstanceOf(ApiException.class);
    }

    @Test
    void fieldsMustBeNonEmptyKnownAndDedupedInOrder() {
        assertThatThrownBy(() -> BrandingRules.fields(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> BrandingRules.fields(List.of())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> BrandingRules.fields(List.of("token_number", "carrier_pigeon"))).isInstanceOf(ApiException.class);
        assertThat(BrandingRules.fields(List.of("token_number", "floor", "token_number"))).containsExactly("token_number", "floor");
    }

    @Test
    void everyFixedFieldFromFrCfg031IsAcceptedByItsWireValue() {
        List<String> all = Arrays.asList(
                "token_number", "building", "floor", "service_group", "service", "visitor_code", "visitor_name", "visitor_category",
                "counter", "issue_time", "estimated_wait", "qr_code", "notice_line");
        assertThat(BrandingRules.fields(all)).containsExactlyElementsOf(all);
    }

    @Test
    void noticeLineIsOptionalAndWithinLength() {
        assertThat(BrandingRules.noticeLine(null)).isNull();
        assertThat(BrandingRules.noticeLine("  ")).isNull();
        assertThat(BrandingRules.noticeLine(" Please have your ID ready ")).isEqualTo("Please have your ID ready");
        assertThatThrownBy(() -> BrandingRules.noticeLine("x".repeat(501))).isInstanceOf(ApiException.class);
    }
}
