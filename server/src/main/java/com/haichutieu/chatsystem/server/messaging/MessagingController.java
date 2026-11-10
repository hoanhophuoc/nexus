package com.haichutieu.chatsystem.server.messaging;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1")
public class MessagingController {
    private final MessagingService messaging;

    public MessagingController(MessagingService messaging) { this.messaging = messaging; }

    @GetMapping("/conversations")
    public ConversationPage conversations(@AuthenticationPrincipal SessionIdentity session,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return messaging.conversations(session.userId(), cursor, limit);
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView create(@AuthenticationPrincipal SessionIdentity session,
                                   @Valid @RequestBody ConversationRequest request) {
        return messaging.createDirectConversation(session.userId(), request.participantId());
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public MessagePage messages(@AuthenticationPrincipal SessionIdentity session,
            @PathVariable UUID conversationId, @RequestParam(required = false) UUID after,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return messaging.messages(session.userId(), conversationId, after, limit);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView send(@AuthenticationPrincipal SessionIdentity session,
            @PathVariable UUID conversationId, @RequestHeader("Idempotency-Key") @NotBlank String key,
            @Valid @RequestBody MessageSubmission request) {
        return messaging.sendMessage(session.userId(), conversationId, key, request.content());
    }

    @DeleteMapping("/conversations/{conversationId}/messages/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID conversationId,
                       @PathVariable UUID messageId, @RequestParam MessageDeletionScope scope) {
        messaging.deleteMessage(session.userId(), conversationId, messageId, scope);
    }

    @DeleteMapping("/conversations/{conversationId}/history")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID conversationId) {
        messaging.clearHistory(session.userId(), conversationId);
    }
}
