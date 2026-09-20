package com.qms.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Shared scaffolding for a test that needs a real, working visitor session: a fake mailer standing in for SMTP
 * (never a real socket — this ticket's own {@code VisitorAuthIT} already proves the OTP flow end to end; other
 * suites just need a token), and a helper that mints one by going through the real HTTP endpoints, the same
 * "through the actual API, not a shortcut" shape {@code AppointmentBookingIT}'s own {@code token(Role, sites...)}
 * helper already is for staff.
 */
public final class VisitorAuthTestSupport {

    private VisitorAuthTestSupport() {}

    @TestConfiguration
    public static class Fakes {
        @Bean
        @Primary
        CapturingVisitorOtpMailer fakeVisitorOtpMailer() {
            return new CapturingVisitorOtpMailer();
        }
    }

    /** Requests and verifies an OTP for {@code email} through the real endpoints, returning the visitor's access token. */
    public static String mintAccessToken(MockMvc mvc, CapturingVisitorOtpMailer mailer, String email) throws Exception {
        MvcResult requested = mvc.perform(post("/api/v1/auth/visitor/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andReturn();
        assertThat(requested.getResponse().getStatus()).as(requested.getResponse().getContentAsString()).isEqualTo(204);
        String code = mailer.lastCodeFor(email);
        assertThat(code).as("an OTP must have been sent to " + email).isNotNull();

        MvcResult verified = mvc.perform(post("/api/v1/auth/visitor/otp/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andReturn();
        assertThat(verified.getResponse().getStatus()).as(verified.getResponse().getContentAsString()).isEqualTo(200);
        return JsonPath.read(verified.getResponse().getContentAsString(), "$.access_token");
    }
}
