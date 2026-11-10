package com.haichutieu.chatsystem;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates every FXML document without starting the JavaFX toolkit or connecting
 * to a server. Runtime loading of the login views is covered by the fxTest suite.
 */
class FxmlResourcesTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final String FX_NS = "http://javafx.com/fxml/1";

    @Test
    void shouldContainNoBrokenControllerHandlerOrResourceReferences() throws Exception {
        List<String> problems = new ArrayList<>();
        List<Path> files;

        try (Stream<Path> stream = Files.walk(RESOURCES)) {
            files = stream.filter(path -> path.toString().endsWith(".fxml")).toList();
        }

        assertThat(files).isNotEmpty();

        for (Path file : files) {
            Document document = parse(file);
            Element root = document.getDocumentElement();
            String controllerName = controller(root);
            Class<?> controller = null;

            if (controllerName == null || controllerName.isBlank()) {
                problems.add(file + ": missing fx:controller");
            } else {
                try {
                    controller = Class.forName(controllerName);
                } catch (ClassNotFoundException e) {
                    problems.add(file + ": controller not found: " + controllerName);
                }
            }

            checkNode(file, root, controller, problems);
        }

        assertThat(problems).isEmpty();
    }

    private static void checkNode(Path file, Element element, Class<?> controller, List<String> problems) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String name = attribute.getNodeName();
            String value = attribute.getNodeValue();

            if (name.startsWith("on") && value.startsWith("#") && controller != null) {
                String handler = value.substring(1);
                if (declaredMethod(controller, handler) == null) {
                    problems.add(file + ": missing handler " + handler + " on " + controller.getSimpleName());
                }
            }

            if (name.equals("stylesheets") || name.equals("url") || name.equals("source")) {
                for (String reference : value.trim().split("\\s+")) {
                    checkResourceReference(file, reference, problems);
                }
            }
        }

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                checkNode(file, child, controller, problems);
            }
        }
    }

    private static void checkResourceReference(Path file, String reference, List<String> problems) {
        if (!reference.startsWith("@")) {
            return;
        }
        String path = reference.substring(1);
        Path resolved = path.startsWith("/")
                ? RESOURCES.resolve(path.substring(1))
                : file.getParent().resolve(path);
        if (!Files.isRegularFile(resolved.normalize())) {
            problems.add(file + ": missing resource " + reference + " -> " + resolved.normalize());
        }
    }

    private static Method declaredMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static String controller(Element root) {
        String controller = root.getAttributeNS(FX_NS, "controller");
        return controller == null || controller.isBlank() ? root.getAttribute("fx:controller") : controller;
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(file.toFile());
    }
}
