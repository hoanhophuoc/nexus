package com.haichutieu.chatsystem.server.groups;

import com.haichutieu.chatsystem.server.identity.SessionIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class KeyPackageController {
    private final KeyPackageService keyPackages;

    public KeyPackageController(KeyPackageService keyPackages) {
        this.keyPackages = keyPackages;
    }

    @PutMapping(value = "/me/key-package", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publish(@AuthenticationPrincipal SessionIdentity session, @RequestBody byte[] keyPackage) {
        keyPackages.publish(session.userId(), keyPackage);
    }

    @GetMapping(value = "/{userId}/key-package", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] find(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID userId) {
        return keyPackages.find(userId);
    }
}
