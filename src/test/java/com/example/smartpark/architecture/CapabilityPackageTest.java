package com.example.smartpark.architecture;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.ParkContext;
import com.example.smartpark.model.common.ApprovalDecision;
import com.example.smartpark.model.common.Diagnosis;
import com.example.smartpark.model.common.Device;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.model.common.WorkOrder;
import com.example.smartpark.model.energy.EnergyReading;
import com.example.smartpark.port.energy.EnergyPort;
import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.energy.EnergyQueryTool;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityPackageTest {

    private static final List<Class<?>> COMMON_MODEL_TYPES = List.of(
            ApprovalDecision.class,
            Diagnosis.class,
            Device.class,
            KnowledgeDocument.class,
            RiskLevel.class,
            WorkflowStatus.class,
            WorkOrder.class);

    private static final List<String> FORBIDDEN_COMMON_MODEL_REFERENCES = List.of(
            "com/example/smartpark/model/alert",
            "com/example/smartpark/model/energy",
            "com/example/smartpark/model/security");

    @Test
    void placesAlertInAlertModelPackage() {
        assertThat(Alert.class.getPackageName()).isEqualTo("com.example.smartpark.model.alert");
    }

    @Test
    void placesParkContextInAlertCapabilityPackage() {
        assertThat(ParkContext.class.getPackageName()).isEqualTo("com.example.smartpark.model.alert");
    }

    @Test
    void placesEnergyReadingInEnergyModelPackage() {
        assertThat(EnergyReading.class.getPackageName()).isEqualTo("com.example.smartpark.model.energy");
    }

    @Test
    void placesEnergyPortInEnergyPortPackage() {
        assertThat(EnergyPort.class.getPackageName()).isEqualTo("com.example.smartpark.port.energy");
    }

    @Test
    void scenarioToolsLiveInCapabilityPackages() {
        assertThat(EnergyQueryTool.class.getPackageName()).isEqualTo("com.example.smartpark.tool.energy");
        assertThat(AlertQueryTool.class.getPackageName()).isEqualTo("com.example.smartpark.tool.alert");
    }

    @Test
    void commonModelClassBytesDoNotReferenceCapabilitySpecificModels() throws IOException {
        for (Class<?> type : COMMON_MODEL_TYPES) {
            for (String value : constantPoolUtf8Values(type)) {
                assertThat(FORBIDDEN_COMMON_MODEL_REFERENCES.stream().noneMatch(value::contains))
                        .as("constant-pool entry in common model %s: %s", type.getName(), value)
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
        List<String> values = new ArrayList<>();
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
}
