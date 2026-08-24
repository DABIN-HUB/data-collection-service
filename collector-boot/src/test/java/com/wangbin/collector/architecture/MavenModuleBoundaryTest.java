package com.wangbin.collector.architecture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maven 模块边界回归测试，避免多模块收口后重新引入过宽 POM 依赖。
 */
class MavenModuleBoundaryTest {

    private static final Path ROOT = locateRepositoryRoot();
    private static final List<String> MODULES = List.of(
            "collector-common",
            "collector-protocol-spi",
            "collector-runtime",
            "collector-telemetry",
            "collector-protocol-iot",
            "collector-protocol-bacnet",
            "collector-protocol-plc",
            "collector-protocol-modbus",
            "collector-protocol-opc",
            "collector-protocol-iec",
            "collector-cloud",
            "collector-storage",
            "collector-monitor",
            "collector-application",
            "collector-web",
            "collector-boot");
    private static final Set<String> CONCRETE_PROTOCOL_MODULES = Set.of(
            "collector-protocol-iot",
            "collector-protocol-bacnet",
            "collector-protocol-plc",
            "collector-protocol-modbus",
            "collector-protocol-opc",
            "collector-protocol-iec");

    @Test
    void parentPomShouldNotExposeBroadDependenciesToEveryModule() throws Exception {
        Document document = parse(ROOT.resolve("pom.xml"));

        assertFalse(hasDirectChild(document.getDocumentElement(), "dependencies"),
                "父 POM 只能通过 dependencyManagement 管理版本，不能让子模块自动继承全量依赖");
        assertTrue(hasDirectChild(document.getDocumentElement(), "dependencyManagement"),
                "父 POM 应集中管理外部依赖版本");
    }

    @Test
    void applicationAndWebShouldNotDependOnConcreteProtocolModules() throws Exception {
        for (String module : List.of("collector-application", "collector-web")) {
            Set<String> dependencies = artifactIds(module);
            for (String protocolModule : CONCRETE_PROTOCOL_MODULES) {
                assertFalse(dependencies.contains(protocolModule),
                        module + " 不应直接依赖具体协议实现模块: " + protocolModule);
            }
        }
    }

    @Test
    void protocolModulesShouldNotDependOnStorageMonitorOrCloud() throws Exception {
        Set<String> forbidden = Set.of("collector-storage", "collector-monitor", "collector-cloud");
        for (String module : CONCRETE_PROTOCOL_MODULES) {
            Set<String> dependencies = artifactIds(module);
            for (String forbiddenModule : forbidden) {
                assertFalse(dependencies.contains(forbiddenModule),
                        module + " 不应依赖下游存储、监控或云上报模块: " + forbiddenModule);
            }
        }
    }

    @Test
    void onlyBootModuleShouldOwnSpringBootRepackage() throws Exception {
        for (String module : MODULES) {
            boolean hasPlugin = hasSpringBootPlugin(module);
            if ("collector-boot".equals(module)) {
                assertTrue(hasPlugin, "collector-boot 必须生成最终 Spring Boot 可执行 jar");
            } else {
                assertFalse(hasPlugin, module + " 只能生成普通 jar，不能执行 Spring Boot repackage");
            }
        }
    }

    private static Set<String> artifactIds(String module) throws Exception {
        Document document = parse(ROOT.resolve(module).resolve("pom.xml"));
        NodeList nodes = document.getElementsByTagName("dependency");
        java.util.LinkedHashSet<String> artifactIds = new java.util.LinkedHashSet<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element) {
                artifactIds.add(textOf(element, "artifactId"));
            }
        }
        return artifactIds;
    }

    private static boolean hasSpringBootPlugin(String module) throws Exception {
        Document document = parse(ROOT.resolve(module).resolve("pom.xml"));
        NodeList nodes = document.getElementsByTagName("plugin");
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element
                    && "spring-boot-maven-plugin".equals(textOf(element, "artifactId"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDirectChild(Element element, String tagName) {
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element child && tagName.equals(child.getLocalName())) {
                return true;
            }
        }
        return false;
    }

    private static String textOf(Element element, String tagName) {
        NodeList nodes = element.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return "";
        }
        return nodes.item(0).getTextContent().trim();
    }

    private static Document parse(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private static Path locateRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) && Files.exists(current.resolve("collector-boot"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位仓库根目录");
    }
}
