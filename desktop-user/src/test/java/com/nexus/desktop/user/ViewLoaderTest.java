package com.nexus.desktop.user;

import com.nexus.desktop.shared.ui.I18n;
import javafx.scene.Parent;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A missing FXML resource must fail loudly at the call site, not as a NullPointerException later. */
class ViewLoaderTest {

    private static final String BASE = "com.nexus.desktop.user.i18n.messages";

    @Test
    void namesTheResourceItCouldNotFind() {
        assertThatThrownBy(() -> ViewLoader.load("/com/nexus/desktop/user/view/missing.fxml",
                new Object(), new I18n(BASE, Locale.ENGLISH)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("view/missing.fxml");
    }
}
