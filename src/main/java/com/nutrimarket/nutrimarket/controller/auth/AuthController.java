package com.nutrimarket.nutrimarket.controller.auth;

import com.nutrimarket.nutrimarket.service.auth.AuthService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.nutrimarket.nutrimarket.model.auth.AuthModels.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth=auth; }
    @PostMapping("/register")
    public ResponseEntity<UserView> register(@Valid @RequestBody Credentials credentials) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(auth.register(credentials));
    }
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginCredentials credentials) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.login(credentials));
    }
    @GetMapping("/me")
    public UserView me(Principal principal) { return auth.currentUser(principal.getName()); }
}
