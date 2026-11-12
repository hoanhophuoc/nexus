package com.nexus.desktop.user;

import java.util.UUID;

/** View model row for the shared friends/member picker dialog. */
record UserPickerItem(UUID id, String title, String subtitle, boolean online) {
}
