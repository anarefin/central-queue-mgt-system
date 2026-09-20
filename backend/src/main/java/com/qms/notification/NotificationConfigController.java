package com.qms.notification;

import com.qms.platform.Profiles;
import com.qms.platform.security.PublicEndpoint;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Web Push public key a visitor's own browser needs to call {@code pushManager.subscribe()} (ticket 39, RFC
 * 8292). It is not sensitive on its own — it is meant to travel to every subscriber's device — so this is open to
 * anyone, with no ticket credential and no bearer token.
 */
@RestController
@Profile(Profiles.SERVING)
class NotificationConfigController {

    private final VapidKeyStore vapid;

    NotificationConfigController(VapidKeyStore vapid) {
        this.vapid = vapid;
    }

    @PublicEndpoint("The VAPID public key is not sensitive; every visitor's browser needs it before it can subscribe (RFC 8292)")
    @GetMapping("/notification-config/web-push-key")
    public Map<String, String> webPushKey() {
        return Map.of("public_key", vapid.publicKeyBase64Url());
    }
}
