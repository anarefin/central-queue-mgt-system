package com.qms.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import org.junit.jupiter.api.Test;

/** The ticket version a console sends back in {@code If-Match} (SRS §20.1, FR-QUE-031). */
class IfMatchTest {

    @Test
    void aMissingHeaderMeansActOnWhateverTheSessionHoldsNow() {
        assertThat(SessionController.version(null)).isNull();
        assertThat(SessionController.version("  ")).isNull();
    }

    @Test
    void theVersionMayBeBareQuotedOrWeak() {
        assertThat(SessionController.version("3")).isEqualTo(3);
        assertThat(SessionController.version("\"3\"")).isEqualTo(3);
        assertThat(SessionController.version("W/\"3\"")).isEqualTo(3);
        assertThat(SessionController.version(" 0 ")).isZero();
    }

    @Test
    void anythingElseIsAValidationFailureNamingTheHeader() {
        for (String bad : new String[] {"abc", "-1", "\"\"", "1.5", "*"}) {
            assertThatThrownBy(() -> SessionController.version(bad))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).as(bad).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }
}
