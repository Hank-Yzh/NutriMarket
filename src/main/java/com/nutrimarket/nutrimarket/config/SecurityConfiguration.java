package com.nutrimarket.nutrimarket.config;

import com.nutrimarket.nutrimarket.config.JwtProperties;
import com.nutrimarket.nutrimarket.service.auth.AuthService;
import com.nutrimarket.nutrimarket.exception.AuthException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean SecretKey jwtSigningKey(JwtProperties properties) {
        byte[] bytes;
        try { bytes=Base64.getDecoder().decode(properties.secret()); }
        catch(IllegalArgumentException e) { throw new IllegalStateException("JWT secret must be Base64 encoded. Configure it in config/api-keys.yml."); }
        if(bytes.length<32) throw new IllegalStateException("JWT secret must decode to at least 32 bytes. Configure a random signing key in config/api-keys.yml.");
        if(properties.issuer().isBlank() || properties.audience().isBlank() || properties.accessTokenTtl().toSeconds()<60
                || properties.accessTokenTtl().compareTo(Duration.ofDays(1))>0)
            throw new IllegalStateException("JWT issuer/audience must not be empty. Token lifetime must be between 1 minute and 24 hours.");
        return new SecretKeySpec(bytes,"HmacSHA256");
    }
    @Bean JwtEncoder jwtEncoder(SecretKey key) { return NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build(); }

    @Bean JwtDecoder jwtDecoder(SecretKey key, JwtProperties properties, Clock clock) {
        var decoder=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamp=new JwtTimestampValidator(Duration.ZERO);
        timestamp.setClock(clock);
        OAuth2TokenValidator<Jwt> claims=jwt->{
            boolean valid=jwt.getExpiresAt()!=null && jwt.getIssuedAt()!=null && jwt.getSubject()!=null
                    && jwt.getAudience()!=null && jwt.getAudience().contains(properties.audience())
                    && "access".equals(jwt.getClaimAsString("token_use"))
                    && "member_id".equals(jwt.getClaimAsString("identity_type"));
            if(valid) {
                try { valid=UUID.fromString(jwt.getSubject()).toString().equals(jwt.getSubject()); }
                catch(IllegalArgumentException e) { valid=false; }
                valid=valid && jwt.getExpiresAt().isAfter(jwt.getIssuedAt()) && clock.instant().isBefore(jwt.getExpiresAt())
                        && !jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(30));
            }
            return valid?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamp,new JwtIssuerValidator(properties.issuer()),claims));
        return decoder;
    }
    @Bean SecurityFilterChain apiSecurity(HttpSecurity http, AuthService auth, ObjectMapper json) throws Exception {
        // Authorization-header Bearer tokens only: no login cookies, HTTP Basic or server-side sessions.
        return http.csrf(csrf->csrf.disable())
                .httpBasic(basic->basic.disable()).formLogin(form->form.disable()).logout(logout->logout.disable())
                .requestCache(cache->cache.disable())
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a.requestMatchers(HttpMethod.POST,"/api/v1/auth/register","/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET,"/","/index.html","/login.html","/register.html",
                                "/api.js","/auth.js","/home.js","/upload.html","/record.html",
                                "/procurement.html","/basket.html","/orders.html","/order.html","/pantry.html","/pantry.js","/procurement-expert.html","/procurement-reports.html","/procurement.js","/basket.js","/orders.js","/order.js","/procurement-expert.js","/procurement-reports.js","/procurement-ui.js",
                                "/market.html","/market-result.html","/catalogue.html","/market.js","/market-result.js","/catalogue.js","/market-ui.js",
                                "/planning.html","/plan.html","/planning.js","/plan.js","/planning-ui.js",
                                "/health.html","/profile.html","/assessment.html","/expert.html","/health.js","/health-ui.js",
                                "/profile.js","/assessment.js","/expert.js","/upload.js","/upload-crypto.js","/upload-photo.js","/record.js","/dietary-ui.js","/questionnaire.js","/body-ui.js","/address-ui.js","/style.css").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e->e.authenticationEntryPoint((request,response,ex)->writeError(response,json,401,"UNAUTHORIZED","Please sign in or obtain a valid token."))
                        .accessDeniedHandler((request,response,ex)->writeError(response,json,403,"FORBIDDEN","Access denied.")))
                .oauth2ResourceServer(oauth->oauth
                        .authenticationEntryPoint((request,response,ex)->writeError(response,json,401,"UNAUTHORIZED","Please sign in or obtain a valid token."))
                        .jwt(jwt->jwt.jwtAuthenticationConverter(token->{
                            try {
                                var user=auth.currentUser(token.getSubject());
                                return new JwtAuthenticationToken(token,List.of(),user.id());
                            } catch(AuthException e) {
                                throw new InvalidBearerTokenException("User is unavailable");
                            }
                        })))
                .build();
    }
    private static void writeError(HttpServletResponse response, ObjectMapper json, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        if(status==401) response.setHeader("WWW-Authenticate","Bearer");
        response.getWriter().write(json.writeValueAsString(Map.of("code",code,"message",message,"timestamp",java.time.Instant.now())));
    }
}
