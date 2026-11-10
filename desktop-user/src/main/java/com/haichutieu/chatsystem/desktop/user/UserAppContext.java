package com.haichutieu.chatsystem.desktop.user;

import com.haichutieu.chatsystem.desktop.shared.SessionApi;
import com.haichutieu.chatsystem.desktop.shared.cache.CacheKeyDerivation;
import com.haichutieu.chatsystem.desktop.shared.cache.EncryptedMessageCache;
import com.haichutieu.chatsystem.desktop.shared.messaging.MessageHistorySyncService;
import com.haichutieu.chatsystem.desktop.shared.messaging.MlsGroupSyncService;
import com.haichutieu.chatsystem.desktop.shared.messaging.RealtimeConnection;
import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import com.haichutieu.chatsystem.desktop.shared.ui.ThemeManager;
import com.haichutieu.chatsystem.sdk.model.Login;
import com.haichutieu.chatsystem.sdk.model.MyProfile;
import com.haichutieu.chatsystem.sdk.model.ProfileUpdate;
import com.haichutieu.chatsystem.sdk.model.Registration;
import com.haichutieu.chatsystem.sdk.model.ConfirmPasswordResetRequest;
import com.haichutieu.chatsystem.sdk.model.RequestPasswordResetRequest;
import javafx.application.Platform;
import javafx.stage.Stage;

import javax.crypto.SecretKey;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * One signed-in desktop composition root. It owns the session, local cache, MLS state and
 * network executor for a single account; no mutable session lives in a static field.
 */
final class UserAppContext implements AutoCloseable {
    private final Stage stage;
    private final String origin;
    private final boolean realtimeEnabled;
    private final URI webSocketUriOverride;
    private final ThemeManager themeManager;
    private final I18n i18n;
    private final ExecutorService network = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("chat-user-io-", 0).factory());

    private SessionApi session;
    private MyProfile profile;
    private EncryptedMessageCache cache;
    private MessageHistorySyncService messaging;
    private MlsGroupSyncService groups;
    private RealtimeConnection realtime;
    private UUID userId;

    UserAppContext(Stage stage, String origin, boolean realtimeEnabled, URI webSocketUriOverride,
                   ThemeManager themeManager, I18n i18n) {
        this.stage = stage;
        this.origin = origin;
        this.realtimeEnabled = realtimeEnabled;
        this.webSocketUriOverride = webSocketUriOverride;
        this.themeManager = themeManager;
        this.i18n = i18n;
    }

    SignedIn signIn(String username, char[] password) throws Exception {
        SessionApi next = new SessionApi(URI.create(origin), webSocketUriOverride);
        EncryptedMessageCache nextCache = null;
        try {
            next.api().getCsrfToken();
            Login credentials = new Login().username(username).password(new String(password));
            MyProfile signedIn = next.api().login(credentials);
            credentials.setPassword(null);
            SecretKey key = CacheKeyDerivation.derive(password, signedIn.getId());
            Path accountDir = Path.of(System.getProperty("user.home"), ".chat-system",
                    signedIn.getId().toString());
            Files.createDirectories(accountDir);
            nextCache = new EncryptedMessageCache(accountDir.resolve("history.bin"),
                    signedIn.getId(), key);
            MlsGroupSyncService nextGroups = new MlsGroupSyncService(next.api(), nextCache,
                    accountDir.resolve("mls-state.bin"), signedIn.getId(), key);
            return new SignedIn(next, signedIn, nextCache, nextGroups);
        } catch (Exception exception) {
            if (nextCache != null) {
                try { nextCache.close(); } catch (Exception ignored) { }
            }
            try { next.api().logout(); } catch (Exception ignored) { }
            next.clear();
            throw exception;
        }
    }

    /** Runs on the JavaFX thread after a successful background sign-in. */
    void activate(SignedIn signedIn) {
        this.session = signedIn.session();
        this.profile = signedIn.profile();
        this.cache = signedIn.cache();
        this.groups = signedIn.groups();
        this.userId = signedIn.profile().getId();
        this.messaging = new MessageHistorySyncService(session.api(), cache);
    }

    MyProfile register(Registration registration) throws Exception {
        SessionApi publicSession = new SessionApi(URI.create(origin), webSocketUriOverride);
        try {
            publicSession.api().getCsrfToken();
            return publicSession.api().register(registration);
        } finally {
            publicSession.clear();
        }
    }

    void requestPasswordReset(String email) throws Exception {
        SessionApi publicSession = new SessionApi(URI.create(origin), webSocketUriOverride);
        try {
            publicSession.api().getCsrfToken();
            publicSession.api().requestPasswordReset(new RequestPasswordResetRequest().email(email));
        } finally {
            publicSession.clear();
        }
    }

    void confirmPasswordReset(String token, String newPassword) throws Exception {
        SessionApi publicSession = new SessionApi(URI.create(origin), webSocketUriOverride);
        try {
            publicSession.api().getCsrfToken();
            publicSession.api().confirmPasswordReset(new ConfirmPasswordResetRequest()
                    .token(token).newPassword(newPassword));
        } finally {
            publicSession.clear();
        }
    }

    MyProfile updateProfile(ProfileUpdate update) throws Exception {
        MyProfile updated = session.api().updateCurrentUser(update);
        profile = updated;
        return updated;
    }

    void startRealtime(Consumer<String> eventHandler, Runnable afterConnect) {
        if (!realtimeEnabled || session == null) return;
        realtime = new RealtimeConnection(session, eventHandler, afterConnect);
        realtime.start();
    }

    void signOut(Runnable onSignedOut) {
        RealtimeConnection previousRealtime = realtime;
        realtime = null;
        if (previousRealtime != null) previousRealtime.close();

        EncryptedMessageCache previousCache = cache;
        cache = null;
        if (previousCache != null) {
            try { previousCache.close(); } catch (Exception ignored) { }
        }

        SessionApi previousSession = session;
        session = null;
        messaging = null;
        groups = null;
        userId = null;
        profile = null;

        if (previousSession == null) {
            Platform.runLater(onSignedOut);
            return;
        }
        network.execute(() -> {
            try { previousSession.api().logout(); } catch (Exception ignored) { }
            finally {
                previousSession.clear();
                Platform.runLater(onSignedOut);
            }
        });
    }

    <T> void runAsync(Callable<T> work, Consumer<T> success, Consumer<Exception> failure) {
        network.execute(() -> {
            try {
                T value = work.call();
                Platform.runLater(() -> success.accept(value));
            } catch (Exception exception) {
                Platform.runLater(() -> failure.accept(exception));
            }
        });
    }

    Stage stage() { return stage; }
    ThemeManager themeManager() { return themeManager; }
    I18n i18n() { return i18n; }
    SessionApi session() { return session; }
    MyProfile profile() { return profile; }
    EncryptedMessageCache cache() { return cache; }
    MessageHistorySyncService messaging() { return messaging; }
    MlsGroupSyncService groups() { return groups; }
    UUID userId() { return userId; }
    boolean realtimeEnabled() { return realtimeEnabled; }
    ExecutorService network() { return network; }

    @Override
    public void close() {
        RealtimeConnection previousRealtime = realtime;
        realtime = null;
        if (previousRealtime != null) previousRealtime.close();
        EncryptedMessageCache previousCache = cache;
        cache = null;
        if (previousCache != null) {
            try { previousCache.close(); } catch (Exception ignored) { }
        }
        if (session != null) {
            session.clear();
            session = null;
        }
        network.shutdownNow();
    }

    record SignedIn(SessionApi session, MyProfile profile, EncryptedMessageCache cache,
                    MlsGroupSyncService groups) { }
}
