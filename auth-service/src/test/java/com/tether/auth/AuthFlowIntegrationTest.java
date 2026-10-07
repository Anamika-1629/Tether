package com.tether.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tether.auth.repo.UserAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtDecoder decoder;
    @Autowired UserAccountRepository users;

    private static String uniqueEmail(String who) {
        return who + "+" + UUID.randomUUID() + "@acme.test";
    }

    private ResultActions register(String body) throws Exception {
        return mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private JsonNode registerOwner(String email, String org) throws Exception {
        String body = register("""
                {"email":"%s","password":"correct-horse","displayName":"Alice","organizationName":"%s"}
                """.formatted(email, org))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    void ownerRegistrationCreatesTenantAndReturnsTokenWithUserAndTenantClaims() throws Exception {
        JsonNode res = registerOwner(uniqueEmail("alice"), "Acme Corp!");

        assertThat(res.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(res.at("/user/role").asText()).isEqualTo("OWNER");
        assertThat(res.at("/tenant/name").asText()).isEqualTo("Acme Corp!");
        assertThat(res.at("/tenant/slug").asText()).startsWith("acme-corp");
        assertThat(res.at("/tenant/joinCode").asText()).hasSize(8);

        Jwt jwt = decoder.decode(res.get("accessToken").asText());
        assertThat(jwt.getClaimAsString("userId")).isEqualTo(res.at("/user/id").asText());
        assertThat(jwt.getSubject()).isEqualTo(res.at("/user/id").asText());
        assertThat(jwt.getClaimAsString("tenantId")).isEqualTo(res.at("/tenant/id").asText());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("OWNER");
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
        assertThat(jwt.getExpiresAt().toString()).isEqualTo(res.get("expiresAt").asText());
    }

    @Test
    void memberJoinsExistingTenantWithJoinCode() throws Exception {
        JsonNode owner = registerOwner(uniqueEmail("alice"), "Globex");
        String joinCode = owner.at("/tenant/joinCode").asText();

        String body = register("""
                {"email":"%s","password":"hunter2hunter2","displayName":"Bob","joinCode":"%s"}
                """.formatted(uniqueEmail("bob"), joinCode.toLowerCase()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").value("MEMBER"))
                .andExpect(jsonPath("$.tenant.id").value(owner.at("/tenant/id").asText()))
                .andExpect(jsonPath("$.tenant.joinCode").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        Jwt jwt = decoder.decode(json.readTree(body).get("accessToken").asText());
        assertThat(jwt.getClaimAsString("tenantId")).isEqualTo(owner.at("/tenant/id").asText());
    }

    @Test
    void sameOrganizationNameGetsSeparateTenantsWithDistinctSlugs() throws Exception {
        JsonNode a = registerOwner(uniqueEmail("a"), "Initech");
        JsonNode b = registerOwner(uniqueEmail("b"), "Initech");
        assertThat(a.at("/tenant/id").asText()).isNotEqualTo(b.at("/tenant/id").asText());
        assertThat(a.at("/tenant/slug").asText()).isNotEqualTo(b.at("/tenant/slug").asText());
    }

    @Test
    void passwordIsStoredAsBcryptHashNotPlaintext() throws Exception {
        String email = uniqueEmail("alice");
        registerOwner(email, "Hooli");
        String hash = users.findByEmail(email).orElseThrow().getPasswordHash();
        assertThat(hash).startsWith("$2").doesNotContain("correct-horse");
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        String email = uniqueEmail("alice");
        registerOwner(email, "Umbrella");
        register("""
                {"email":"%s","password":"correct-horse","displayName":"Alice","organizationName":"Other"}
                """.formatted(email.toUpperCase()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("An account with this email already exists"));
    }

    @Test
    void registerRequiresExactlyOneOfOrganizationNameOrJoinCode() throws Exception {
        register("""
                {"email":"%s","password":"correct-horse","displayName":"Alice"}
                """.formatted(uniqueEmail("x")))
                .andExpect(status().isBadRequest());
        register("""
                {"email":"%s","password":"correct-horse","displayName":"Alice","organizationName":"A","joinCode":"ABCDEFGH"}
                """.formatted(uniqueEmail("x")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownJoinCodeIsRejected() throws Exception {
        register("""
                {"email":"%s","password":"correct-horse","displayName":"Eve","joinCode":"ZZZZZZZZ"}
                """.formatted(uniqueEmail("eve")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Join code is not valid"));
    }

    @Test
    void invalidRegistrationInputReturnsFieldErrors() throws Exception {
        register("""
                {"email":"not-an-email","password":"short","displayName":"","organizationName":"Acme"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").exists())
                .andExpect(jsonPath("$.fields.password").exists())
                .andExpect(jsonPath("$.fields.displayName").exists());
    }

    @Test
    void loginReturnsTokenForCorrectPasswordIgnoringEmailCase() throws Exception {
        String email = uniqueEmail("alice");
        JsonNode owner = registerOwner(email, "Wayne");
        String body = login("  " + email.toUpperCase() + " ", "correct-horse")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andReturn().getResponse().getContentAsString();
        Jwt jwt = decoder.decode(json.readTree(body).get("accessToken").asText());
        assertThat(jwt.getClaimAsString("tenantId")).isEqualTo(owner.at("/tenant/id").asText());
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSame401() throws Exception {
        String email = uniqueEmail("alice");
        registerOwner(email, "Stark");
        String wrongPassword = login(email, "not-the-password").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login(uniqueEmail("nobody"), "whatever123").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertThat(wrongPassword).isEqualTo(unknownEmail);
    }

    @Test
    void meReturnsCurrentUserForValidToken() throws Exception {
        String email = uniqueEmail("alice");
        JsonNode owner = registerOwner(email, "Tyrell");
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + owner.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.tenant.id").value(owner.at("/tenant/id").asText()));
    }

    @Test
    void meRejectsMissingOrTamperedToken() throws Exception {
        mvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));

        String token = registerOwner(uniqueEmail("alice"), "Cyberdyne").get("accessToken").asText();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing, invalid or expired token"));
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
