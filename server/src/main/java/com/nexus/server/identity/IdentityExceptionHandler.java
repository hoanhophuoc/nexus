package com.nexus.server.identity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.nexus.server.social.SocialConflictException;
import com.nexus.server.messaging.ConversationForbiddenException;
import com.nexus.server.messaging.IdempotencyConflictException;
import com.nexus.server.messaging.MessageDeletionForbiddenException;
import com.nexus.server.administration.AdminConflictException;
import com.nexus.server.administration.AdminForbiddenException;
import com.nexus.server.administration.AdminNotFoundException;
import com.nexus.server.groups.GroupConflictException;
import com.nexus.server.groups.GroupForbiddenException;
import com.nexus.server.groups.MlsConflictException;
import com.nexus.server.groups.MlsNotFoundException;
import com.nexus.server.groups.MlsPayloadTooLargeException;

@RestControllerAdvice
public class IdentityExceptionHandler {

    @ExceptionHandler(AccountConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ProblemDetail accountConflict(AccountConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(SocialConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ProblemDetail socialConflict(SocialConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler({ConversationForbiddenException.class, MessageDeletionForbiddenException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ProblemDetail messagingForbidden(RuntimeException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "This messaging action is not allowed.");
    }

    @ExceptionHandler(AdminForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ProblemDetail adminForbidden(AdminForbiddenException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Administrator permission is required.");
    }

    @ExceptionHandler(AdminNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ProblemDetail adminNotFound(AdminNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(AdminConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ProblemDetail adminConflict(AdminConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(GroupForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ProblemDetail groupForbidden(GroupForbiddenException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "This group action is not allowed.");
    }

    @ExceptionHandler(GroupConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ProblemDetail groupConflict(GroupConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The group state does not allow this action.");
    }

    @ExceptionHandler(MlsPayloadTooLargeException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    ProblemDetail payloadTooLarge(MlsPayloadTooLargeException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, exception.getMessage());
    }

    @ExceptionHandler(MlsConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ProblemDetail mlsConflict(MlsConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(MlsNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ProblemDetail mlsNotFound(MlsNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ProblemDetail idempotencyConflict(IdempotencyConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The idempotency key was used for different content.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProblemDetail invalidArgument(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ProblemDetail invalidCredentials(InvalidCredentialsException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
    }

    @ExceptionHandler(InvalidPasswordResetTokenException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProblemDetail invalidResetToken(InvalidPasswordResetTokenException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ProblemDetail userNotFound(UserNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The current account is unavailable.");
    }

    @ExceptionHandler(TooManyRequestsException.class)
    ResponseEntity<ProblemDetail> rateLimited(TooManyRequestsException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds())).body(problem);
    }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ProblemDetail storageUnavailable(DataAccessException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "A required service is unavailable.");
    }
}
