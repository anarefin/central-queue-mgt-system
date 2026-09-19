package com.qms.platform;

import com.qms.platform.i18n.ConfiguredSiteDefaultLanguage;
import com.qms.platform.i18n.LanguageProperties;
import com.qms.platform.i18n.LanguageResolver;
import com.qms.platform.i18n.Messages;
import com.qms.platform.i18n.RequestLanguage;
import com.qms.platform.security.AuthorizationDenials;
import com.qms.platform.security.CurrentUser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The platform beans a {@code @WebMvcTest} slice does not scan, so slice tests exercise the real error pipeline.
 * Authentication is opened up here on purpose: these tests are about the error envelope, not access control, which
 * the integration tests cover against the real filter chain.
 */
@TestConfiguration
@EnableConfigurationProperties(LanguageProperties.class)
@Import({
    GlobalExceptionHandler.class,
    ErrorEnvelopeFactory.class,
    Messages.class,
    LanguageResolver.class,
    ConfiguredSiteDefaultLanguage.class,
    RequestLanguage.class,
    TraceIdFilter.class,
    ApiPathConfig.class,
    AuthorizationDenials.class,
    CurrentUser.class
})
public class WebSliceTestConfig {

    @Bean
    SecurityFilterChain openForSliceTests(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }
}
