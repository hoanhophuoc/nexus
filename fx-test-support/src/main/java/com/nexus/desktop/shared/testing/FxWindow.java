package com.nexus.desktop.shared.testing;

import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.function.Consumer;

import static com.nexus.desktop.shared.testing.FxThread.onFx;

/**
 * One test window: a stage and a theme manager, shown once and read in both themes.
 *
 * <p>The suites that mount a real screen all need the same five steps — create the stage, apply the
 * theme to the scene, let the module add its own stylesheet, show, and run the CSS and layout pass
 * the measurements read from. Keeping those in one place means a suite only supplies the screen and
 * what is specific to its module; the lifecycle is not copied per test class.</p>
 */
public final class FxWindow implements AutoCloseable {
    private final Stage stage;
    private final ThemeManager theme;
    private Scene scene;

    private FxWindow(Stage stage, ThemeManager theme) {
        this.stage = stage;
        this.theme = theme;
    }

    public static FxWindow open() {
        return onFx(() -> new FxWindow(new Stage(), new ThemeManager()));
    }

    public Stage stage() {
        return stage;
    }

    public ThemeManager theme() {
        return theme;
    }

    public Scene scene() {
        return scene;
    }

    /** Moves the live scene to {@code next} and re-resolves its tokens. */
    public void theme(Theme next) {
        onFx(() -> {
            theme.setTheme(next);
            if (scene != null) theme.apply(scene);
            return true;
        });
    }

    /**
     * Shows {@code root} with the current theme, then the module's own styling through
     * {@code prepare}, and returns it after the CSS and layout pass.
     */
    public <T extends Parent> T show(T root, double width, double height, Consumer<Scene> prepare) {
        return onFx(() -> {
            scene = new Scene(root, width, height);
            theme.apply(scene);
            prepare.accept(scene);
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            root.layout();
            return root;
        });
    }

    @Override
    public void close() {
        onFx(() -> {
            stage.hide();
            return true;
        });
    }
}
