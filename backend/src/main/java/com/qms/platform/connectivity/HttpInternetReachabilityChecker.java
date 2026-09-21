package com.qms.platform.connectivity;

import com.qms.platform.Profiles;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The real reachability check (ticket 44): a plain HTTP HEAD with a short timeout, following no redirects — this
 * only needs to know the request left the Site and something on the internet answered, not what it said, so even a
 * 4xx/5xx response from the target counts as reachable; only a timeout, refused connection or DNS failure does not.
 */
@Component
@Profile(Profiles.SERVING)
class HttpInternetReachabilityChecker implements InternetReachabilityChecker {

    private final HttpClient http;
    private final InternetConnectivityProperties properties;

    HttpInternetReachabilityChecker(InternetConnectivityProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.timeout())
                .build();
    }

    @Override
    public boolean reachable(URI target) {
        try {
            HttpRequest request = HttpRequest.newBuilder(target)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(properties.timeout())
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (IOException networkFailure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
