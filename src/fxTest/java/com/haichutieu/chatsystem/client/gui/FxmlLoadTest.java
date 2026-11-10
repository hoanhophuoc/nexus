package com.haichutieu.chatsystem.client.gui;

import com.haichutieu.chatsystem.client.gui.adminPanel.AdminLogin;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class FxmlLoadTest {

    @Test
    void shouldLoadLoginViewAndWireController() throws Exception {
        LoginGUI controller = load("/com/haichutieu/chatsystem/client/gui/login.fxml", LoginGUI.class);

        assertThat(field(controller, "username")).isInstanceOf(TextField.class);
        assertThat(field(controller, "password")).isInstanceOf(TextField.class);
        assertThat(field(controller, "screen")).isInstanceOf(HBox.class);

        FxTestSupport.run(() -> {
            controller.displayError("Invalid credentials");
            assertThat(((Text) field(controller, "alertText")).getText()).isEqualTo("Invalid credentials");
            assertThat(((HBox) field(controller, "alert")).isVisible()).isTrue();
        });
    }

    @Test
    void shouldLoadSignupViewAndWireController() throws Exception {
        SignupGUI controller = load("/com/haichutieu/chatsystem/client/gui/signup.fxml", SignupGUI.class);

        assertThat(field(controller, "name")).isInstanceOf(TextField.class);
        assertThat(field(controller, "confirmPassword")).isInstanceOf(TextField.class);
    }

    @Test
    void shouldLoadForgotPasswordViewAndWireController() throws Exception {
        ForgotPasswordGUI controller = load("/com/haichutieu/chatsystem/client/gui/forgotPassword.fxml", ForgotPasswordGUI.class);

        assertThat(field(controller, "email")).isInstanceOf(TextField.class);
    }

    @Test
    void shouldLoadAdminLoginViewAndWireController() throws Exception {
        AdminLogin controller = load("/com/haichutieu/chatsystem/client/gui/adminPanel/adminLogin.fxml", AdminLogin.class);

        assertThat(field(controller, "username")).isInstanceOf(TextField.class);
        assertThat(field(controller, "password")).isInstanceOf(TextField.class);
    }

    @Test
    void shouldValidateLoginFields() {
        LoginGUI controller = FxTestSupport.call(() -> new LoginGUI());

        FxTestSupport.run(() -> {
            assertThat(controller.checkValidUsername("alice1234")).isTrue();
            assertThat(controller.checkValidUsername("short")).isFalse();
            assertThat(controller.checkValidUsername("alice_1234")).isFalse();
            assertThat(controller.checkValidUsername("a".repeat(33))).isFalse();

            assertThat(controller.checkValidPassword("pass1234")).isTrue();
            assertThat(controller.checkValidPassword("password")).isFalse();
            assertThat(controller.checkValidPassword("12345678")).isFalse();
            assertThat(controller.checkValidPassword("short1")).isFalse();
        });
    }

    @Test
    void shouldValidateRegistrationEmail() {
        SignupGUI controller = FxTestSupport.call(() -> new SignupGUI());

        FxTestSupport.run(() -> {
            assertThat(controller.checkValidEmail("alice@example.com")).isTrue();
            assertThat(controller.checkValidEmail("alice@example")).isFalse();
            assertThat(controller.checkValidEmail("alice example.com")).isFalse();
        });
    }

    private static <T> T load(String resource, Class<T> expectedType) throws Exception {
        return FxTestSupport.call(() -> {
            FXMLLoader loader = new FXMLLoader(FxmlLoadTest.class.getResource(resource));
            Parent root = loader.load();
            Object controller = loader.getController();
            assertThat(root).isNotNull();
            assertThat(controller).isInstanceOf(expectedType);
            return expectedType.cast(controller);
        });
    }

    private static Object field(Object controller, String name) throws Exception {
        Field field = controller.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }
}
