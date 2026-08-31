package com.example.smartpark.analytics.agent.time;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Bounded, version-pinned HTTP client for the internal parser sidecar. */
public final class JioNlpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RestClient client;
    private final int maxResponseBytes;
    private final String expectedProvider;
    private final String expectedVersion;

    public JioNlpClient(String baseUrl, Duration connectTimeout, Duration readTimeout,
                        int maxResponseBytes, String expectedProvider, String expectedVersion) {
        if (baseUrl == null || baseUrl.isBlank() || connectTimeout == null || readTimeout == null
                || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero() || maxResponseBytes < 1024) {
            throw new IllegalArgumentException("invalid parser client configuration");
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.maxResponseBytes = maxResponseBytes;
        this.expectedProvider = expectedProvider;
        this.expectedVersion = expectedVersion;
    }

    public TimeParserResponse resolve(TimeParserRequest request) {
        try {
            byte[] body = client.post().uri("/v1/time-intents:resolve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.isError()) {
                            throw new RestClientException("parser returned HTTP " + status.value());
                        }
                        return readLimited(response.getBody(), maxResponseBytes);
                    });
            if (body == null || body.length == 0 || body.length > maxResponseBytes) {
                throw new TimeParserInvalidResponseException("parser response size is invalid");
            }
            TimeParserResponse response = decode(body);
            validate(request, response);
            return response;
        } catch (TimeParserInvalidResponseException invalid) {
            throw invalid;
        } catch (RestClientException unavailable) {
            throw new TimeParserUnavailableException("time parser sidecar unavailable", unavailable);
        } catch (RuntimeException invalid) {
            throw new TimeParserInvalidResponseException("parser response is invalid", invalid);
        }
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new TimeParserInvalidResponseException("parser response exceeds configured limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private TimeParserResponse decode(byte[] body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            List<TimeParserMention> mentions = new ArrayList<>();
            JsonNode values = root.get("mentions");
            if (values != null && !values.isArray()) {
                throw new IllegalArgumentException("mentions must be an array");
            }
            if (values != null) {
                values.forEach(node -> mentions.add(new TimeParserMention(
                        requiredText(node, "text"), requiredInt(node, "start"), requiredInt(node, "end"),
                        optionalText(node, "type"), optionalText(node, "definition"),
                        optionalText(node, "fromInclusive"), optionalText(node, "toExclusive"),
                        node.path("empty").asBoolean(false))));
            }
            return new TimeParserResponse(requiredText(root, "provider"), requiredText(root, "providerVersion"),
                    requiredText(root, "referenceInstant"), requiredText(root, "timezone"),
                    requiredText(root, "status"), mentions, optionalText(root, "reasonCode"));
        } catch (TimeParserInvalidResponseException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new TimeParserInvalidResponseException("parser JSON schema is invalid", invalid);
        }
    }

    private void validate(TimeParserRequest request, TimeParserResponse response) {
        if (!expectedProvider.equals(response.provider()) || !expectedVersion.equals(response.providerVersion())) {
            throw new TimeParserInvalidResponseException("parser provider or version mismatch");
        }
        if (!request.referenceInstant().equals(response.referenceInstant())
                || !request.timezone().equals(response.timezone())) {
            throw new TimeParserInvalidResponseException("parser request echo mismatch");
        }
        if (!List.of("NONE", "PARSED", "UNSUPPORTED", "MULTIPLE", "AMBIGUOUS", "EMPTY")
                .contains(response.status())) {
            throw new TimeParserInvalidResponseException("parser status is unsupported");
        }
        int codePoints = request.question().codePointCount(0, request.question().length());
        for (TimeParserMention mention : response.mentions()) {
            if (mention.start() < 0 || mention.end() <= mention.start() || mention.end() > codePoints
                    || mention.fromInclusive() == null && mention.toExclusive() != null
                    || mention.fromInclusive() != null && mention.toExclusive() == null) {
                throw new TimeParserInvalidResponseException("parser mention span or range is invalid");
            }
            if (mention.empty() && (mention.fromInclusive() == null
                    || !mention.fromInclusive().equals(mention.toExclusive()))) {
                throw new TimeParserInvalidResponseException("empty parser mention must have equal boundaries");
            }
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new TimeParserInvalidResponseException("missing parser field: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.isTextual() ? value.asText() : null;
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new TimeParserInvalidResponseException("missing parser integer field: " + field);
        }
        return value.intValue();
    }
}
