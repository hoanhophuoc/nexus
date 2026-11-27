package com.nexus.desktop.admin;

import com.nexus.desktop.shared.testing.FxWindow;
import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.Theme;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.util.Locale;
import java.util.function.Supplier;

import static com.nexus.desktop.shared.testing.FxThread.onFx;

/**
 * The admin window and composition root the FX suites mount a screen in.
 *
 * <p>It lives here rather than in {@code fx-test-support} because {@code AdminAppContext} and
 * {@code AdminViewLoader} are package-private: the generic window lifecycle is shared from the
 * support module, and only this admin-specific wiring stays in the admin suite.</p>
 */
final class AdminSessions {
    private static final String BASE = "com.nexus.desktop.admin.i18n.messages";

    private AdminSessions() {
    }

    static Session open(String origin) {
        FxWindow window = FxWindow.open();
        I18n english = new I18n(BASE, Locale.ENGLISH);
        AdminAppContext context = onFx(() ->
                new AdminAppContext(window.stage(), origin, window.theme(), english));
        window.theme(Theme.LIGHT);
        return new Session(window, context);
    }

    static Session signedIn(FakeAdminServer server) throws Exception {
        Session session = open(server.origin().toString());
        // Three blocking HTTP calls; the sign-in stays off the FX thread like the app does it.
        AdminAppContext.SignedIn signedIn =
                session.context.signIn("admin", "correct horse battery staple".toCharArray());
        onFx(() -> {
            session.context.activate(signedIn);
            return true;
        });
        return session;
    }

    /** One admin window with its composition root, mounting one screen at a time. */
    record Session(FxWindow window, AdminAppContext context) implements AutoCloseable {
        Scene scene() {
            return window.scene();
        }

        Parent show(String resource, Supplier<Object> controller, double width, double height) {
            Parent root = onFx(() -> AdminViewLoader.load(resource, controller.get(), context.i18n()));
            return window.show(root, width, height, AdminViewLoader::applyStylesheet);
        }

        @Override
        public void close() {
            onFx(() -> {
                context.close();
                return true;
            });
            window.close();
        }
    }
}
