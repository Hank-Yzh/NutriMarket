package com.nutrimarket.nutrimarket.model.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nutrimarket.nutrimarket.validation.UsernamePolicy;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Locale;

public final class AuthModels {
    private AuthModels() {}

    public record Credentials(@NotBlank @Pattern(regexp=UsernamePolicy.REGEX) String username,
                              @NotBlank @Size(min=8, max=72) String password) {
        public Credentials { if(username!=null) username=username.strip().toLowerCase(Locale.ROOT); }
        @Override public String toString() { return "Credentials[REDACTED]"; }
    }
    // Login verifies existing passwords; registration retains its minimum of eight characters.
    public record LoginCredentials(@NotBlank @Pattern(regexp=UsernamePolicy.REGEX) String username,
                                   @NotBlank @Size(max=72) String password) {
        public LoginCredentials { if(username!=null) username=username.strip().toLowerCase(Locale.ROOT); }
        @Override public String toString() { return "LoginCredentials[REDACTED]"; }
    }
    public record UserAccount(String id, String username, @JsonIgnore String passwordHash, boolean enabled, Instant createdAt) {
        @Override public String toString() { return "UserAccount[username="+username+"]"; }
    }
    public record UserView(String id, String username, Instant createdAt) {
        public static UserView from(UserAccount user) { return new UserView(user.id(),user.username(),user.createdAt()); }
    }
    public record LoginResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt, UserView user) {
        @Override public String toString() { return "LoginResponse[token=REDACTED]"; }
    }
}
