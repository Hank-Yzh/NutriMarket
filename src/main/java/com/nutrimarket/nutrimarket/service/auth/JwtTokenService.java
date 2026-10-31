package com.nutrimarket.nutrimarket.service.auth;

import com.nutrimarket.nutrimarket.config.JwtProperties;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import static com.nutrimarket.nutrimarket.model.auth.AuthModels.*;

@Service
public class JwtTokenService {
    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;
    public JwtTokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder=encoder; this.properties=properties; this.clock=clock;
    }
    public LoginResponse issue(UserAccount user) {
        var issuedAt=clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var expiresAt=issuedAt.plus(properties.accessTokenTtl());
        var claims=JwtClaimsSet.builder().issuer(properties.issuer()).subject(user.id())
                .audience(List.of(properties.audience())).issuedAt(issuedAt).expiresAt(expiresAt)
                .id(UUID.randomUUID().toString()).claim("token_use","access").claim("identity_type","member_id").build();
        var token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),claims));
        return new LoginResponse(token.getTokenValue(),"Bearer",properties.accessTokenTtl().toSeconds(),expiresAt,UserView.from(user));
    }
}
