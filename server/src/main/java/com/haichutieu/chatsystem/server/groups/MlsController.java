package com.haichutieu.chatsystem.server.groups;

import com.haichutieu.chatsystem.server.identity.SessionIdentity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/groups")
public class MlsController {
    private final MlsService mls;

    public MlsController(MlsService mls) {
        this.mls = mls;
    }

    @PostMapping(value = "/{groupId}/mls-commits", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public void submitCommit(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID groupId,
                             @RequestHeader("X-MLS-Epoch") long expectedEpoch, @RequestBody byte[] commit) {
        mls.submitCommit(session.userId(), groupId, expectedEpoch, commit);
    }

    @GetMapping("/{groupId}/mls-messages")
    public MlsMessagePage messages(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID groupId,
                                   @RequestParam(required = false) UUID after,
                                   @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return mls.messages(session.userId(), groupId, after, limit);
    }

    @PostMapping(value = "/{groupId}/mls-messages", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MlsMessageView sendMessage(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID groupId,
                                      @RequestHeader("X-MLS-Epoch") long epoch,
                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                      @RequestBody byte[] ciphertext) {
        return mls.sendMessage(session.userId(), groupId, epoch, idempotencyKey, ciphertext);
    }

    @PutMapping(value = "/{groupId}/recovery-package", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void storeRecoveryPackage(@AuthenticationPrincipal SessionIdentity session,
                                     @PathVariable UUID groupId, @RequestBody byte[] encryptedPackage) {
        mls.storeRecoveryPackage(session.userId(), groupId, encryptedPackage);
    }

    @GetMapping(value = "/{groupId}/recovery-package", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] recoveryPackage(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID groupId) {
        return mls.recoveryPackage(session.userId(), groupId);
    }
}
