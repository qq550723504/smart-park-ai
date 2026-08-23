package com.example.smartpark.web;

import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.feedback.FeedbackEntry;
import com.example.smartpark.feedback.FeedbackRating;
import com.example.smartpark.feedback.FeedbackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {
    private final FeedbackService feedback;
    private final AuditTrail auditTrail;

    public FeedbackController(FeedbackService feedback, AuditTrail auditTrail) {
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    @PostMapping
    public FeedbackEntry record(
            @Valid @RequestBody FeedbackRequest request,
            @RequestHeader("X-Demo-Role") String role) {
        DemoRole.require(role, DemoRole.CUSTOMER_AGENT, DemoRole.APPROVER, DemoRole.ADMIN);
        FeedbackEntry entry = feedback.record(request.targetType(), request.targetId(), request.rating(), DemoRole.parse(role).name());
        auditTrail.record(DemoRole.parse(role).name(), "RECORD_FEEDBACK", request.targetId(), "SUCCESS");
        return entry;
    }

    @GetMapping
    public List<FeedbackEntry> entries(@RequestHeader("X-Demo-Role") String role) {
        DemoRole.require(role, DemoRole.ADMIN);
        return feedback.entries();
    }

    public record FeedbackRequest(
            @NotBlank @Pattern(regexp = "CUSTOMER_SESSION|ALERT_WORKFLOW") String targetType,
            @NotBlank String targetId,
            @NotNull FeedbackRating rating) { }
}
