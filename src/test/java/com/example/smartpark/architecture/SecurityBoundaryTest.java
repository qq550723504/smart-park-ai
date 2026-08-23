package com.example.smartpark.architecture;

import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.port.security.SecurityPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityBoundaryTest {

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

    private Stream<Type> methodTypes(Method method) {
        return Stream.concat(
                Stream.of(method.getGenericReturnType()),
                Arrays.stream(method.getGenericParameterTypes()));
    }
}
