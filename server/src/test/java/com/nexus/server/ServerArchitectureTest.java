package com.nexus.server;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ServerArchitectureTest {

    @Test
    void serverMustNotDependOnJavaFx() {
        noClasses()
                .that().resideInAPackage("com.nexus.server..")
                .should().dependOnClassesThat().resideInAnyPackage("javafx..")
                .check(new ClassFileImporter().importPackages("com.nexus.server"));
    }
}
