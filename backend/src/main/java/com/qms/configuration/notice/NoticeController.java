package com.qms.configuration.notice;

import com.qms.configuration.site.Items;
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

/** Notice-board content (ticket 30, FR-DSP-006). Permissions are also enforced in {@link NoticeService} (API-016). */
@RestController
@Profile(Profiles.SERVING)
public class NoticeController {

    private final NoticeService notices;

    NoticeController(NoticeService notices) {
        this.notices = notices;
    }

    @PreAuthorize(NoticeService.MANAGE)
    @GetMapping("/zones/{zoneId}/notices")
    public Items<Notice> forZone(@PathVariable UUID zoneId) {
        return new Items<>(notices.forZone(zoneId));
    }

    @PreAuthorize(NoticeService.MANAGE)
    @PostMapping("/notices")
    @ResponseStatus(HttpStatus.CREATED)
    public Notice create(@RequestBody NoticeRequest request) {
        return notices.create(request);
    }

    @PreAuthorize(NoticeService.MANAGE)
    @PutMapping("/notices/{id}")
    public Notice replace(@PathVariable UUID id, @RequestBody NoticeRequest request) {
        return notices.replace(id, request);
    }

    @PreAuthorize(NoticeService.MANAGE)
    @PostMapping("/notices/{id}/deactivate")
    public Notice deactivate(@PathVariable UUID id) {
        return notices.deactivate(id);
    }

    @PreAuthorize(NoticeService.MANAGE)
    @PostMapping("/notices/{id}/activate")
    public Notice activate(@PathVariable UUID id) {
        return notices.activate(id);
    }
}
