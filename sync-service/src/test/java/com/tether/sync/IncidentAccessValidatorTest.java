package com.tether.sync;

import com.sun.net.httpserver.HttpServer;
import com.tether.sync.security.IncidentAccessValidator;
import com.tether.sync.security.IncidentAccessValidator.Decision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runs the validator against a real local HTTP server standing in for the Incident Service. */
class IncidentAccessValidatorTest {

    private static final String INCIDENT = "11111111-2222-3333-4444-555555555555";

    private HttpServer server;
    private final AtomicReference<String> seenAuthHeader = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    /** Starts a stub Incident Service that answers /incidents/* with the given status after an optional delay. */
    private String startStub(int status, long delayMs) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/incidents", exchange -> {
            seenAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                if (delayMs > 0) Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private IncidentAccessValidator validator(String url) {
        return new IncidentAccessValidator(url, true, 500, 500);
    }

    @Test
    void allowsWhenIncidentServiceReturns200AndForwardsTheToken() throws Exception {
        String url = startStub(200, 0);
        assertEquals(Decision.ALLOWED, validator(url).check(INCIDENT, "tok123"));
        assertEquals("Bearer tok123", seenAuthHeader.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void deniesWhenIncidentServiceSaysNoAccess(int status) throws Exception {
        String url = startStub(status, 0);
        assertEquals(Decision.DENIED, validator(url).check(INCIDENT, "tok"));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 429})
    void failsClosedAsUnavailableWhenIncidentServiceIsErroring(int status) throws Exception {
        String url = startStub(status, 0);
        assertEquals(Decision.UNAVAILABLE, validator(url).check(INCIDENT, "tok"));
    }

    @Test
    void failsClosedWhenIncidentServiceIsUnreachable() throws Exception {
        String url = startStub(200, 0);
        server.stop(0); // port is now closed: connection refused
        assertEquals(Decision.UNAVAILABLE, validator(url).check(INCIDENT, "tok"));
    }

    @Test
    void failsClosedWhenIncidentServiceIsTooSlow() throws Exception {
        String url = startStub(200, 2000);
        IncidentAccessValidator v = new IncidentAccessValidator(url, true, 500, 300);
        assertEquals(Decision.UNAVAILABLE, v.check(INCIDENT, "tok"));
    }

    @Test
    void deniesMissingTokenOrIncident() {
        IncidentAccessValidator v = validator("http://127.0.0.1:1");
        assertEquals(Decision.DENIED, v.check(INCIDENT, null));
        assertEquals(Decision.DENIED, v.check(null, "tok"));
    }

    @Test
    void checkCanBeDisabledForLocalTesting() {
        IncidentAccessValidator v = new IncidentAccessValidator("http://127.0.0.1:1", false, 500, 500);
        assertEquals(Decision.ALLOWED, v.check(INCIDENT, "tok"));
    }
}
