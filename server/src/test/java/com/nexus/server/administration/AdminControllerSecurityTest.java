package com.nexus.server.administration;

import com.nexus.server.identity.AuthenticationService;
import com.nexus.server.identity.IdentitySecurityConfiguration;
import com.nexus.server.identity.SessionAuthenticationFilter;
import com.nexus.server.identity.SessionIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminController.class)
@Import({IdentitySecurityConfiguration.class, SessionAuthenticationFilter.class})
class AdminControllerSecurityTest {
    private static final SessionIdentity ADMIN = new SessionIdentity(UUID.randomUUID(), "admin");

    @Autowired
    private MockMvc mvc;

    @MockitoBean private AdminService admin;
    @MockitoBean private AdminAnalyticsService analytics;
    @MockitoBean private AuthenticationService authentication;

    @Test
    void administrativeEndpointsRequireAnAuthenticatedSession() throws Exception {
        mvc.perform(get("/api/v1/admin/audit-events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/analytics/registrations/monthly").param("year", "2026"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedAdministratorReachesEveryNewWorkflowRoute() throws Exception {
        when(admin.users(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new AdminUserPage(List.of(), null));
        when(admin.reports(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new ReportPage(List.of(), null));
        when(admin.auditEvents(any(), any(), anyInt())).thenReturn(new AuditEventPage(List.of(), null));
        when(analytics.monthlyRegistrations(any(), anyInt()))
                .thenReturn(new MonthlyCounts(2026, List.of()));

        mvc.perform(get("/api/v1/admin/users").with(adminSession())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/reports").with(adminSession())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/audit-events").with(adminSession())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/analytics/registrations/monthly")
                .param("year", "2026").with(adminSession())).andExpect(status().isOk());
    }

    @Test
    void mutatingAdministrativeRequestsRequireCsrf() throws Exception {
        mvc.perform(delete("/api/v1/admin/users/{userId}", UUID.randomUUID()).with(adminSession()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/users").with(adminSession())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor adminSession() {
        return authentication(new UsernamePasswordAuthenticationToken(ADMIN, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
}
