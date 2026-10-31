package com.nutrimarket.nutrimarket;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

/** Source-level boundaries complement integration tests of Spring and MyBatis wiring. */
class LayeredArchitectureTests {
    private static final Path ROOT = Path.of("src/main/java/com/nutrimarket/nutrimarket");
    private static final String BASE = "com.nutrimarket.nutrimarket.";

    private List<Path> sources() throws IOException {
        try (var files = Files.walk(ROOT)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test void springComponentsBelongToTheirApplicationLayer() throws IOException {
        for (var file : sources()) {
            String source = Files.readString(file);
            var matcher = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE).matcher(source);
            assertThat(matcher.find()).as("Package declaration: %s", file).isTrue();
            String packageName = matcher.group(1);
            if (source.contains("@RestController"))
                assertThat(packageName).as("Controller: %s", file).startsWith(BASE + "controller.");
            if (source.contains("@Service"))
                assertThat(packageName).as("Service: %s", file).startsWith(BASE + "service.");
            if (source.contains("@Mapper") || source.contains("@Repository"))
                assertThat(packageName).as("DAO: %s", file).startsWith(BASE + "dao.");
        }
    }

    @Test void controllersUseServicesAndPersistenceDoesNotDependOnUpperLayers() throws IOException {
        for (var file : sources()) {
            String source = Files.readString(file);
            String layer = ROOT.relativize(file).getName(0).toString();
            List<String> forbidden = switch (layer) {
                case "controller" -> List.of("dao", "integration");
                case "service" -> List.of("controller");
                case "dao" -> List.of("controller", "service", "integration");
                case "model", "integration", "validation" -> List.of("controller", "service", "dao");
                case "config" -> List.of("dao");
                default -> List.of();
            };
            for (String dependency : forbidden)
                assertThat(source).as("Layer dependency in %s", file).doesNotContain(BASE + dependency + ".");
        }
    }
}
