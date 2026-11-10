package com.haichutieu.chatsystem.server.administration;

import com.haichutieu.chatsystem.server.groups.GroupMemberPage;
import com.haichutieu.chatsystem.server.identity.ProfileUpdateRequest;
import com.haichutieu.chatsystem.server.identity.RegistrationRequest;
import com.haichutieu.chatsystem.server.identity.SessionIdentity;
import com.haichutieu.chatsystem.server.messaging.ConversationPage;
import com.haichutieu.chatsystem.server.social.SocialUser;
import com.haichutieu.chatsystem.server.social.UserPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService admin;
    private final AdminAnalyticsService analytics;

    public AdminController(AdminService admin, AdminAnalyticsService analytics) {
        this.admin = admin;
        this.analytics = analytics;
    }

    @GetMapping("/users")
    public AdminUserPage users(@AuthenticationPrincipal SessionIdentity session,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return admin.users(session, query, status, sort, direction, cursor, limit);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUserView createUser(@AuthenticationPrincipal SessionIdentity session,
                                    @Valid @RequestBody RegistrationRequest request) {
        return admin.createUser(session, request);
    }

    @GetMapping("/users/{userId}")
    public AdminUserView user(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID userId) {
        return admin.user(session, userId);
    }

    @PatchMapping("/users/{userId}")
    public AdminUserView updateUser(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID userId,
                                    @Valid @RequestBody ProfileUpdateRequest request) {
        return admin.updateUser(session, userId, request);
    }

    @DeleteMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID userId) {
        admin.deleteUser(session, userId);
    }

    @PutMapping("/users/{userId}/lock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setLock(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID userId,
                        @Valid @RequestBody AdminUserLockRequest request) {
        admin.setUserLock(session, userId, request);
    }

    @PostMapping("/users/{userId}/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestPasswordReset(@AuthenticationPrincipal SessionIdentity session,
                                     @PathVariable UUID userId) {
        admin.requestPasswordReset(session, userId);
    }

    @GetMapping("/users/{userId}/login-history")
    public LoginHistoryPage userLoginHistory(@AuthenticationPrincipal SessionIdentity session,
            @PathVariable UUID userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return admin.loginHistory(session, userId, cursor, limit);
    }

    @GetMapping("/login-history")
    public LoginHistoryPage loginHistory(@AuthenticationPrincipal SessionIdentity session,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return admin.allLoginHistory(session, cursor, limit);
    }

    @GetMapping("/users/{userId}/friends")
    public UserPage<SocialUser> userFriends(@AuthenticationPrincipal SessionIdentity session,
            @PathVariable UUID userId,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return admin.userFriends(session, userId, cursor, limit);
    }

    @GetMapping("/groups")
    public ConversationPage groups(@AuthenticationPrincipal SessionIdentity session,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return admin.groups(session, query, sort, direction, cursor, limit);
    }

    @GetMapping("/groups/{groupId}/members")
    public GroupMemberPage groupMembers(@AuthenticationPrincipal SessionIdentity session,
            @PathVariable UUID groupId,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return admin.groupMembers(session, groupId, cursor, limit);
    }

    @GetMapping("/reports")
    public ReportPage reports(@AuthenticationPrincipal SessionIdentity session,
            @RequestParam(required = false) @Size(max = 32) String reportedUsername,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return admin.reports(session, reportedUsername, from, to, sort, direction, cursor, limit);
    }

    @PutMapping("/reports/{reportId}/decision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decideReport(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID reportId,
                             @Valid @RequestBody AdminReportDecisionRequest request) {
        admin.decideReport(session, reportId, request);
    }

    @GetMapping("/registrations")
    public AdminUserPage registrations(@AuthenticationPrincipal SessionIdentity session,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return admin.registrations(session, from, to, query, sort, direction, cursor, limit);
    }

    @GetMapping("/analytics/friends")
    public FriendAnalyticsPage friendAnalytics(@AuthenticationPrincipal SessionIdentity session,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) @Min(0) Integer directFriendCount,
            @RequestParam(required = false) String directFriendCountComparison,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return analytics.friends(session, query, directFriendCount, directFriendCountComparison,
                sort, direction, cursor, limit);
    }

    @GetMapping("/analytics/activity")
    public ActivityAnalyticsPage activityAnalytics(@AuthenticationPrincipal SessionIdentity session,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) @Min(0) Integer activityCount,
            @RequestParam(required = false) String activityCountComparison,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return analytics.activity(session, from, to, query, activityCount, activityCountComparison,
                sort, direction, cursor, limit);
    }

    @GetMapping("/analytics/registrations/monthly")
    public MonthlyCounts monthlyRegistrations(@AuthenticationPrincipal SessionIdentity session,
                                              @RequestParam @Min(2000) @Max(9999) int year) {
        return analytics.monthlyRegistrations(session, year);
    }

    @GetMapping("/analytics/app-opens/monthly")
    public MonthlyCounts monthlyAppOpens(@AuthenticationPrincipal SessionIdentity session,
                                         @RequestParam @Min(2000) @Max(9999) int year) {
        return analytics.monthlyAppOpens(session, year);
    }

    @GetMapping("/audit-events")
    public AuditEventPage auditEvents(@AuthenticationPrincipal SessionIdentity session,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return admin.auditEvents(session, cursor, limit);
    }
}
