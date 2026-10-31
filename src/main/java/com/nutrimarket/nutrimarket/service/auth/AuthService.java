package com.nutrimarket.nutrimarket.service.auth;

import com.nutrimarket.nutrimarket.dao.auth.UserMapper;
import com.nutrimarket.nutrimarket.exception.AuthException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.nutrimarket.nutrimarket.model.auth.AuthModels.*;

@Service
public class AuthService {
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final JwtTokenService tokens;
    private final Clock clock;
    private final String dummyHash;
    public AuthService(UserMapper users, PasswordEncoder passwords, JwtTokenService tokens, Clock clock) {
        this.users=users; this.passwords=passwords; this.tokens=tokens; this.clock=clock;
        this.dummyHash=passwords.encode(UUID.randomUUID().toString());
    }
    @Transactional
    public UserView register(Credentials request) {
        validatePasswordBytes(request.password());
        var user=new UserAccount(UUID.randomUUID().toString(),request.username(),passwords.encode(request.password()),true,clock.instant());
        try { users.insert(user); }
        catch(DuplicateKeyException e) {
            throw new AuthException(HttpStatus.CONFLICT,"USERNAME_ALREADY_REGISTERED","This username is already registered.");
        }
        return UserView.from(user);
    }
    public LoginResponse login(LoginCredentials request) {
        validatePasswordBytes(request.password());
        var user=users.findByUsername(request.username());
        // Perform the same expensive hash comparison even when the username does not exist.
        boolean matches=passwords.matches(request.password(),user==null?dummyHash:user.passwordHash());
        if(user==null || !matches || !user.enabled()) throw AuthException.invalidCredentials();
        return tokens.issue(user);
    }
    public UserView currentUser(String memberId) {
        var user=users.findById(memberId);
        if(user==null || !user.enabled()) throw new AuthException(HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","Please sign in again.");
        return UserView.from(user);
    }
    private void validatePasswordBytes(String password) {
        // BCrypt limits passwords by UTF-8 bytes, not Java character count.
        if(password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new AuthException(HttpStatus.BAD_REQUEST,"INVALID_PASSWORD","The password UTF-8 encoding must not exceed 72 bytes.");
    }
}
