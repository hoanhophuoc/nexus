package com.haichutieu.chatsystem.server.social;

import com.haichutieu.chatsystem.server.identity.SessionIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1")
public class SocialController {

    private final SocialService social;

    public SocialController(SocialService social) {
        this.social = social;
    }

    @GetMapping("/users")
    public UserPage<SocialUser> search(
            @AuthenticationPrincipal SessionIdentity session,
            @RequestParam("query") @NotBlank String query,
            @RequestParam(name = "cursor", required = false) UUID cursor,
            @RequestParam(name = "limit", defaultValue = "25") @Min(1) @Max(100) int limit) {
        return social.search(session.userId(), query, cursor, limit);
    }

    @GetMapping("/friend-requests")
    public FriendRequestPage incomingRequests(
            @AuthenticationPrincipal SessionIdentity session,
            @RequestParam(name = "cursor", required = false) UUID cursor,
            @RequestParam(name = "limit", defaultValue = "25") @Min(1) @Max(100) int limit) {
        return social.incomingRequests(session.userId(), cursor, limit);
    }

    @GetMapping("/friend-requests/outgoing")
    public OutgoingFriendRequestPage outgoingRequests(
            @AuthenticationPrincipal SessionIdentity session,
            @RequestParam(name = "cursor", required = false) UUID cursor,
            @RequestParam(name = "limit", defaultValue = "25") @Min(1) @Max(100) int limit) {
        return social.outgoingRequests(session.userId(), cursor, limit);
    }

    @PostMapping("/friend-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public void sendRequest(@AuthenticationPrincipal SessionIdentity session,
                            @Valid @RequestBody FriendRequestSubmission request) {
        social.sendRequest(session.userId(), request.userId());
    }

    @PostMapping("/friend-requests/{requestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decideRequest(@AuthenticationPrincipal SessionIdentity session,
                              @PathVariable("requestId") UUID requestId,
                              @Valid @RequestBody FriendRequestDecision request) {
        social.decideRequest(session.userId(), requestId, request.decision());
    }

    @DeleteMapping("/friend-requests/{requestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelRequest(@AuthenticationPrincipal SessionIdentity session,
                              @PathVariable("requestId") UUID requestId) {
        social.cancelRequest(session.userId(), requestId);
    }

    @GetMapping("/friends")
    public UserPage<SocialUser> friends(
            @AuthenticationPrincipal SessionIdentity session,
            @RequestParam(name = "cursor", required = false) UUID cursor,
            @RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(100) int limit) {
        return social.friends(session.userId(), cursor, limit);
    }

    @GetMapping("/blocks")
    public UserPage<SocialUser> blocked(
            @AuthenticationPrincipal SessionIdentity session,
            @RequestParam(name = "cursor", required = false) UUID cursor,
            @RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(100) int limit) {
        return social.blocked(session.userId(), cursor, limit);
    }

    @DeleteMapping("/friends/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFriend(@AuthenticationPrincipal SessionIdentity session, @PathVariable("userId") UUID userId) {
        social.removeFriend(session.userId(), userId);
    }

    @PutMapping("/blocks/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@AuthenticationPrincipal SessionIdentity session, @PathVariable("userId") UUID userId) {
        social.block(session.userId(), userId);
    }

    @DeleteMapping("/blocks/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@AuthenticationPrincipal SessionIdentity session, @PathVariable("userId") UUID userId) {
        social.unblock(session.userId(), userId);
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public void report(@AuthenticationPrincipal SessionIdentity session,
                       @Valid @RequestBody ReportSubmission submission) {
        social.submitReport(session.userId(), submission);
    }
}
