package com.example.smartpark.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AdapterDependencyTest {

    @Test
    void adaptersDoNotDependOnWebLayer() throws IOException {
        Path root = Paths.get("src/main/java/com/example/smartpark/adapter");
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String source = Files.readString(path, StandardCharsets.UTF_8);
                    assertThat(source).as("adapter source %s", path)
                            .doesNotContain("com.example.smartpark.web");
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
    }
}
