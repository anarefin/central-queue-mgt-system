package com.qms.device;

import com.qms.platform.Profiles;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Fleet administration: issuing pairing codes, the central health view, revoking a device and pushing a reload or
 * configuration update (FR-OPS-041, FR-OPS-042). Every method needs {@code config:org_sites_zones}. */
@RestController
@Profile(Profiles.SERVING)
public class DeviceFleetController {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";

    private final DeviceService service;

    DeviceFleetController(DeviceService service) {
        this.service = service;
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/devices/pairing-codes")
    @ResponseStatus(HttpStatus.CREATED)
    public PairingCodeResponse createPairingCode(@RequestBody CreatePairingCodeRequest request) {
        return service.createPairingCode(request.kind(), request.siteId(), request.zoneId(), request.label());
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/devices")
    public Items<DeviceView> list() {
        return new Items<>(service.devices());
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/devices/{id}")
    public DeviceView get(@PathVariable UUID id) {
        return service.device(id);
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/devices/{id}/revoke")
    public DeviceView revoke(@PathVariable UUID id) {
        return service.revoke(id);
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/devices/{id}/commands")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void command(@PathVariable UUID id, @RequestBody CommandRequest request) {
        service.command(id, request.command());
    }

    @PreAuthorize(PERMISSION)
    @PutMapping("/devices/{id}/display-config")
    public DisplayConfigResponse updateDisplayConfig(@PathVariable UUID id, @RequestBody DisplayConfigRequest request) {
        return service.updateDisplayConfig(id, request);
    }
}
