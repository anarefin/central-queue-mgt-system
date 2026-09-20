package com.qms.device;

import com.qms.platform.Profiles;
import com.qms.platform.security.PublicEndpoint;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a device itself calls: pairing, silent refresh, heartbeat and its bootstrap configuration (SRS §20.2, §20.4).
 * Both tokens travel in the response body (API-017); there is no refresh cookie, because a kiosk/display shell is
 * not a browser.
 */
@RestController
@Profile(Profiles.SERVING)
public class DeviceController {

    private final DeviceService service;

    DeviceController(DeviceService service) {
        this.service = service;
    }

    @PublicEndpoint("A device has no credential yet; it presents the pairing code instead (FR-OPS-011)")
    @PostMapping("/devices/pair")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceSessionResponse pair(@RequestBody PairRequest request) {
        return DeviceSessionResponse.from(service.pair(request.code()));
    }

    @PublicEndpoint("Authenticated by the refresh token in the body, not a bearer token")
    @PostMapping("/devices/refresh")
    public DeviceSessionResponse refresh(@RequestBody DeviceRefreshRequest request) {
        return DeviceSessionResponse.from(service.refresh(request.refreshToken()));
    }

    @PreAuthorize("hasAnyRole('KIOSK','DISPLAY')")
    @PostMapping("/devices/{id}/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat(@PathVariable UUID id, @RequestBody HeartbeatRequest request) {
        service.heartbeat(id, request.appVersion());
    }

    @PreAuthorize("hasAnyRole('KIOSK','DISPLAY')")
    @GetMapping("/config/bootstrap")
    public BootstrapResponse bootstrap() {
        return service.bootstrap();
    }

    @PreAuthorize("hasRole('DISPLAY')")
    @GetMapping("/devices/{id}/display-state")
    public DisplayStateResponse displayState(@PathVariable UUID id) {
        return service.displayState(id);
    }
}
