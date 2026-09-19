package com.qms.platform;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Every REST controller lives under {@code /api/v1} (SRS §20.1). Breaking changes need a new prefix, not a new flag. */
@Configuration
public class ApiPathConfig implements WebMvcConfigurer {

    public static final String BASE_PATH = "/api/v1";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(BASE_PATH, HandlerTypePredicate.forAnnotation(RestController.class));
    }
}
