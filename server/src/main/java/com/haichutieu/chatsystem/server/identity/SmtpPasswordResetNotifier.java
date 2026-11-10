package com.haichutieu.chatsystem.server.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class SmtpPasswordResetNotifier implements PasswordResetNotifier {

    private final JavaMailSender mailSender;
    private final PasswordResetRepository resets;
    private final String from;
    private static final System.Logger LOG = System.getLogger(SmtpPasswordResetNotifier.class.getName());

    public SmtpPasswordResetNotifier(JavaMailSender mailSender, PasswordResetRepository resets,
                                     @Value("${chat.mail.from:no-reply@localhost}") String from) {
        this.mailSender = mailSender;
        this.resets = resets;
        this.from = from;
    }

    @Override
    @Async
    public void send(String email, String rawToken, String tokenHash) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Chat System password reset");
        message.setText("Use this single-use password reset token within 15 minutes:\n\n" + rawToken);
        try {
            mailSender.send(message);
        } catch (RuntimeException exception) {
            resets.invalidate(tokenHash);
            LOG.log(System.Logger.Level.WARNING, "Password reset email delivery failed; token invalidated.");
        }
    }
}
