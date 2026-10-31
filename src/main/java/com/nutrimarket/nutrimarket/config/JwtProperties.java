package com.nutrimarket.nutrimarket.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("nutrimarket.security.jwt")
public record JwtProperties(@DefaultValue("") String secret,
                            @DefaultValue("nutrimarket") String issuer,
                            @DefaultValue("nutrimarket-api") String audience,
                            @DefaultValue("15m") Duration accessTokenTtl) {
    @Override public String toString() { return "JwtProperties[secret=REDACTED]"; }
}
