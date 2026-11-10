package com.haichutieu.chatsystem.server.messaging;

import com.haichutieu.chatsystem.server.social.SocialUser;

public record ConversationMemberView(SocialUser user, String role, int joinedEpoch) {
}
