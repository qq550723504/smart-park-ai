package com.example.smartpark.showcase;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShowcaseComposeConfigurationTest {

    private static final Map<String, Object> REQUIRED_SHOWCASE_ENVIRONMENT = Map.of(
            "SMARTPARK_KNOWLEDGE_MODE", "rag",
            "SMARTPARK_CUSTOMER_SERVICE_ANSWER_MODE", "dashscope",
            "SMARTPARK_VOICE_ENABLED", "true",
            "SMARTPARK_VOICE_ALLOWED_ORIGINS", "http://localhost:5173,http://127.0.0.1:5173");

    private static final String REQUIRED_SHOWCASE_ENVIRONMENT_YAML = """
            SMARTPARK_KNOWLEDGE_MODE: rag
            SMARTPARK_CUSTOMER_SERVICE_ANSWER_MODE: dashscope
            SMARTPARK_VOICE_ENABLED: "true"
            SMARTPARK_VOICE_ALLOWED_ORIGINS: http://localhost:5173,http://127.0.0.1:5173
            """;

    @Test
    void showcaseOverlayContainsOnlyTheRequiredBackendEnvironment() throws Exception {
        Map<String, Object> overlay = parseYaml(Path.of("compose.showcase.yaml"));

        assertShowcaseOverlayContract(overlay);
        assertThat(allKeys(overlay)).doesNotContain(
                "AI_DASHSCOPE_API_KEY",
                "SMARTPARK_ANALYTICS_DB_ADMIN_PASSWORD",
                "SMARTPARK_ANALYTICS_DB_RO_PASSWORD");
    }

    @Test
    void analyticsComposeOwnsTheProviderAndDatabaseCredentialMappings() throws Exception {
        Map<String, Object> analyticsEnvironment = backendEnvironment(parseYaml(Path.of("compose.analytics.yaml")));

        assertThat(analyticsEnvironment).contains(
                Map.entry("AI_DASHSCOPE_API_KEY",
                        "${AI_DASHSCOPE_API_KEY:?set AI_DASHSCOPE_API_KEY in .env before enabling analytics}"),
                Map.entry("SMARTPARK_ANALYTICS_DB_ADMIN_PASSWORD",
                        "${SMARTPARK_ANALYTICS_DB_ADMIN_PASSWORD:?set SMARTPARK_ANALYTICS_DB_ADMIN_PASSWORD in .env before enabling analytics}"),
                Map.entry("SMARTPARK_ANALYTICS_DB_RO_PASSWORD",
                        "${SMARTPARK_ANALYTICS_DB_RO_PASSWORD:?set SMARTPARK_ANALYTICS_DB_RO_PASSWORD in .env before enabling analytics}"));
    }

    @Test
    void defaultComposeRemainsOfflineAndApplicationMapsThePreflightTimeout() throws Exception {
        Map<String, Object> defaultEnvironment = backendEnvironment(parseYaml(Path.of("compose.yaml")));
        Map<String, Object> application = parseYaml(Path.of("src/main/resources/application.yml"));

        assertThat(defaultEnvironment.get("SPRING_AI_DASHSCOPE_ENABLED")).isEqualTo("false");
        assertThat(defaultEnvironment.getOrDefault("SMARTPARK_ANALYTICS_ENABLED", "false")).isEqualTo("false");
        assertThat(defaultEnvironment.getOrDefault("SMARTPARK_ANALYTICS_DEMO_DATA_REFRESH_ENABLED", "false"))
                .isEqualTo("false");
        assertThat(defaultEnvironment.getOrDefault("SMARTPARK_VOICE_ENABLED", "false")).isEqualTo("false");
        assertThat(defaultEnvironment.getOrDefault("SMARTPARK_KNOWLEDGE_MODE", "mock")).isEqualTo("mock");
        assertThat(defaultEnvironment.getOrDefault("SMARTPARK_CUSTOMER_SERVICE_ANSWER_MODE", "mock"))
                .isEqualTo("mock");
        assertThat(mapAt(mapAt(application, "smartpark"), "showcase").get("preflight-timeout"))
                .isEqualTo("${SMARTPARK_SHOWCASE_PREFLIGHT_TIMEOUT:90s}");
    }

    @Test
    void showcaseContractRejectsCommentedValuesAndExtraKeys() {
        String commentedValues = """
                # SMARTPARK_KNOWLEDGE_MODE: rag
                # SMARTPARK_CUSTOMER_SERVICE_ANSWER_MODE: dashscope
                # SMARTPARK_VOICE_ENABLED: "true"
                # SMARTPARK_VOICE_ALLOWED_ORIGINS: http://localhost:5173,http://127.0.0.1:5173
                services:
                  backend:
                    environment: {}
                """;
        String extraService = """
                services:
                  backend:
                    environment:
                %s
                  unrelated-service:
                    image: busybox:latest
                """.formatted(indent(REQUIRED_SHOWCASE_ENVIRONMENT_YAML, 6));
        String extraBackendKey = """
                services:
                  backend:
                    environment:
                %s
                    image: ignored
                """.formatted(indent(REQUIRED_SHOWCASE_ENVIRONMENT_YAML, 6));

        assertThatThrownBy(() -> assertShowcaseOverlayContract(parseYaml(commentedValues)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertShowcaseOverlayContract(parseYaml(extraService)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertShowcaseOverlayContract(parseYaml(extraBackendKey)))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void showcaseContractRejectsMisnestedValuesAliasesAnchorsMergesAndDuplicateKeys() {
        String misnested = """
                services:
                  backend:
                    SMARTPARK_KNOWLEDGE_MODE: rag
                    environment:
                %s
                """.formatted(indent(REQUIRED_SHOWCASE_ENVIRONMENT_YAML, 6));
        String anchor = """
                services:
                  backend: &backend
                    environment:
                %s
                """.formatted(indent(REQUIRED_SHOWCASE_ENVIRONMENT_YAML, 6));
        String alias = "services: *backend\n";
        String inlineMerge = """
                services:
                  backend:
                    environment:
                      <<: {SMARTPARK_KNOWLEDGE_MODE: rag}
                      SMARTPARK_CUSTOMER_SERVICE_ANSWER_MODE: dashscope
                      SMARTPARK_VOICE_ENABLED: "true"
                      SMARTPARK_VOICE_ALLOWED_ORIGINS: http://localhost:5173,http://127.0.0.1:5173
                """;
        String duplicateKey = """
                services:
                  backend:
                    environment:
                %s
                      SMARTPARK_KNOWLEDGE_MODE: mock
                """.formatted(indent(REQUIRED_SHOWCASE_ENVIRONMENT_YAML, 6));

        assertThatThrownBy(() -> assertShowcaseOverlayContract(parseYaml(misnested)))
                .isInstanceOf(AssertionError.class);
        assertThatIllegalArgumentException().isThrownBy(() -> parseYaml(anchor));
        assertThatIllegalArgumentException().isThrownBy(() -> parseYaml(alias));
        assertThatIllegalArgumentException().isThrownBy(() -> parseYaml(inlineMerge));
        assertThatThrownBy(() -> parseYaml(duplicateKey)).isInstanceOf(RuntimeException.class);
    }

    private static void assertShowcaseOverlayContract(Map<String, Object> overlay) {
        assertThat(overlay).containsOnlyKeys("services");
        Map<String, Object> services = mapAt(overlay, "services");
        assertThat(services).containsOnlyKeys("backend");
        Map<String, Object> backend = mapAt(services, "backend");
        assertThat(backend).containsOnlyKeys("environment");
        assertThat(mapAt(backend, "environment"))
                .containsExactlyInAnyOrderEntriesOf(REQUIRED_SHOWCASE_ENVIRONMENT);
    }

    private static Map<String, Object> backendEnvironment(Map<String, Object> compose) {
        return mapAt(mapAt(mapAt(compose, "services"), "backend"), "environment");
    }

    private static Map<String, Object> parseYaml(Path path) throws IOException {
        return parseYaml(Files.readString(path));
    }

    private static Map<String, Object> parseYaml(String source) {
        rejectAliasesAnchorsAndMerges(source);

        Yaml yaml = new Yaml(new SafeConstructor(strictLoaderOptions()));
        List<Object> documents = new ArrayList<>();
        yaml.loadAll(source).forEach(documents::add);
        assertThat(documents).hasSize(1);
        return asStringObjectMap(documents.get(0), "YAML document");
    }

    private static void rejectAliasesAnchorsAndMerges(String source) {
        for (Event event : new Yaml(strictLoaderOptions()).parse(new StringReader(source))) {
            if (event instanceof AliasEvent) {
                throw new IllegalArgumentException("YAML aliases are not permitted in Compose contracts");
            }
            if (event instanceof NodeEvent nodeEvent && nodeEvent.getAnchor() != null) {
                throw new IllegalArgumentException("YAML anchors are not permitted in Compose contracts");
            }
        }
        for (Node document : new Yaml(strictLoaderOptions()).composeAll(new StringReader(source))) {
            rejectMergeNodes(document);
        }
    }

    private static void rejectMergeNodes(Node node) {
        if (Tag.MERGE.equals(node.getTag())) {
            throw new IllegalArgumentException("YAML merge keys are not permitted in Compose contracts");
        }
        if (node instanceof MappingNode mappingNode) {
            if (mappingNode.isMerged()) {
                throw new IllegalArgumentException("YAML merged mappings are not permitted in Compose contracts");
            }
            for (NodeTuple tuple : mappingNode.getValue()) {
                rejectMergeNodes(tuple.getKeyNode());
                rejectMergeNodes(tuple.getValueNode());
            }
        } else if (node instanceof SequenceNode sequenceNode) {
            for (Node value : sequenceNode.getValue()) {
                rejectMergeNodes(value);
            }
        }
    }

    private static LoaderOptions strictLoaderOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setAllowRecursiveKeys(false);
        options.setMergeOnCompose(false);
        return options;
    }

    private static Map<String, Object> mapAt(Map<String, Object> parent, String key) {
        assertThat(parent).containsKey(key);
        return asStringObjectMap(parent.get(key), key);
    }

    private static Map<String, Object> asStringObjectMap(Object value, String location) {
        assertThat(value).as(location).isInstanceOf(Map.class);
        Map<?, ?> rawMap = (Map<?, ?>) value;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            assertThat(entry.getKey()).as("key in %s", location).isInstanceOf(String.class);
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static Set<String> allKeys(Map<String, Object> value) {
        Set<String> keys = new java.util.HashSet<>();
        collectKeys(value, keys);
        return keys;
    }

    private static void collectKeys(Object value, Set<String> keys) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                assertThat(entry.getKey()).isInstanceOf(String.class);
                keys.add((String) entry.getKey());
                collectKeys(entry.getValue(), keys);
            }
        } else if (value instanceof List<?> list) {
            list.forEach(item -> collectKeys(item, keys));
        }
    }

    private static String indent(String value, int spaces) {
        return value.indent(spaces).stripTrailing();
    }
}
