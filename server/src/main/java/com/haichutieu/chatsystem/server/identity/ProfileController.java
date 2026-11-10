package com.haichutieu.chatsystem.server.identity;

import com.haichutieu.chatsystem.server.social.PresenceStore;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
public class ProfileController {

    private final IdentityService identity;
    private final PresenceStore presence;

    public ProfileController(IdentityService identity, PresenceStore presence) {
        this.identity = identity;
        this.presence = presence;
    }

    @GetMapping
    public UserProfile getProfile(@AuthenticationPrincipal SessionIdentity session) {
        return withPresence(identity.profile(session.userId()));
    }

    @PatchMapping
    public UserProfile updateProfile(
            @AuthenticationPrincipal SessionIdentity session,
            @Valid @RequestBody ProfileUpdateRequest update) {
        return withPresence(identity.updateProfile(session.userId(), update));
    }

    private UserProfile withPresence(UserProfile profile) {
        return new UserProfile(profile.id(), profile.username(), profile.fullName(), profile.email(),
                profile.address(), profile.birthDate(), profile.gender(),
                presence.isOnline(profile.id()) ? "online" : "offline");
    }
}
