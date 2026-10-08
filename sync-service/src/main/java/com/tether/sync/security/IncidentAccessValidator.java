package com.tether.sync.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Verifies that the caller may open a given incident by asking the Incident Service,
 * which enforces tenant isolation. This check FAILS CLOSED: if the Incident Service
 * cannot give a clear yes, nobody gets into the room.
 *
 * Three outcomes, because the client reacts differently to each:
 *  - ALLOWED     incident service answered 2xx
 *  - DENIED      incident service answered 401/403/404/...: caller has no access (client stops retrying)
 *  - UNAVAILABLE could not verify (timeout, connection refused, 5xx, 429): deny for now,
 *                but tell the client to retry later
 */
@Component
public class IncidentAccessValidator {

    public enum Decision { ALLOWED, DENIED, UNAVAILABLE }

    private static final Logger log = LoggerFactory.getLogger(IncidentAccessValidator.class);

    private final RestClient restClient;
    private final boolean enabled;

    public IncidentAccessValidator(
            @Value("${tether.incident.service-url:http://localhost:8082}") String incidentServiceUrl,
            @Value("${tether.incident.check-enabled:true}") boolean enabled,
            @Value("${tether.incident.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${tether.incident.read-timeout-ms:3000}") int readTimeoutMs) {
        this.enabled = enabled;

        // Without timeouts a hung Incident Service would hang every WebSocket connect.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restClient = RestClient.builder().baseUrl(incidentServiceUrl).requestFactory(factory).build();

        if (!enabled) {
            log.warn("INCIDENT_CHECK_ENABLED=false: incident access is NOT verified. Use for local testing only.");
        }
    }

    public Decision check(String incidentId, String token) {
        if (!enabled) {
            return Decision.ALLOWED;
        }
        if (incidentId == null || token == null) {
            return Decision.DENIED;
        }

        try {
            HttpStatusCode status = restClient.get()
                    .uri("/incidents/{id}", incidentId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange((request, response) -> response.getStatusCode());

            if (status.is2xxSuccessful()) {
                return Decision.ALLOWED;
            }
            if (status.is5xxServerError() || status.value() == 429) {
                log.warn("Incident service returned {} while checking incident {}: denying, client should retry",
                        status.value(), incidentId);
                return Decision.UNAVAILABLE;
            }
            return Decision.DENIED;
        } catch (Exception e) {
            log.warn("Incident access verification failed for incident {}: {}. Denying, client should retry.",
                    incidentId, e.getMessage());
            return Decision.UNAVAILABLE;
        }
    }
}
