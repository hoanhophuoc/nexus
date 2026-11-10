package com.haichutieu.chatsystem.desktop.admin;

import com.haichutieu.chatsystem.desktop.shared.SessionApi;
import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import com.haichutieu.chatsystem.desktop.shared.ui.ThemeManager;
import com.haichutieu.chatsystem.sdk.model.Login;
import com.haichutieu.chatsystem.sdk.model.MyProfile;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * One signed-in admin composition root. It owns the session and the single background
 * executor; no mutable session or executor is stored in a static field.
 */
final class AdminAppContext implements AutoCloseable {
    private final Stage stage;
    private final String origin;
    private final ThemeManager themeManager;
    private final I18n i18n;
    private final ExecutorService network = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("chat-admin-io-", 0).factory());

    private SessionApi session;
    private MyProfile profile;

    AdminAppContext(Stage stage, String origin, ThemeManager themeManager, I18n i18n) {
        this.stage = stage;
        this.origin = origin;
        this.themeManager = themeManager;
        this.i18n = i18n;
    }

    SignedIn signIn(String username, char[] password) throws Exception {
        SessionApi candidate = new SessionApi(URI.create(origin));
        try {
            candidate.api().getCsrfToken();
            Login credentials = new Login().username(username).password(new String(password));
            MyProfile signedIn = candidate.api().login(credentials);
            credentials.setPassword(null);
            // Keep the original admin guard: a normal user may authenticate but must not
            // reach the admin shell.
            candidate.api().adminListUsers(null, null, "name", "asc", null, 1);
            return new SignedIn(candidate, signedIn);
        } catch (Exception exception) {
            try {
                candidate.api().logout();
            } catch (Exception ignored) {
                // The candidate session is discarded either way.
            }
            candidate.clear();
            throw exception;
        }
    }

    /** Runs on the JavaFX thread after a successful background sign-in. */
    void activate(SignedIn signedIn) {
        this.session = signedIn.session();
        this.profile = signedIn.profile();
    }

    void signOut(Runnable onSignedOut) {
        SessionApi previous = session;
        session = null;
        profile = null;
        if (previous == null) {
            Platform.runLater(onSignedOut);
            return;
        }
        network.execute(() -> {
            try {
                previous.api().logout();
            } catch (Exception ignored) {
                // The local session is cleared regardless.
            } finally {
                previous.clear();
                Platform.runLater(onSignedOut);
            }
        });
    }

    <T> void runAsync(Callable<T> work, Consumer<T> success, Consumer<Exception> failure) {
        try {
            network.execute(() -> {
                try {
                    T value = work.call();
                    Platform.runLater(() -> success.accept(value));
                } catch (Exception exception) {
                    Platform.runLater(() -> failure.accept(exception));
                }
            });
        } catch (RejectedExecutionException closed) {
            // The composition root is closing; no callback should reach a disposed scene.
        }
    }

    String administratorName() {
        if (profile == null) return "";
        if (profile.getFullName() != null && !profile.getFullName().isBlank()) {
            return profile.getFullName();
        }
        return profile.getUsername();
    }

    Stage stage() { return stage; }
    ThemeManager themeManager() { return themeManager; }
    I18n i18n() { return i18n; }
    SessionApi session() { return session; }
    MyProfile profile() { return profile; }
    ExecutorService network() { return network; }

    @Override
    public void close() {
        if (session != null) {
            session.clear();
            session = null;
        }
        network.shutdownNow();
    }

    record SignedIn(SessionApi session, MyProfile profile) {
    }
}
