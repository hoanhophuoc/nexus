package com.haichutieu.chatsystem.server;

import com.haichutieu.chatsystem.dto.Customer;
import com.haichutieu.chatsystem.integration.PostgresTestBase;
import com.haichutieu.chatsystem.server.dal.CustomerService;
import com.haichutieu.chatsystem.util.PasswordUtil;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Exercises reset-password end to end: TCP request, PostgreSQL update, and a
 * message delivered to a real local SMTP server instead of Gmail.
 */
class PasswordResetIntegrationTest extends PostgresTestBase {

    private static GreenMail greenMail;
    private static SocketServer server;
    private static int port;

    @BeforeAll
    static void startMailAndServer() {
        // Port 0 lets GreenMail choose a free port; tests never depend on 3025.
        greenMail = new GreenMail(new ServerSetup(0, InetAddress.getLoopbackAddress().getHostAddress(), "smtp"));
        greenMail.start();

        PROPERTIES.setProperty("email.host", InetAddress.getLoopbackAddress().getHostAddress());
        PROPERTIES.setProperty("email.port", Integer.toString(greenMail.getSmtp().getPort()));
        PROPERTIES.setProperty("email.starttls", "false");
        PROPERTIES.setProperty("email.auth", "false");
        PROPERTIES.setProperty("email.username", "noreply@example.com");
        PROPERTIES.setProperty("email.password", "unused");

        server = new SocketServer(PROPERTIES, false);
        port = server.getPort();
    }

    @AfterAll
    static void stopServerAndMail() {
        server.close();
        greenMail.stop();
    }

    @BeforeEach
    void clearMail() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
    }

    @Test
    void shouldResetPasswordAndDeliverTheTemporaryPassword() throws Exception {
        Customer customer = createCustomer("resetuser");
        String oldHash = CustomerService.getCustomerByID(customer.getId()).getPassword();

        String response = sendCommand("RESET_PASSWORD NULL " + customer.getEmail());

        assertThat(response).isEqualTo("RESET_PASSWORD Reset password successfully");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(greenMail.getReceivedMessages()).hasSize(1));

        MimeMessage message = greenMail.getReceivedMessages()[0];
        assertThat(((InternetAddress) message.getAllRecipients()[0]).getAddress())
                .isEqualTo(customer.getEmail());
        String body = message.getContent().toString();
        String newPassword = body.substring(body.indexOf(':') + 1).trim();

        assertThat(newPassword).matches("[A-Za-z0-9]{8}");
        String newHash = CustomerService.getCustomerByID(customer.getId()).getPassword();
        assertThat(newHash).isNotEqualTo(oldHash);
        assertThat(PasswordUtil.verify("password123", newHash)).isFalse();
        assertThat(PasswordUtil.verify(newPassword, newHash)).isTrue();
    }

    @Test
    void shouldRejectUnknownEmailWithoutSendingMail() throws Exception {
        String response = sendCommand("RESET_PASSWORD NULL missing@example.com");

        assertThat(response).isEqualTo("RESET_PASSWORD ERROR Email does not exist");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    private String sendCommand(String command) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(15_000);
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write(command + "\n");
                writer.flush();
                return reader.readLine();
            }
        }
    }
}
