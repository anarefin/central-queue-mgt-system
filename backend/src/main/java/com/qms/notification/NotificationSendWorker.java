package com.qms.notification;

import com.qms.notification.NotificationChannel.Outcome;
import com.qms.notification.NotificationMessageRepository.MessageRow;
import com.qms.platform.Profiles;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends due messages (FR-NTF-003: this runs off the request thread entirely, driven by {@link NotificationSendScheduler}).
 * A channel with no registered adapter — Web Push and email before tickets 39 and 40 land — fails at once, with no
 * wasted retries, and falls straight to the next channel (FR-NTF-001, FR-NTF-005). A registered adapter that fails
 * retries with exponential backoff up to {@code maxAttemptsPerChannel}, then the message falls to the next channel
 * in its order; once every channel is exhausted (or none of the remaining ones even has a template) it is
 * terminally {@code failed} (FR-NTF-033).
 */
@Component
@Profile(Profiles.SERVING)
class NotificationSendWorker {

    private static final int BATCH_SIZE = 50;

    private final NotificationMessageRepository messages;
    private final NotificationTemplateService templates;
    private final Map<String, NotificationChannel> channelsByKey;
    private final NotificationProperties properties;
    private final Clock clock;

    NotificationSendWorker(NotificationMessageRepository messages, NotificationTemplateService templates, List<NotificationChannel> channels,
            NotificationProperties properties, Clock clock) {
        this.messages = messages;
        this.templates = templates;
        this.channelsByKey = channels.stream().collect(Collectors.toMap(NotificationChannel::key, Function.identity()));
        this.properties = properties;
        this.clock = clock;
    }

    /** One sweep: every due message, each in its own transaction so one failure never blocks the rest. */
    int tick() {
        Instant now = clock.instant();
        List<MessageRow> due = messages.due(now, BATCH_SIZE);
        for (MessageRow message : due) attempt(message, now);
        return due.size();
    }

    @Transactional
    void attempt(MessageRow message, Instant now) {
        NotificationChannel adapter = channelsByKey.get(message.channel());
        Outcome outcome = adapter == null ? Outcome.failure("no_adapter_registered") : adapter.send(message);
        // channelAttemptNo (this channel's own retry count) decides backoff and exhaustion; logAttemptNo is a single
        // increasing count across every channel the message has tried, so the delivery log reads as one timeline
        // even after a fallback (FR-NTF-032).
        int channelAttemptNo = message.attemptCount() + 1;
        int logAttemptNo = messages.attemptCountAcrossChannels(message.id()) + 1;
        messages.insertAttempt(message.id(), logAttemptNo, message.channel(), outcome.success(), outcome.providerResponse(), now);

        if (outcome.success()) {
            messages.markSent(message.id(), now);
            return;
        }

        // A missing adapter never spends retries on a channel that cannot possibly answer differently next time.
        boolean exhausted = adapter == null || channelAttemptNo >= properties.maxAttemptsPerChannel();
        if (!exhausted) {
            messages.retrySameChannel(message.id(), channelAttemptNo, now.plusSeconds(backoffSeconds(channelAttemptNo)));
            return;
        }
        advanceToNextRenderableChannel(message, now);
    }

    /** Falls to the next channel in the order whose template actually renders, skipping any that has none; failed once none is left. */
    private void advanceToNextRenderableChannel(MessageRow message, Instant now) {
        List<String> order = message.channelOrder();
        for (int index = message.channelIndex() + 1; index < order.size(); index++) {
            String candidate = order.get(index);
            var rendered = templates.render(message.triggerKey(), candidate, message.language(), message.variables());
            if (rendered.isPresent()) {
                messages.switchChannel(message.id(), index, candidate, rendered.get().subject(), rendered.get().body(), now);
                return;
            }
            messages.insertAttempt(message.id(), messages.attemptCountAcrossChannels(message.id()) + 1, candidate, false, "no_template", now);
        }
        messages.markFailed(message.id());
    }

    private long backoffSeconds(int attemptNo) {
        return (long) properties.backoffBaseSeconds() * (1L << Math.max(0, attemptNo - 1));
    }
}
