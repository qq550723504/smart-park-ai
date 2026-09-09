package com.example.smartpark.analytics.report;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Bounded atomic file store for structured report snapshots and embedded Markdown artifacts. */
public final class OperationsDailyReportStore {
    public static final int DEFAULT_MAX_RETAINED_REPORTS = 200;
    public static final int DEFAULT_MAX_ACTIVE_REPORTS = 1;
    public static final int DEFAULT_MAX_REPORT_BYTES = 512 * 1024;
    public static final int DEFAULT_MAX_ARTIFACT_BYTES = 256 * 1024;
    static final long MAX_STATE_FILE_BYTES = 512L * 1024 * 1024;

    private final Path stateFile;
    private final ObjectMapper mapper;
    private final int maxRetainedReports;
    private final int maxActiveReports;
    private final int maxReportBytes;
    private final int maxArtifactBytes;
    private final AtomicReplacer atomicReplacer;
    private final Consumer<UUID> terminalTraceEvictor;
    private final Map<UUID, OperationsDailyReport> reports = new LinkedHashMap<>();
    private final Map<String, UUID> idempotencyIndex = new LinkedHashMap<>();
    /** Future/older schema records are retained verbatim but deliberately not exposed. */
    private final List<JsonNode> unsupportedRecords = new ArrayList<>();

    public OperationsDailyReportStore(Path stateFile, ObjectMapper mapper) {
        this(stateFile, mapper, DEFAULT_MAX_RETAINED_REPORTS, DEFAULT_MAX_ACTIVE_REPORTS,
                DEFAULT_MAX_REPORT_BYTES, DEFAULT_MAX_ARTIFACT_BYTES,
                OperationsDailyReportStore::atomicReplace, ignored -> { });
    }

    public OperationsDailyReportStore(Path stateFile, ObjectMapper mapper,
                                      int maxRetainedReports, int maxActiveReports,
                                      int maxReportBytes, int maxArtifactBytes) {
        this(stateFile, mapper, maxRetainedReports, maxActiveReports, maxReportBytes,
                maxArtifactBytes, OperationsDailyReportStore::atomicReplace, ignored -> { });
    }

    OperationsDailyReportStore(Path stateFile, ObjectMapper mapper,
                               int maxRetainedReports, int maxActiveReports,
                               int maxReportBytes, int maxArtifactBytes,
                               AtomicReplacer atomicReplacer) {
        this(stateFile, mapper, maxRetainedReports, maxActiveReports, maxReportBytes,
                maxArtifactBytes, atomicReplacer, ignored -> { });
    }

    OperationsDailyReportStore(Path stateFile, ObjectMapper mapper,
                               int maxRetainedReports, int maxActiveReports,
                               int maxReportBytes, int maxArtifactBytes,
                               Consumer<UUID> terminalTraceEvictor) {
        this(stateFile, mapper, maxRetainedReports, maxActiveReports, maxReportBytes,
                maxArtifactBytes, OperationsDailyReportStore::atomicReplace, terminalTraceEvictor);
    }

    OperationsDailyReportStore(Path stateFile, ObjectMapper mapper,
                               int maxRetainedReports, int maxActiveReports,
                               int maxReportBytes, int maxArtifactBytes,
                               AtomicReplacer atomicReplacer,
                               Consumer<UUID> terminalTraceEvictor) {
        this.stateFile = stateFile.toAbsolutePath().normalize();
        this.mapper = mapper.copy().findAndRegisterModules();
        if (maxRetainedReports < 1) throw new IllegalArgumentException("maxRetainedReports must be positive");
        if (maxActiveReports < 1 || maxActiveReports > maxRetainedReports) {
            throw new IllegalArgumentException("maxActiveReports is invalid");
        }
        if (maxReportBytes < 4096 || maxReportBytes > MAX_STATE_FILE_BYTES
                || maxArtifactBytes < 1024 || maxArtifactBytes > maxReportBytes) {
            throw new IllegalArgumentException("report byte limits are invalid");
        }
        this.maxRetainedReports = maxRetainedReports;
        this.maxActiveReports = maxActiveReports;
        this.maxReportBytes = maxReportBytes;
        this.maxArtifactBytes = maxArtifactBytes;
        this.atomicReplacer = java.util.Objects.requireNonNull(atomicReplacer, "atomicReplacer");
        this.terminalTraceEvictor = java.util.Objects.requireNonNull(terminalTraceEvictor,
                "terminalTraceEvictor");
        load();
    }

    public synchronized StartResult createOrGet(String key, String fingerprint,
                                                Supplier<OperationsDailyReport> factory) {
        if (unsupportedRecords.stream().anyMatch(record -> key.equals(record.path("idempotencyKey").asText()))) {
            throw new IllegalStateException("Idempotency-Key was already used by an unsupported report schema");
        }
        UUID existingId = idempotencyIndex.get(key);
        if (existingId != null) {
            OperationsDailyReport existing = reports.get(existingId);
            if (existing == null) throw new IllegalStateException("report idempotency index is inconsistent");
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw new IllegalStateException("Idempotency-Key was already used for another report request");
            }
            return new StartResult(existing, false);
        }
        long active = reports.values().stream().filter(report -> !report.status().isTerminal()).count();
        if (active >= maxActiveReports) {
            throw new OperationsReportCapacityException("operations report active capacity is exhausted");
        }
        int supportedTarget = maxRetainedReports - unsupportedRecords.size() - 1;
        if (supportedTarget < 0) {
            throw new OperationsReportCapacityException("operations report retained capacity is exhausted");
        }
        LinkedHashMap<UUID, OperationsDailyReport> next = compact(supportedTarget);
        List<UUID> evictedTraceIds = reports.values().stream()
                .filter(report -> !next.containsKey(report.reportId()))
                .map(OperationsDailyReport::traceId)
                .toList();
        if (next.size() + unsupportedRecords.size() >= maxRetainedReports) {
            throw new OperationsReportCapacityException("operations report retained capacity is exhausted");
        }
        OperationsDailyReport created = factory.get();
        if (!key.equals(created.idempotencyKey()) || !fingerprint.equals(created.requestFingerprint())) {
            throw new IllegalArgumentException("created report does not match idempotency request");
        }
        next.put(created.reportId(), created);
        persist(next.values());
        replaceState(next);
        // Keep the live execution projection aligned with the durable authorization
        // source before another request can observe the evicted report.
        evictedTraceIds.forEach(terminalTraceEvictor);
        return new StartResult(created, true);
    }

    public synchronized Optional<OperationsDailyReport> find(UUID reportId) {
        return Optional.ofNullable(reports.get(reportId));
    }

    public synchronized boolean isUnsupported(UUID reportId) {
        return unsupportedRecords.stream()
                .anyMatch(record -> reportId.toString().equals(record.path("reportId").asText()));
    }

    public synchronized Optional<OperationsDailyReport> findByRunId(UUID runId) {
        return reports.values().stream().filter(report -> report.runId().equals(runId)).findFirst();
    }

    public synchronized List<OperationsDailyReport> all() {
        return List.copyOf(reports.values());
    }

    public synchronized List<OperationsDailyReport> nonTerminalReports() {
        return reports.values().stream().filter(report -> !report.status().isTerminal()).toList();
    }

    public synchronized OperationsDailyReport update(UUID reportId,
                                                     UnaryOperator<OperationsDailyReport> transition) {
        OperationsDailyReport current = Optional.ofNullable(reports.get(reportId))
                .orElseThrow(() -> new NoSuchElementException("Unknown operations report"));
        OperationsDailyReport updated = transition.apply(current);
        if (!reportId.equals(updated.reportId())) throw new IllegalArgumentException("transition changed report identity");
        if (updated.revision() <= current.revision()) throw new IllegalArgumentException("report revision must advance");
        LinkedHashMap<UUID, OperationsDailyReport> next = new LinkedHashMap<>(reports);
        next.put(reportId, updated);
        validateReportSize(updated);
        persist(next.values());
        replaceState(next);
        return updated;
    }

    private void load() {
        if (!Files.exists(stateFile)) return;
        try {
            if (Files.size(stateFile) > MAX_STATE_FILE_BYTES) {
                throw new IllegalStateException("operations report state file exceeds global byte limit");
            }
            LinkedHashMap<UUID, OperationsDailyReport> loaded = new LinkedHashMap<>();
            java.util.Set<UUID> loadedIds = new java.util.HashSet<>();
            java.util.Set<String> loadedKeys = new java.util.HashSet<>();
            List<JsonNode> unsupported = new ArrayList<>();
            int supportedRecordCount = 0;
            try (JsonParser parser = mapper.getFactory().createParser(stateFile.toFile())) {
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    throw new IllegalStateException("operations report state must be a JSON array");
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    JsonNode node = mapper.readTree(parser);
                    if (node == null || !node.isObject()) {
                        throw new IllegalStateException("operations report state record must be an object");
                    }
                    JsonNode version = node.get("schemaVersion");
                    if (version == null || !version.isInt()
                            || version.intValue() != OperationsDailyReport.CURRENT_SCHEMA_VERSION) {
                        unsupported.add(node.deepCopy());
                    } else {
                        OperationsDailyReport report = mapper.treeToValue(node, OperationsDailyReport.class);
                        validateReportSize(report);
                        if (!loadedKeys.add(report.idempotencyKey())) {
                            throw new IllegalStateException("duplicate operations report idempotency key");
                        }
                        if (!loadedIds.add(report.reportId())) {
                            throw new IllegalStateException("duplicate operations report id");
                        }
                        supportedRecordCount++;
                        loaded.put(report.reportId(), report);
                    }
                    int target = maxRetainedReports - unsupported.size();
                    if (target < 0) {
                        throw new OperationsReportCapacityException(
                                "unsupported operations report records exceed retained capacity");
                    }
                    loaded = compact(loaded, target);
                    if (loaded.size() > target) {
                        throw new OperationsReportCapacityException(
                                "active operations report records exceed retained capacity");
                    }
                }
                if (parser.nextToken() != null) {
                    throw new IllegalStateException("operations report state has trailing content");
                }
            }
            reports.clear();
            reports.putAll(loaded);
            unsupportedRecords.clear();
            unsupportedRecords.addAll(unsupported);
            if (supportedRecordCount != loaded.size()) persist(loaded.values());
            replaceState(loaded);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("unable to load operations report state", failure);
        }
    }

    private LinkedHashMap<UUID, OperationsDailyReport> compact(int targetSize) {
        return compact(reports, targetSize);
    }

    private static LinkedHashMap<UUID, OperationsDailyReport> compact(
            Map<UUID, OperationsDailyReport> source, int targetSize) {
        LinkedHashMap<UUID, OperationsDailyReport> compacted = new LinkedHashMap<>(source);
        var iterator = compacted.entrySet().iterator();
        while (compacted.size() > targetSize && iterator.hasNext()) {
            if (iterator.next().getValue().status().isTerminal()) iterator.remove();
        }
        return compacted;
    }

    private void replaceState(Map<UUID, OperationsDailyReport> next) {
        reports.clear();
        reports.putAll(next);
        idempotencyIndex.clear();
        for (OperationsDailyReport report : reports.values()) {
            UUID duplicate = idempotencyIndex.putIfAbsent(report.idempotencyKey(), report.reportId());
            if (duplicate != null && !duplicate.equals(report.reportId())) {
                throw new IllegalStateException("duplicate operations report idempotency key");
            }
        }
    }

    private void persist(java.util.Collection<OperationsDailyReport> snapshot) {
        try {
            snapshot.forEach(this::validateReportSize);
            Path parent = stateFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, stateFile.getFileName().toString(), ".tmp");
            try {
                ArrayNode output = mapper.createArrayNode();
                snapshot.forEach(report -> output.add(mapper.valueToTree(report)));
                unsupportedRecords.forEach(record -> output.add(record.deepCopy()));
                mapper.writeValue(temporary.toFile(), output);
                atomicReplacer.replace(temporary, stateFile);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("unable to persist operations report state", failure);
        }
    }

    private void validateReportSize(OperationsDailyReport report) {
        if (report.artifact() != null) {
            byte[] content = report.artifact().content().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (content.length > maxArtifactBytes) {
                throw new OperationsReportCapacityException("operations report artifact exceeds configured byte limit");
            }
            if (report.artifact().size() != content.length || !report.artifact().checksum().equals(sha256(content))) {
                throw new IllegalStateException("operations report artifact metadata is inconsistent");
            }
        }
        try {
            if (mapper.writeValueAsBytes(report).length > maxReportBytes) {
                throw new OperationsReportCapacityException("operations report exceeds configured byte limit");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("unable to size operations report", failure);
        }
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record StartResult(OperationsDailyReport report, boolean created) { }

    @FunctionalInterface
    interface AtomicReplacer {
        void replace(Path source, Path target) throws IOException;
    }
}
