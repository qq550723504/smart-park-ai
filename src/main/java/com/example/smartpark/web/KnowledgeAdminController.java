package com.example.smartpark.web;

import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.PublicMetadata;
import com.example.smartpark.port.knowledge.KnowledgeAdminPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/knowledge")
@ConditionalOnBean(KnowledgeAdminPort.class)
public class KnowledgeAdminController {
    private final KnowledgeAdminPort knowledge;
    private final AuditTrail auditTrail;
    private final Clock clock;

    @Autowired
    public KnowledgeAdminController(KnowledgeAdminPort knowledge, AuditTrail auditTrail) {
        this(knowledge, auditTrail, Clock.systemUTC());
    }

    KnowledgeAdminController(KnowledgeAdminPort knowledge, AuditTrail auditTrail, Clock clock) {
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping
    public List<KnowledgeMetadataResponse> list(@RequestHeader("X-Demo-Role") String role) {
        DemoRole.require(role, DemoRole.ADMIN);
        return knowledge.list().stream().map(KnowledgeAdminController::metadata).toList();
    }

    @PostMapping
    public KnowledgeMetadataResponse create(
            @Valid @RequestBody KnowledgeCreateRequest request,
            @RequestHeader("X-Demo-Role") String role) {
        DemoRole.require(role, DemoRole.ADMIN);
        KnowledgeDocument saved = knowledge.save(new KnowledgeDocument(
                request.id(), request.title(), request.content(), request.tags(), Instant.now(clock)));
        auditTrail.record(DemoRole.parse(role).name(), "CREATE_KNOWLEDGE", saved.id(), "SUCCESS");
        return metadata(new KnowledgeAdminPort.ManagedDocument(saved, true));
    }

    @PatchMapping("/{documentId}/active")
    public KnowledgeMetadataResponse active(
            @PathVariable String documentId,
            @Valid @RequestBody KnowledgeActiveRequest request,
            @RequestHeader("X-Demo-Role") String role) {
        DemoRole.require(role, DemoRole.ADMIN);
        var updated = knowledge.setActive(documentId, request.active());
        auditTrail.record(DemoRole.parse(role).name(), "SET_KNOWLEDGE_ACTIVE", documentId, "SUCCESS");
        return metadata(updated);
    }

    private static KnowledgeMetadataResponse metadata(KnowledgeAdminPort.ManagedDocument managed) {
        var document = managed.document();
        return new KnowledgeMetadataResponse(
                document.id(), document.title(), document.tags(), document.updatedAt(), managed.active());
    }

    public record KnowledgeCreateRequest(
            @NotBlank @Pattern(regexp = "KD-[A-Z0-9-]{1,120}") String id,
            @NotBlank @Size(max = PublicMetadata.MAX_TITLE_LENGTH) String title,
            @NotBlank @Size(max = 2000) String content,
            @NotEmpty List<@NotBlank String> tags) {
        @AssertTrue(message = "title must be bounded public metadata")
        public boolean hasSafePublicTitle() {
            return PublicMetadata.isSafeTitle(title);
        }
    }
    public record KnowledgeActiveRequest(@NotNull Boolean active) { }
    public record KnowledgeMetadataResponse(
            String id, String title, List<String> tags, Instant updatedAt, boolean active) { }
}
