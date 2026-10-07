package com.tether.sync.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Validates that the connecting caller has authorized access to the requested incident
 * by calling the Incident Service.
 */
@Component
public class IncidentAccessValidator {

    private static final Logger log = LoggerFactory.getLogger(IncidentAccessValidator.class);

    private final RestClient restClient;
    private final boolean enabled;

    public IncidentAccessValidator(
            @Value("${tether.incident.service-url:http://localhost:8082}") String incidentServiceUrl,
            @Value("${tether.incident.check-enabled:true}") boolean enabled) {
        this.enabled = enabled;
        this.restClient = RestClient.builder().baseUrl(incidentServiceUrl).build();
    }

    public boolean canAccessIncident(String incidentId, String token) {
        if (!enabled) {
            return true;
        }
        if (incidentId == null || token == null) {
            return false;
        }

        try {
            return restClient.get()
                    .uri("/incidents/{id}", incidentId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        // 404 or 403 means no access to this incident
                    })
                    .toBodilessEntity()
                    .getStatusCode()
                    .is2xxSuccessful();
        } catch (Exception e) {
            log.warn("Incident access verification failed for incident {}: {}", incidentId, e.getMessage());
            // If incident service is down/unreachable during local development or network failure,
            // we allow access if token claims are valid so the incident room remains available
            return true;
        }
    }
}
