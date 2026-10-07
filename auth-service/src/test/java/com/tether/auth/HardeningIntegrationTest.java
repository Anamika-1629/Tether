package com.tether.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Brute-force limits, tenant-scoped endpoints, join-code rotation, metrics and docs. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HardeningIntegrationTest {

    private static final AtomicInteger IPS = new AtomicInteger(1);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry metrics;

    /** Each test gets its own client IP so the per-IP limits never leak between tests. */
    private final String ip = "10.0.0." + IPS.getAndIncrement();
    private final RequestPostProcessor fromIp = r -> { r.setRemoteAddr(ip); return r; };

    private static String uniqueEmail(String who) {
        return who + "+" + UUID.randomUUID() + "@acme.test";
    }

    private ResultActions register(String body) throws Exception {
        return mvc.perform(post("/auth/register").with(fromIp).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login").with(fromIp).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private JsonNode registerOwner(String email, String org) throws Exception {
        return json.readTree(register("""
                {"email":"%s","password":"correct-horse","displayName":"Alice","organizationName":"%s"}
                """.formatted(email, org)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode registerMember(String email, String joinCode) throws Exception {
        return json.readTree(register("""
                {"email":"%s","password":"correct-horse","displayName":"Bob","joinCode":"%s"}
                """.formatted(email, joinCode)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private static String bearer(JsonNode auth) {
        return "Bearer " + auth.get("accessToken").asText();
    }

    @Test
    void fiveFailedLoginsLockTheAccountForThatClientEvenWithTheRightPassword() throws Exception {
        String email = uniqueEmail("alice");
        registerOwner(email, "Acme");
        for (int i = 0; i < 5; i++) login(email, "wrong-password").andExpect(status().isUnauthorized());

        login(email, "correct-horse")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("Too many failed attempts. Try again later."));
    }

    @Test
    void successfulLoginResetsTheFailureCount() throws Exception {
        String email = uniqueEmail("alice");
        registerOwner(email, "Acme");
        for (int i = 0; i < 4; i++) login(email, "wrong-password").andExpect(status().isUnauthorized());
        login(email, "correct-horse").andExpect(status().isOk());
        for (int i = 0; i < 4; i++) login(email, "wrong-password").andExpect(status().isUnauthorized());
        login(email, "correct-horse").andExpect(status().isOk());
    }

    @Test
    void joinCodeGuessingIsCappedPerClient() throws Exception {
        String realCode = registerOwner(uniqueEmail("alice"), "Acme").at("/tenant/joinCode").asText();
        for (int i = 0; i < 10; i++) {
            register("""
                    {"email":"%s","password":"correct-horse","displayName":"Eve","joinCode":"GUESS%03d"}
                    """.formatted(uniqueEmail("eve"), i)).andExpect(status().isBadRequest());
        }
        // Blocked now, even with the right code
        register("""
                {"email":"%s","password":"correct-horse","displayName":"Eve","joinCode":"%s"}
                """.formatted(uniqueEmail("eve"), realCode))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void membersListsOnlyTheCallersOwnTenant() throws Exception {
        JsonNode alice = registerOwner(uniqueEmail("alice"), "Acme");
        JsonNode bob = registerMember(uniqueEmail("bob"), alice.at("/tenant/joinCode").asText());
        JsonNode outsider = registerOwner(uniqueEmail("mallory"), "Evil Corp");

        String body = mvc.perform(get("/auth/tenant/members").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(alice.at("/user/id").asText(), bob.at("/user/id").asText())
                .doesNotContain(outsider.at("/user/id").asText())
                .doesNotContain("password");
    }

    @Test
    void ownerCanRotateJoinCodeAndTheOldCodeStopsWorking() throws Exception {
        JsonNode alice = registerOwner(uniqueEmail("alice"), "Acme");
        String oldCode = alice.at("/tenant/joinCode").asText();

        String body = mvc.perform(post("/auth/tenant/join-code/rotate").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newCode = json.readTree(body).get("joinCode").asText();
        assertThat(newCode).hasSize(8).isNotEqualTo(oldCode);

        register("""
                {"email":"%s","password":"correct-horse","displayName":"Bob","joinCode":"%s"}
                """.formatted(uniqueEmail("bob"), oldCode)).andExpect(status().isBadRequest());
        JsonNode bob = registerMember(uniqueEmail("bob"), newCode);
        assertThat(bob.at("/tenant/id").asText()).isEqualTo(alice.at("/tenant/id").asText());
    }

    @Test
    void memberCannotRotateJoinCode() throws Exception {
        JsonNode alice = registerOwner(uniqueEmail("alice"), "Acme");
        JsonNode bob = registerMember(uniqueEmail("bob"), alice.at("/tenant/joinCode").asText());

        mvc.perform(post("/auth/tenant/join-code/rotate").header("Authorization", bearer(bob)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Only the organization owner can rotate the join code"));
    }

    @Test
    void tenantEndpointsRequireAToken() throws Exception {
        mvc.perform(get("/auth/tenant/members")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/tenant/join-code/rotate")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginsAndRegistrationsAreCountedInMetrics() throws Exception {
        double successesBefore = count("tether.auth.logins", "outcome", "success");
        double failuresBefore = count("tether.auth.logins", "outcome", "failure");
        double orgsBefore = count("tether.auth.registrations", "type", "new_org");

        String email = uniqueEmail("alice");
        registerOwner(email, "Acme");
        login(email, "correct-horse").andExpect(status().isOk());
        login(email, "wrong-password").andExpect(status().isUnauthorized());

        assertThat(count("tether.auth.logins", "outcome", "success")).isEqualTo(successesBefore + 1);
        assertThat(count("tether.auth.logins", "outcome", "failure")).isEqualTo(failuresBefore + 1);
        assertThat(count("tether.auth.registrations", "type", "new_org")).isEqualTo(orgsBefore + 1);
    }

    @Test
    void openApiDocsArePublicAndListTheEndpoints() throws Exception {
        String docs = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(docs).contains("/auth/register", "/auth/login", "/auth/me", "/auth/tenant/members");
    }

    private double count(String name, String tag, String value) {
        var counter = metrics.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }
}
