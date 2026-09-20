package com.qms.mobile;

import com.qms.notification.NotificationEmailProperties;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * The SMTP send of an OTP email (ticket 41, FR-MOB-001), against the one relay {@link NotificationEmailProperties}
 * already configures for this installation (ticket 40, §27.2) — never a third-party email API, and never the
 * consent-gated notification pipeline {@code com.qms.notification}'s trigger/template system runs on, since a
 * sign-in code is not an opt-outable notification and a visitor's own consent choice must never block their own
 * login. Unlike that pipeline's own {@code EmailChannel} (which fails a queued send cleanly for a worker to retry),
 * this call is synchronous inside the HTTP request that asked for the code, so a delivery failure here is reported
 * to the caller at once as {@link ErrorCode#UNAVAILABLE}.
 */
@Component
@Profile(Profiles.SERVING)
class SmtpVisitorOtpMailer implements VisitorOtpMailer {

    private final NotificationEmailProperties properties;
    private final JavaMailSenderImpl sender;

    SmtpVisitorOtpMailer(NotificationEmailProperties properties) {
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
    public void sendCode(String email, String code, Duration validFor) {
        if (sender == null) throw new ApiException(ErrorCode.UNAVAILABLE, Map.of("reason", "smtp_not_configured"));
        try {
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, StandardCharsets.UTF_8.name());
            helper.setTo(email);
            helper.setFrom(properties.from());
            helper.setSubject("Your sign-in code");
            helper.setText("Your one-time code is " + code + ". It expires in " + Math.max(1, validFor.toMinutes())
                    + " minute(s). If you did not request this, you can ignore this email.", false);
            sender.send(mime);
        } catch (MessagingException | MailException e) {
            throw new ApiException(ErrorCode.UNAVAILABLE, Map.of("reason", "otp_delivery_failed"));
        }
    }
}
