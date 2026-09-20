package com.qms.notification;

import com.qms.notification.NotificationMessageRepository.MessageRow;
import com.qms.platform.Profiles;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * The SMTP adapter (ticket 40, FR-INT-040, §14.1): "clients supply an SMTP relay for email" (§27.2), so this only
 * ever talks to the one relay {@link NotificationEmailProperties} configures for this installation — never a
 * third-party email API. A message with no visitor, a visitor with no email on file, or an unconfigured relay all
 * fail cleanly with a specific reason rather than throwing (the same contract every {@link NotificationChannel}
 * keeps), so {@link NotificationSendWorker} retries or falls back to the trigger's next channel exactly as it would
 * for any other failure (FR-NTF-033).
 */
@Component
@Profile(Profiles.SERVING)
class EmailChannel implements NotificationChannel {

    static final String KEY = "email";

    private final JdbcTemplate jdbc;
    private final NotificationEmailProperties properties;
    private final JavaMailSenderImpl sender;

    EmailChannel(JdbcTemplate jdbc, NotificationEmailProperties properties) {
        this.jdbc = jdbc;
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

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Outcome send(MessageRow message) {
        if (sender == null) return Outcome.failure("smtp_not_configured");
        if (message.visitorId() == null) return Outcome.failure("no_visitor");
        String email = jdbc.query("SELECT email FROM visitor WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, message.visitorId());
        if (email == null || email.isBlank()) return Outcome.failure("no_email");

        try {
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, StandardCharsets.UTF_8.name());
            helper.setTo(email);
            helper.setFrom(properties.from());
            helper.setSubject(message.renderedSubject() == null ? "" : message.renderedSubject());
            helper.setText(message.renderedBody() == null ? "" : message.renderedBody(), false);
            sender.send(mime);
            return Outcome.success("delivered");
        } catch (MessagingException | MailException e) {
            return Outcome.failure(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
