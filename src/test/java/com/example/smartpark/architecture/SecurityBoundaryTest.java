package com.example.smartpark.architecture;

import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.port.security.SecurityPort;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityBoundaryTest {

    private static final List<String> FORBIDDEN_PACKAGE_REFERENCES = List.of(
            "com/example/smartpark/web",
            "com/example/smartpark/tool",
            "com/example/smartpark/adapter");

    @Test
    void placesSecurityTypesInCapabilityPackages() {
        assertThat(SecurityEvent.class.getPackageName()).isEqualTo("com.example.smartpark.model.security");
        assertThat(SecurityPort.class.getPackageName()).isEqualTo("com.example.smartpark.port.security");
    }

    @Test
    void exposesOnlyTheEventLookupPort() {
        Method[] methods = SecurityPort.class.getDeclaredMethods();

        assertThat(methods).hasSize(1);
        assertThat(methods[0].getName()).isEqualTo("getEvent");
        assertThat(methods[0].getReturnType()).isEqualTo(SecurityEvent.class);
        assertThat(methods[0].getParameterTypes()).containsExactly(String.class);
    }

    @Test
    void securityBoundaryDoesNotDependOnWebToolsOrAdapters() {
        Stream<Type> referencedTypes = Stream.concat(
                Arrays.stream(SecurityEvent.class.getRecordComponents()).map(component -> component.getGenericType()),
                Arrays.stream(SecurityPort.class.getDeclaredMethods()).flatMap(this::methodTypes));

        assertThat(referencedTypes.map(Type::getTypeName))
                .noneMatch(typeName -> typeName.contains(".web.")
                        || typeName.contains(".tool.")
                        || typeName.contains(".adapter."));
    }

    @Test
    void securityBoundaryClassBytesDoNotReferenceForbiddenPackages() throws IOException {
        for (Class<?> type : List.of(SecurityEvent.class, SecurityPort.class)) {
            for (String value : constantPoolUtf8Values(type)) {
                assertThat(FORBIDDEN_PACKAGE_REFERENCES.stream().noneMatch(value::contains))
                        .as("constant-pool entry in security boundary: %s", value)
                        .isTrue();
            }
        }
    }

    private List<String> constantPoolUtf8Values(Class<?> type) throws IOException {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        InputStream input = type.getResourceAsStream(resourceName);
        assertThat(input).as("class bytes for %s", type.getName()).isNotNull();
        try (input) {
            return readConstantPoolUtf8Values(input.readAllBytes());
        }
    }

    private List<String> readConstantPoolUtf8Values(byte[] classBytes) {
        assertThat(readInt(classBytes, 0)).isEqualTo(0xCAFEBABE);
        int constantPoolCount = readUnsignedShort(classBytes, 8);
        int offset = 10;
        List<String> values = new java.util.ArrayList<>();
        for (int entry = 1; entry < constantPoolCount; entry++) {
            int tag = readUnsignedByte(classBytes, offset++);
            switch (tag) {
                case 1 -> {
                    int length = readUnsignedShort(classBytes, offset);
                    offset += 2;
                    values.add(new String(classBytes, offset, length, StandardCharsets.UTF_8));
                    offset += length;
                }
                case 3, 4 -> offset += 4;
                case 5, 6 -> {
                    offset += 8;
                    entry++;
                }
                case 7, 8, 16, 19, 20 -> offset += 2;
                case 9, 10, 11, 12, 17, 18 -> offset += 4;
                case 15 -> offset += 3;
                default -> throw new IllegalArgumentException("Unsupported constant-pool tag: " + tag);
            }
        }
        return values;
    }

    private int readUnsignedByte(byte[] bytes, int offset) {
        return bytes[offset] & 0xff;
    }

    private int readUnsignedShort(byte[] bytes, int offset) {
        return (readUnsignedByte(bytes, offset) << 8) | readUnsignedByte(bytes, offset + 1);
    }

    private int readInt(byte[] bytes, int offset) {
        return (readUnsignedByte(bytes, offset) << 24)
                | (readUnsignedByte(bytes, offset + 1) << 16)
                | (readUnsignedByte(bytes, offset + 2) << 8)
                | readUnsignedByte(bytes, offset + 3);
    }

    private Stream<Type> methodTypes(Method method) {
        return Stream.concat(
                Stream.of(method.getGenericReturnType()),
                Arrays.stream(method.getGenericParameterTypes()));
    }
}
