package com.qms.reporting;

import com.qms.notification.NotificationEmailProperties;
import com.qms.platform.Profiles;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Emails a scheduled report's rendered file to one recipient (ticket 52, FR-RPT-005), over the same SMTP relay
 * {@link NotificationEmailProperties} already configures for this installation (ticket 40, §27.2) — a direct send,
 * the same shape {@code com.qms.mobile.SmtpVisitorOtpMailer} already uses, never through the consent-gated {@code
 * com.qms.notification} pipeline: a schedule's own recipient list is an admin's own configuration choice, not a
 * visitor's opt-in preference, so there is nothing there for a visitor to consent to or opt out of.
 */
@Component
@Profile(Profiles.SERVING)
class ScheduledReportMailer {

    sealed interface Outcome {
        record Sent(String providerResponse) implements Outcome {}

        record Failed(String reason) implements Outcome {}
    }

    private final NotificationEmailProperties properties;
    private final JavaMailSenderImpl sender;

    ScheduledReportMailer(NotificationEmailProperties properties) {
        this.properties = properties;
        this.sender = properties.host().isBlank() ? null : buildSender(properties);
    }

    private static JavaMailSenderImpl buildSender(NotificationEmailProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.host());
        sender.setPort(properties.port());
        sender.setProtocol("smtp");
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        boolean authenticated = properties.username() != null && !properties.username().isBlank();
        if (authenticated) {
            sender.setUsername(properties.username());
            sender.setPassword(properties.password());
        }
        Properties javaMailProperties = new Properties();
        javaMailProperties.put("mail.smtp.auth", authenticated);
        javaMailProperties.put("mail.smtp.starttls.enable", properties.starttls());
        javaMailProperties.put("mail.smtp.connectiontimeout", properties.connectionTimeoutMillis());
        javaMailProperties.put("mail.smtp.timeout", properties.connectionTimeoutMillis());
        javaMailProperties.put("mail.smtp.writetimeout", properties.connectionTimeoutMillis());
        sender.setJavaMailProperties(javaMailProperties);
        return sender;
    }

    Outcome send(String recipient, String subject, String body, byte[] attachment, String attachmentFilename, String attachmentContentType) {
        if (sender == null) return new Outcome.Failed("smtp_not_configured");
        try {
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setTo(recipient);
            helper.setFrom(properties.from());
            helper.setSubject(subject);
            helper.setText(body, false);
            helper.addAttachment(attachmentFilename, new ByteArrayResource(attachment), attachmentContentType);
            sender.send(mime);
            return new Outcome.Sent("delivered");
        } catch (MessagingException | MailException e) {
            return new Outcome.Failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
