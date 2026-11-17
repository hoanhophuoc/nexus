package com.nexus.server.identity;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SmtpPasswordResetNotifierTest {

    @Test
    void sendsTheTokenWithTheSubjectAndSenderTheMailConfigured() {
        JavaMailSender mail = mock(JavaMailSender.class);
        PasswordResetRepository resets = mock(PasswordResetRepository.class);

        new SmtpPasswordResetNotifier(mail, resets, "no-reply@nexus.test")
                .send("alice@example.com", "raw-token", "token-hash");

        org.mockito.ArgumentCaptor<SimpleMailMessage> sent =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        SimpleMailMessage message = sent.getValue();
        assertThat(message.getFrom()).isEqualTo("no-reply@nexus.test");
        assertThat(message.getTo()).containsExactly("alice@example.com");
        assertThat(message.getSubject()).isEqualTo("Nexus password reset");
        assertThat(message.getText()).contains("raw-token").contains("15 minutes");
        verify(resets, never()).invalidate(any());
    }

    @Test
    void invalidatesTheTokenWhenTheMailServerFails() {
        JavaMailSender mail = mock(JavaMailSender.class);
        PasswordResetRepository resets = mock(PasswordResetRepository.class);
        doThrow(new MailSendException("smtp is down")).when(mail).send(any(SimpleMailMessage.class));

        new SmtpPasswordResetNotifier(mail, resets, "no-reply@nexus.test")
                .send("bob@example.com", "raw-token", "token-hash");

        verify(resets).invalidate("token-hash");
    }
}
