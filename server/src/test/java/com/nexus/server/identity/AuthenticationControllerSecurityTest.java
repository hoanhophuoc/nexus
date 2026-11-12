package com.nexus.server.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.nexus.server.social.PresenceStore;
import com.nexus.server.social.SocialController;
import com.nexus.server.social.SocialService;

import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthenticationController.class, ProfileController.class, SocialController.class})
@Import({IdentitySecurityConfiguration.class, SessionAuthenticationFilter.class})
class AuthenticationControllerSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private AuthenticationService authentication;
    @MockitoBean private PasswordResetService passwordResets;
    @MockitoBean private RateLimitService rateLimits;
    @MockitoBean private IdentityService identity;
    @MockitoBean private SocialService social;
    @MockitoBean private PresenceStore presence;

    @Test
    void resetRequestIsPublicButStillRequiresCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/password-reset")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"person@example.com\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void resetRequestWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/password-reset")
                        .contentType("application/json")
                        .content("{\"email\":\"person@example.com\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginReturnsOpaqueSessionOnlyInSecureCookie() throws Exception {
        String token = "opaque-secret-session-token";
        UserProfile profile = new UserProfile(UUID.randomUUID(), "alice", "Alice", "alice@example.com",
                "Address", LocalDate.of(1998, 4, 12), "female", "offline");
        when(authentication.login(any())).thenReturn(new SignedInSession(profile, token));

        mvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"correct horse battery staple\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", allOf(containsString("HttpOnly"),
                        containsString("Secure"), containsString("SameSite=Strict"),
                        containsString("Path=/"))))
                .andExpect(content().string(not(containsString(token))));
    }

    @Test
    void profileEndpointRejectsRequestsWithoutAnAuthenticatedSession() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void userSearchRejectsRequestsWithoutAnAuthenticatedSession() throws Exception {
        mvc.perform(get("/api/v1/users").param("query", "alice"))
                .andExpect(status().isUnauthorized());
    }
}
