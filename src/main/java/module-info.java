module system.chatsystem {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.hibernate.orm.core;
    requires jakarta.persistence;
    requires jakarta.mail;
    requires org.eclipse.angus.mail;
    requires java.naming;
    requires jdk.httpserver;
    requires com.password4j;
    requires tools.jackson.databind;
    requires java.desktop;
    requires org.postgresql.jdbc;


    exports com.haichutieu.chatsystem.client.gui;
    opens com.haichutieu.chatsystem.client.gui to javafx.fxml;
    exports com.haichutieu.chatsystem.client;
    opens com.haichutieu.chatsystem.client to javafx.fxml;
    exports com.haichutieu.chatsystem.client.util;
    opens com.haichutieu.chatsystem.client.util to javafx.fxml;
    opens com.haichutieu.chatsystem.client.gui.adminPanel to javafx.fxml;
    // Hibernate and Jackson inspect the entity/DTO classes reflectively.
    opens com.haichutieu.chatsystem.dto to org.hibernate.orm.core, tools.jackson.databind, javafx.base;
}
