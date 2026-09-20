package com.qms.notification;

import com.qms.notification.NotificationTemplateRepository.TemplateRow;
import com.qms.notification.NotificationTemplateService.Rendered;
import com.qms.notification.NotificationTriggerConfigService.EffectiveSetting;
import com.qms.platform.security.CurrentUser;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin surface for the notification pipeline (ticket 38): the trigger catalogue and its per-Site/Service settings
 * (FR-NTF-010), templates with their preview (FR-NTF-020, FR-NTF-021) and the delivery log (FR-NTF-032). Permission
 * is checked in each service method (FR-CFG-103).
 */
@RestController
class NotificationAdminController {

    private final NotificationTriggerConfigService triggerConfig;
    private final NotificationTemplateService templates;
    private final NotificationLogService log;
    private final CurrentUser currentUser;

    NotificationAdminController(NotificationTriggerConfigService triggerConfig, NotificationTemplateService templates, NotificationLogService log, CurrentUser currentUser) {
        this.triggerConfig = triggerConfig;
        this.templates = templates;
        this.log = log;
        this.currentUser = currentUser;
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)")
    @GetMapping("/notification-triggers/catalogue")
    Items<TriggerCatalogueEntry> catalogue() {
        return new Items<>(Arrays.stream(NotificationTriggerKey.values()).map(TriggerCatalogueEntry::of).toList());
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)")
    @GetMapping("/notification-triggers")
    Items<EffectiveSetting> triggers(@RequestParam(name = "site_id") UUID siteId, @RequestParam(name = "service_id", required = false) UUID serviceId) {
        return new Items<>(serviceId == null ? triggerConfig.listForSite(siteId) : triggerConfig.listForService(siteId, serviceId));
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)")
    @PutMapping("/notification-triggers/{triggerKey}")
    EffectiveSetting setTrigger(
            @PathVariable String triggerKey,
            @RequestParam(name = "site_id") UUID siteId,
            @RequestParam(name = "service_id", required = false) UUID serviceId,
            @RequestBody TriggerSettingInput input) {
        UUID actorId = currentUser.require().userId();
        return serviceId == null
                ? triggerConfig.setForSite(siteId, triggerKey, input.enabled(), input.channelOrder(), actorId)
                : triggerConfig.setForService(siteId, serviceId, triggerKey, input.enabled(), input.channelOrder(), actorId);
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)")
    @GetMapping("/notification-templates/{triggerKey}")
    Items<TemplateRow> forTrigger(@PathVariable String triggerKey) {
        return new Items<>(templates.forTrigger(triggerKey));
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)")
    @PutMapping("/notification-templates/{triggerKey}/{channel}/{language}")
    TemplateRow save(@PathVariable String triggerKey, @PathVariable String channel, @PathVariable String language, @RequestBody TemplateInput input) {
        UUID actorId = currentUser.require().userId();
        return templates.save(triggerKey, channel, language, input.subject(), input.body(), actorId);
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)")
    @GetMapping("/notification-templates/{triggerKey}/{channel}/{language}/preview")
    Rendered preview(@PathVariable String triggerKey, @PathVariable String channel, @PathVariable String language) {
        return templates.preview(triggerKey, channel, language);
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).AUDIT_READ)")
    @GetMapping("/notification-messages")
    Items<MessageWithAttempts> messages(
            @RequestParam(name = "ticket_id", required = false) UUID ticketId,
            @RequestParam(name = "visitor_id", required = false) UUID visitorId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit) {
        return new Items<>(log.search(ticketId, visitorId, status, limit));
    }
}
