package com.nexus.server.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class RegistrationController {

    private final IdentityService identity;
    private final RateLimitService rateLimits;

    public RegistrationController(IdentityService identity, RateLimitService rateLimits) {
        this.identity = identity;
        this.rateLimits = rateLimits;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserProfile register(@Valid @RequestBody RegistrationRequest request, HttpServletRequest servletRequest) {
        rateLimits.consume("register-ip", servletRequest.getRemoteAddr(), 5, Duration.ofHours(1));
        return identity.register(request);
    }
}
