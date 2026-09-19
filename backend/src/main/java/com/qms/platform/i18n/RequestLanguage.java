package com.qms.platform.i18n;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/** The language to answer a request in. The device setting is the browser's {@code Accept-Language}. */
@Component
public class RequestLanguage {

    private final LanguageResolver resolver;
    private final ObjectProvider<UserLanguagePreference> userPreference;

    public RequestLanguage(LanguageResolver resolver, ObjectProvider<UserLanguagePreference> userPreference) {
        this.resolver = resolver;
        this.userPreference = userPreference;
    }

    public String current(HttpServletRequest request) {
        String device = resolver.fromAcceptLanguage(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE)).orElse(null);
        String user = userPreference.getIfAvailable(() -> Optional::empty).current().orElse(null);
        return resolver.resolve(user, device);
    }
}
