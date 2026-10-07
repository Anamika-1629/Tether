package com.tether.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IncidentAuthIntegrationTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long!!";
    private static final String BODY = "{\"title\":\"Checkout API 500s\",\"severity\":\"SEV2\"}";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    /** Mints a token the way auth-service does. Pass null to omit a claim. */
    private static String token(String secret, String issuer, String tenantId, String email, Duration ttl) {
        NimbusJwtEncoder enc = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer).subject(UUID.randomUUID().toString())
                .issuedAt(now.minus(Duration.ofMinutes(5))).expiresAt(now.plus(ttl));
        if (tenantId != null) claims.claim("tenantId", tenantId);
        if (email != null) claims.claim("email", email);
        return enc.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    private static String valid(String tenantId, String email) {
        return token(SECRET, "tether-auth", tenantId, email, Duration.ofHours(1));
    }

    private static String bearer(String t) { return "Bearer " + t; }

    private String createIncident(String tenant, String email) throws Exception {
        String res = mvc.perform(post("/incidents").header("Authorization", bearer(valid(tenant, email)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    // ---- rejected tokens ----

    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(get("/incidents")).andExpect(status().isUnauthorized());
    }

    @Test
    void oldHeaderIdentityIsNoLongerAccepted() throws Exception {
        mvc.perform(get("/incidents").header("X-Tenant-Id", "acme").header("X-Actor", "mallory"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherSecretIs401() throws Exception {
        String t = token("a-completely-different-secret-32-bytes!!", "tether-auth",
                UUID.randomUUID().toString(), "x@y.test", Duration.ofHours(1));
        mvc.perform(get("/incidents").header("Authorization", bearer(t))).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIs401() throws Exception {
        String t = token(SECRET, "tether-auth", UUID.randomUUID().toString(), "x@y.test", Duration.ofMinutes(-1));
        mvc.perform(get("/incidents").header("Authorization", bearer(t))).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongIssuerIs401() throws Exception {
        String t = token(SECRET, "someone-else", UUID.randomUUID().toString(), "x@y.test", Duration.ofHours(1));
        mvc.perform(get("/incidents").header("Authorization", bearer(t))).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutTenantClaimIs401() throws Exception {
        String t = token(SECRET, "tether-auth", null, "x@y.test", Duration.ofHours(1));
        mvc.perform(get("/incidents").header("Authorization", bearer(t))).andExpect(status().isUnauthorized());
    }

    // ---- identity comes from the token ----

    @Test
    void actorInAuditLogIsTheTokenEmailNotAHeader() throws Exception {
        String tenant = UUID.randomUUID().toString();
        String id = mvc.perform(post("/incidents").header("Authorization", bearer(valid(tenant, "alice@acme.test")))
                        .header("X-Actor", "mallory").header("X-Tenant-Id", "other")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID incident = UUID.fromString(json.readTree(id).get("id").asText());

        mvc.perform(patch("/incidents/" + incident).header("Authorization", bearer(valid(tenant, "bob@acme.test")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INVESTIGATING\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/incidents/" + incident + "/audit").header("Authorization", bearer(valid(tenant, "alice@acme.test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actor").value("alice@acme.test"))
                .andExpect(jsonPath("$[1].actor").value("bob@acme.test"))
                .andExpect(jsonPath("$[1].field").value("status"));
    }

    // ---- tenant isolation ----

    @Test
    void anotherTenantCannotSeeOrChangeAnIncident() throws Exception {
        String tenantA = UUID.randomUUID().toString();
        String tenantB = UUID.randomUUID().toString();
        String id = createIncident(tenantA, "alice@a.test");
        String authB = bearer(valid(tenantB, "eve@b.test"));

        mvc.perform(get("/incidents/" + id).header("Authorization", authB)).andExpect(status().isNotFound());
        mvc.perform(get("/incidents/" + id + "/audit").header("Authorization", authB)).andExpect(status().isNotFound());
        mvc.perform(patch("/incidents/" + id).header("Authorization", authB)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/incidents").header("Authorization", authB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // and tenant A still sees it, unchanged
        mvc.perform(get("/incidents/" + id).header("Authorization", bearer(valid(tenantA, "alice@a.test"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void listOnlyReturnsOwnTenantsIncidents() throws Exception {
        String tenantA = UUID.randomUUID().toString();
        String tenantB = UUID.randomUUID().toString();
        createIncident(tenantA, "alice@a.test");
        createIncident(tenantA, "alice@a.test");
        createIncident(tenantB, "eve@b.test");

        String res = mvc.perform(get("/incidents").header("Authorization", bearer(valid(tenantA, "alice@a.test"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(res)).hasSize(2);
    }
}
