package com.example.smartpark.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Small durable store for orchestration metadata. Writes replace one complete
 * JSON snapshot atomically, so a process crash cannot expose a partially
 * written run. It stores safe summaries and references only, never raw SQL,
 * credentials or provider responses.
 */
public final class FileOrchestrationRunStore implements OrchestrationRunStore {
    private static final TypeReference<List<OrchestrationRun>> RUN_LIST = new TypeReference<>() { };

    private final Path stateFile;
    private final ObjectMapper mapper;
    private final AtomicReplacer atomicReplacer;
    private final OrchestrationStoreLimits limits;
    private final Map<UUID, OrchestrationRun> runs = new LinkedHashMap<>();
    private final Map<String, UUID> idempotencyIndex = new LinkedHashMap<>();

    public FileOrchestrationRunStore(Path stateFile, ObjectMapper mapper) {
        this(stateFile, mapper, OrchestrationStoreLimits.defaults(), FileOrchestrationRunStore::atomicReplace);
    }

    public FileOrchestrationRunStore(Path stateFile, ObjectMapper mapper,
                                     int maxRetainedRuns, int maxActiveRuns) {
        this(stateFile, mapper, new OrchestrationStoreLimits(maxRetainedRuns, maxActiveRuns),
                FileOrchestrationRunStore::atomicReplace);
    }

    FileOrchestrationRunStore(Path stateFile, ObjectMapper mapper, AtomicReplacer atomicReplacer) {
        this(stateFile, mapper, OrchestrationStoreLimits.defaults(), atomicReplacer);
    }

    FileOrchestrationRunStore(Path stateFile, ObjectMapper mapper,
                              OrchestrationStoreLimits limits, AtomicReplacer atomicReplacer) {
        this.stateFile = stateFile.toAbsolutePath().normalize();
        this.mapper = mapper.copy().findAndRegisterModules();
        this.limits = Objects.requireNonNull(limits, "limits");
        this.atomicReplacer = Objects.requireNonNull(atomicReplacer, "atomicReplacer");
        load();
    }

    @Override
    public synchronized StartResult createOrGet(String key, String fingerprint,
                                                Supplier<OrchestrationRun> factory) {
        UUID existingId = idempotencyIndex.get(key);
        if (existingId != null) {
            OrchestrationRun existing = runs.get(existingId);
            if (existing == null) throw new IllegalStateException("orchestration idempotency index is inconsistent");
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw new IllegalStateException("Idempotency-Key was already used for another orchestration request");
            }
            return new StartResult(existing, false);
        }
        Map<UUID, OrchestrationRun> nextRuns = limits.prepareForAdmission(runs);
        OrchestrationRun created = factory.get();
        if (!key.equals(created.idempotencyKey()) || !fingerprint.equals(created.requestFingerprint())) {
            throw new IllegalArgumentException("created run does not match its idempotency request");
        }
        nextRuns.put(created.id(), created);
        persist(nextRuns.values());
        replaceState(nextRuns);
        return new StartResult(created, true);
    }

    @Override
    public synchronized Optional<OrchestrationRun> find(UUID runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public synchronized OrchestrationRun update(UUID runId, UnaryOperator<OrchestrationRun> transition) {
        OrchestrationRun current = Optional.ofNullable(runs.get(runId))
                .orElseThrow(() -> new NoSuchElementException("Unknown orchestration run"));
        OrchestrationRun updated = transition.apply(current);
        if (!runId.equals(updated.id())) throw new IllegalArgumentException("run transition changed identity");
        if (updated.revision() <= current.revision()) throw new IllegalArgumentException("run revision must advance");
        Map<UUID, OrchestrationRun> nextRuns = new LinkedHashMap<>(runs);
        nextRuns.put(runId, updated);
        persist(nextRuns.values());
        runs.clear();
        runs.putAll(nextRuns);
        return updated;
    }

    @Override
    public synchronized List<OrchestrationRun> nonTerminalRuns() {
        return runs.values().stream().filter(run -> !run.status().isTerminal()).toList();
    }

    private void load() {
        if (!Files.exists(stateFile)) return;
        try {
            List<OrchestrationRun> restored = mapper.readValue(stateFile.toFile(), RUN_LIST);
            Map<UUID, OrchestrationRun> loaded = new LinkedHashMap<>();
            Map<String, UUID> loadedKeys = new LinkedHashMap<>();
            for (OrchestrationRun run : restored) {
                loaded.put(run.id(), run);
                UUID duplicate = loadedKeys.putIfAbsent(run.idempotencyKey(), run.id());
                if (duplicate != null && !duplicate.equals(run.id())) {
                    throw new IllegalStateException("duplicate orchestration idempotency key");
                }
            }
            Map<UUID, OrchestrationRun> compacted = limits.compactRetained(loaded);
            if (compacted.size() != loaded.size()) persist(compacted.values());
            replaceState(compacted);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("unable to load orchestration state", failure);
        }
    }

    private void replaceState(Map<UUID, OrchestrationRun> nextRuns) {
        runs.clear();
        runs.putAll(nextRuns);
        idempotencyIndex.clear();
        for (OrchestrationRun run : runs.values()) {
            UUID duplicate = idempotencyIndex.putIfAbsent(run.idempotencyKey(), run.id());
            if (duplicate != null && !duplicate.equals(run.id())) {
                throw new IllegalStateException("duplicate orchestration idempotency key");
            }
        }
    }

    private void persist(java.util.Collection<OrchestrationRun> snapshot) {
        try {
            Path parent = stateFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, stateFile.getFileName().toString(), ".tmp");
            try {
                mapper.writeValue(temporary.toFile(), new ArrayList<>(snapshot));
                atomicReplacer.replace(temporary, stateFile);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("unable to persist orchestration state", failure);
        }
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @FunctionalInterface
    interface AtomicReplacer {
        void replace(Path source, Path target) throws IOException;
    }
}
