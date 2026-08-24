package com.example.smartpark.adapter.mcp;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.model.energy.EnergyReading;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.energy.EnergyPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SmartParkMcpTools {
    private static final Logger log = LoggerFactory.getLogger(SmartParkMcpTools.class);
    private static final Pattern ALERT_ID = Pattern.compile("ALT-[A-Z0-9-]{1,120}");
    private static final Pattern METER_ID = Pattern.compile("DEV-[A-Z0-9-]{1,120}");
    private static final int MAX_KNOWLEDGE_QUERY_LENGTH = 500;
    private static final int MAX_KNOWLEDGE_MATCHES = 5;

    private final AlertPort alertPort;
    private final EnergyPort energyPort;
    private final KnowledgePort knowledgePort;

    public SmartParkMcpTools(AlertPort alertPort, EnergyPort energyPort, KnowledgePort knowledgePort) {
        this.alertPort = alertPort;
        this.energyPort = energyPort;
        this.knowledgePort = knowledgePort;
    }

    @Tool(name = "smartpark_lookup_alert", description = "Read allowlisted metadata for one Mock park alert. Returns no summary, evidence, security event, identity data, or control capability.")
    public McpToolResults.AlertLookupResult lookupAlert(@ToolParam(description = "Alert ID matching ALT-[A-Z0-9-], for example ALT-ENERGY-001") String alertId) {
        String normalized = normalize(alertId);
        if (!ALERT_ID.matcher(normalized).matches()) return new McpToolResults.AlertLookupResult(false, null, McpToolResults.invalidArgument(), McpToolResults.NOTICE);
        try {
            Alert alert = alertPort.getAlert(normalized);
            var data = new McpToolResults.AlertData(alert.id(), alert.parkId(), alert.buildingId(), alert.deviceId(), alert.classification().name(), alert.riskHint().name(), alert.occurredAt());
            return new McpToolResults.AlertLookupResult(true, data, null, McpToolResults.NOTICE);
        } catch (IllegalArgumentException exception) {
            return new McpToolResults.AlertLookupResult(false, null, McpToolResults.notFound(), McpToolResults.NOTICE);
        } catch (RuntimeException exception) {
            logFailure("smartpark_lookup_alert", exception);
            return new McpToolResults.AlertLookupResult(false, null, McpToolResults.internalError(), McpToolResults.NOTICE);
        }
    }

    @Tool(name = "smartpark_lookup_energy", description = "Read one Mock park energy reading and derived variance. Read-only; no device control.")
    public McpToolResults.EnergyLookupResult lookupEnergy(@ToolParam(description = "Meter ID matching DEV-[A-Z0-9-], for example DEV-ENERGY-001") String meterId) {
        String normalized = normalize(meterId);
        if (!METER_ID.matcher(normalized).matches()) return new McpToolResults.EnergyLookupResult(false, null, McpToolResults.invalidArgument(), McpToolResults.NOTICE);
        try {
            EnergyReading reading = energyPort.getLatestEnergyReading(normalized);
            var data = new McpToolResults.EnergyData(reading.meterId(), reading.parkId(), reading.buildingId(), reading.measuredAt(), reading.currentKwh(), reading.baselineKwh(), reading.peakDemandKw(), reading.varianceKwh(), reading.varianceRatio());
            return new McpToolResults.EnergyLookupResult(true, data, null, McpToolResults.NOTICE);
        } catch (IllegalArgumentException exception) {
            return new McpToolResults.EnergyLookupResult(false, null, McpToolResults.notFound(), McpToolResults.NOTICE);
        } catch (RuntimeException exception) {
            logFailure("smartpark_lookup_energy", exception);
            return new McpToolResults.EnergyLookupResult(false, null, McpToolResults.internalError(), McpToolResults.NOTICE);
        }
    }

    @Tool(name = "smartpark_search_knowledge", description = "Search Mock park knowledge metadata. Returns titles and tags only, never document content.")
    public McpToolResults.KnowledgeSearchResult searchKnowledge(@ToolParam(description = "Bounded knowledge search query") String query, @ToolParam(description = "Knowledge domain: CUSTOMER_SERVICE or ALERT_OPERATIONS") String domain) {
        String normalizedQuery = normalize(query);
        KnowledgeDomain parsed;
        try {
            parsed = KnowledgeDomain.valueOf(normalize(domain).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return new McpToolResults.KnowledgeSearchResult(false, null, McpToolResults.invalidArgument(), McpToolResults.NOTICE);
        }
        if (normalizedQuery.isEmpty() || normalizedQuery.length() > MAX_KNOWLEDGE_QUERY_LENGTH) return new McpToolResults.KnowledgeSearchResult(false, null, McpToolResults.invalidArgument(), McpToolResults.NOTICE);
        try {
            List<McpToolResults.KnowledgeMatchData> matches = knowledgePort.rankedSearch(parsed, normalizedQuery).stream()
                    .sorted(Comparator.comparingDouble(KnowledgeMatch::score).reversed().thenComparing(KnowledgeMatch::documentId))
                    .limit(MAX_KNOWLEDGE_MATCHES)
                    .map(this::mapKnowledge).toList();
            return new McpToolResults.KnowledgeSearchResult(true, new McpToolResults.KnowledgeData(parsed.name(), matches), null, McpToolResults.NOTICE);
        } catch (RuntimeException exception) {
            logFailure("smartpark_search_knowledge", exception);
            return new McpToolResults.KnowledgeSearchResult(false, null, McpToolResults.internalError(), McpToolResults.NOTICE);
        }
    }

    private McpToolResults.KnowledgeMatchData mapKnowledge(KnowledgeMatch match) {
        var document = match.document();
        return new McpToolResults.KnowledgeMatchData(document.id(), document.title(), document.domain().name(), document.tags(), match.score(), document.updatedAt());
    }

    private static String normalize(String value) { return value == null ? "" : value.trim(); }

    private static void logFailure(String toolName, RuntimeException exception) {
        log.warn("MCP tool failed: tool={}, exceptionType={}", toolName, exception.getClass().getSimpleName());
    }
}
