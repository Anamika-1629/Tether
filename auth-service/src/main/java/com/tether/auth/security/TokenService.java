package com.tether.auth.security;

import com.tether.auth.model.UserAccount;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues the access token every Tether service trusts. Claims:
 *   sub / userId  user UUID
 *   tenantId      tenant UUID; services scope all data by this
 *   email, role   for display and future authorization
 *   iss, iat, exp, jti
 */
@Service
public class TokenService {

    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_TENANT_ID = "tenantId";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_ROLE = "role";

    private final JwtEncoder encoder;
    private final JwtProperties props;

    public TokenService(JwtEncoder encoder, JwtProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public IssuedToken issue(UserAccount user) {
        // JWT timestamps are whole seconds; truncate so expiresAt in the response matches "exp" exactly
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(props.ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_USER_ID, user.getId().toString())
                .claim(CLAIM_TENANT_ID, user.getTenant().getId().toString())
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }
}
