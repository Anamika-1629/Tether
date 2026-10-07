# Tether Auth / Tenant Service (Member 1)

Spring Boot 3 / Java 17 / PostgreSQL. Port **8081**. Covers **FR6**: users log in and are scoped to their organization (tenant).

It registers users, hashes passwords (BCrypt), puts every user in exactly one tenant, and issues the signed JWT that the other Tether services trust.

## Run
```bash
cd auth-service
./mvnw spring-boot:run
```
Defaults match `docker-compose.yml` (`localhost:5432/incidentsync`, user/pass `incidentsync`). To use the shared Supabase DB, export the same variables the Incident Service uses:

| Variable | Default | Notes |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/incidentsync` | Supabase: use the **session** pooler / direct connection JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `incidentsync` | |
| `JWT_SECRET` | dev-only value | **At least 32 bytes.** Every service that validates tokens needs the same value. Startup fails if it's too short. |
| `JWT_TTL` | `PT1H` | ISO-8601 duration, e.g. `PT15M`, `PT8H` |
| `JWT_ISSUER` | `tether-auth` | Validators check the `iss` claim against this |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Comma-separated frontend origins |

Flyway creates `tenants` and `users` on first start. Its history table is `auth_schema_history`, so it doesn't clash with the Incident Service on the shared DB. I tested both services running against one database.

Tests (no DB or Docker needed; they use in-memory H2 in PostgreSQL mode): `./mvnw test`

## Endpoints
| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/auth/register` | public | `email`, `password` (8–72 chars), `displayName`, and **exactly one of** `organizationName` (create a new org, you become `OWNER`) or `joinCode` (join an existing org as `MEMBER`). Returns 201 + token. |
| POST | `/auth/login` | public | `email`, `password`. Returns 200 + token. |
| GET | `/auth/me` | Bearer | Current user + tenant. Useful to check a token. |
| GET | `/actuator/health` | public | `{"status":"UP"}` |

Successful register/login response:
```json
{
  "accessToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresAt": "2026-10-07T13:34:01Z",
  "expiresIn": 3599,
  "user":   { "id": "7dc0…", "email": "alice@acme.test", "displayName": "Alice", "role": "OWNER" },
  "tenant": { "id": "20c3…", "name": "Acme Corp", "slug": "acme-corp", "joinCode": "VV2Y29S6" }
}
```
`joinCode` is only returned to the tenant's `OWNER`, who shares it with teammates.

Errors are always `{"error": "..."}` (validation errors add `"fields": {...}`):

| Status | When |
|---|---|
| 400 | validation failed, both/neither of `organizationName`/`joinCode`, unknown join code |
| 401 | wrong email or password (same message for both), missing/invalid/expired token |
| 409 | email already registered (case-insensitive) |

## Try it
**Postman:** import `postman/tether-auth-service.postman_collection.json` and run the folder top to bottom. It registers an owner, has a teammate join with the join code, logs in, calls `/auth/me`, and checks the 401/409 cases. Variables are saved for you, and it can be re-run as often as you like.

**curl:**
```bash
# 1. Register: creates the "Acme Corp" tenant, Alice is OWNER. Note tenant.joinCode in the response.
curl -s -X POST localhost:8081/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"alice@acme.test","password":"correct-horse","displayName":"Alice","organizationName":"Acme Corp"}'

# 2. Teammate joins the same tenant with the join code
curl -s -X POST localhost:8081/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"bob@acme.test","password":"hunter2hunter2","displayName":"Bob","joinCode":"<JOIN_CODE>"}'

# 3. Login -> token
curl -s -X POST localhost:8081/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"alice@acme.test","password":"correct-horse"}'

# 4. Use the token
curl -s localhost:8081/auth/me -H "Authorization: Bearer <TOKEN>"
```

## The token (contract for other services)
HS256-signed JWT. Decoded payload:
```json
{
  "iss": "tether-auth",
  "sub": "7dc02702-ebfa-4fd9-b059-3e056bd67b65",
  "userId": "7dc02702-ebfa-4fd9-b059-3e056bd67b65",
  "tenantId": "20c3ef62-fcbc-42af-995e-a082f5ed4d08",
  "email": "alice@acme.test",
  "role": "OWNER",
  "iat": 1791376441,
  "exp": 1791380041,
  "jti": "c92bebdd-6b9a-4f40-a553-f8382a504277"
}
```
- `tenantId` is the value to scope data by. It's a UUID string, so it fits the Incident Service's `tenant_id VARCHAR(100)` and its `X-Tenant-Id` header unchanged. I verified this end to end: a tenantId from a token was used to create and list incidents.
- `userId` / `email` are what to record as the actor (replacing `X-Actor`).
- Send it as `Authorization: Bearer <token>`.

### Validating the token in another Spring Boot service
1. Add the dependency:
   ```xml
   <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-oauth2-resource-server</artifactId></dependency>
   ```
2. Configure the same secret and issuer:
   ```java
   @Configuration
   public class SecurityConfig {
       @Bean
       JwtDecoder jwtDecoder(@Value("${JWT_SECRET}") String secret) {
           SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
           NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
           decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("tether-auth"));
           return decoder;
       }

       @Bean
       SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
           return http.csrf(c -> c.disable())
                   .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                   .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health").permitAll().anyRequest().authenticated())
                   .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                   .build();
       }
   }
   ```
3. Read the claims in a controller instead of trusting headers:
   ```java
   @GetMapping
   public List<IncidentResponse> list(@AuthenticationPrincipal Jwt jwt, ...) {
       String tenant = jwt.getClaimAsString("tenantId");
       String actor  = jwt.getClaimAsString("email");
       ...
   }
   ```
Signature, expiry and issuer are then checked on every request, and a bad token gets a 401 automatically.

**Sync Service (WebSocket/STOMP):** browsers can't set headers on the WebSocket handshake. Send the token in the STOMP `CONNECT` frame (`Authorization: Bearer …` native header) and validate it with the same `JwtDecoder` in a `ChannelInterceptor`. Then only let a client subscribe to topics for incidents in its own `tenantId`.

**Frontend:** keep the token in memory (or `sessionStorage`), send it as `Authorization: Bearer …`, and when any service returns 401, send the user back to login.

## Design notes
- **Schema:** `tenants(id, name, slug UNIQUE, join_code UNIQUE, created_at)` and `users(id, tenant_id FK → tenants.id, email UNIQUE, password_hash, display_name, role, created_at)`. IDs are UUIDs generated by the app.
- **Passwords:** BCrypt (cost 10), never stored or logged in plain text. The maximum length is 72 because BCrypt ignores anything past 72 bytes.
- **No user enumeration:** an unknown email and a wrong password return the identical 401. An unknown email still runs a BCrypt check against a dummy hash, so response time doesn't reveal which emails exist.
- **Emails** are trimmed and lower-cased before storing or looking up, so `Alice@Acme.test` and `alice@acme.test` are the same account.
- **Join codes:** 8 characters from an alphabet without `0/O/1/I`, generated with `SecureRandom`, case-insensitive when entered.
- **Race safety:** the unique constraint on `email` is the real guard. If two registrations race, the loser gets a 409, not a 500.
- **Fail fast:** the service refuses to start with a JWT secret shorter than 32 bytes.

## Deferred (per Review 2 scope)
- Full RBAC: only `OWNER`/`MEMBER` is stored and put in the token.
- Password reset, email verification, refresh tokens.
- Rate limiting on `/auth/login` and on join-code guessing.
- Dockerfile.
- Moving to RS256 + a JWKS endpoint, so other services hold only a public key instead of the shared secret.
