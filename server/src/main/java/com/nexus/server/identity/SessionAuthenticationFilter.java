package com.nexus.server.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "NEXUS_SESSION";
    private final AuthenticationService authentication;

    public SessionAuthenticationFilter(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (COOKIE_NAME.equals(cookie.getName())) {
                    authentication.authenticate(cookie.getValue()).ifPresent(identity -> {
                        var auth = new UsernamePasswordAuthenticationToken(identity, null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + identity.role().toUpperCase(Locale.ROOT))));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    });
                    break;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
