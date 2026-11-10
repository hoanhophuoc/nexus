package com.haichutieu.chatsystem;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private static final JavaClasses CLASSES =
            new ClassFileImporter().importPackages("com.haichutieu.chatsystem");

    @Test
    void dtoShouldNotDependOnClientOrServer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..dto..")
                .should().dependOnClassesThat().resideInAnyPackage("..client..", "..server..");

        rule.check(CLASSES);
    }

    @Test
    void serverShouldNotDependOnClient() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..server..")
                .should().dependOnClassesThat().resideInAPackage("..client..");

        rule.check(CLASSES);
    }

    @Test
    void serverShouldNotDependOnJavaFx() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..server..")
                .should().dependOnClassesThat().resideInAnyPackage("javafx..");

        rule.check(CLASSES);
    }

    @Test
    void clientShouldNotDependOnServerOrPersistence() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..client..")
                .should().dependOnClassesThat().resideInAnyPackage("..server..", "..dal..");

        rule.check(CLASSES);
    }

    @Test
    void utilityPackageShouldStayIndependentOfUiAndServer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.haichutieu.chatsystem.util..")
                .should().dependOnClassesThat().resideInAnyPackage("..client..", "..server..");

        rule.check(CLASSES);
    }
}
