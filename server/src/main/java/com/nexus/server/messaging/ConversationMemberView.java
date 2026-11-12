package com.nexus.server.messaging;

import com.nexus.server.social.SocialUser;

public record ConversationMemberView(SocialUser user, String role, int joinedEpoch) {
}
