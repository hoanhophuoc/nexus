package com.haichutieu.chatsystem.client;

import javafx.application.Application;

/**
 * Starts the user client from the classpath. A main class that does not extend
 * {@link Application} is required, otherwise the JVM refuses to start JavaFX
 * when the runtime is not on the module path.
 */
public final class ClientLauncher {
    private ClientLauncher() {
    }

    static void main(String[] args) {
        Application.launch(ClientApp.class, args);
    }
}
