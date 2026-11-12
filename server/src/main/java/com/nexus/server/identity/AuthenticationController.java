package com.nexus.server.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {

    private final AuthenticationService authentication;
    private final PasswordResetService passwordResets;
    private final RateLimitService rateLimits;
    private final boolean secureCookie;

    public AuthenticationController(
            AuthenticationService authentication,
            PasswordResetService passwordResets,
            RateLimitService rateLimits,
            @Value("${nexus.security.secure-cookie:true}") boolean secureCookie) {
        this.authentication = authentication;
        this.passwordResets = passwordResets;
        this.rateLimits = rateLimits;
        this.secureCookie = secureCookie;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(@RequestAttribute("_csrf") CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @PostMapping("/login")
    public ResponseEntity<UserProfile> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        rateLimits.consume("login-ip", servletRequest.getRemoteAddr(), 30, Duration.ofMinutes(15));
        rateLimits.consume("login-user", request.username().strip().toLowerCase(Locale.ROOT), 10,
                Duration.ofMinutes(15));
        SignedInSession session = authentication.login(request);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                        sessionCookie(session.token(), AuthenticationService.SESSION_LIFETIME).toString())
                .body(session.profile());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = SessionAuthenticationFilter.COOKIE_NAME, required = false) String token) {
        authentication.logout(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString()).build();
    }

    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal SessionIdentity session,
            @CookieValue(name = SessionAuthenticationFilter.COOKIE_NAME) String token,
            @Valid @RequestBody PasswordChangeRequest request) {
        authentication.changePassword(session.userId(), token, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request,
            HttpServletRequest servletRequest) {
        passwordResets.request(request.email(), servletRequest.getRemoteAddr());
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(
            @Valid @RequestBody PasswordResetConfirmation confirmation,
            HttpServletRequest servletRequest) {
        passwordResets.confirm(confirmation, servletRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(SessionAuthenticationFilter.COOKIE_NAME, value)
                .httpOnly(true).secure(secureCookie).sameSite("Strict").path("/").maxAge(maxAge).build();
    }
}
