package com.example.smartpark.architecture;

import com.example.smartpark.port.customer.CustomerSessionStore;
import com.example.smartpark.port.customer.CustomerTicketPort;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerPortBoundaryTest {

    @Test
    void customerPortsRemainIndependentOfSpringAndAdapters() throws IOException {
        assertThat(CustomerSessionStore.class.getPackageName()).isEqualTo("com.example.smartpark.port.customer");
        assertThat(CustomerTicketPort.class.getPackageName()).isEqualTo("com.example.smartpark.port.customer");

        for (Path port : new Path[]{
                Path.of("src/main/java/com/example/smartpark/port/customer/CustomerSessionStore.java"),
                Path.of("src/main/java/com/example/smartpark/port/customer/CustomerTicketPort.java")}) {
            String source = Files.readString(port, StandardCharsets.UTF_8);
            assertThat(source).doesNotContain("org.springframework", "com.example.smartpark.adapter", "java.sql");
        }
    }
}
