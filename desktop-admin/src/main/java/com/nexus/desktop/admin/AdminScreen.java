package com.nexus.desktop.admin;

import com.nexus.desktop.shared.ui.I18n;

/** The six admin workspaces. Their labels are resolved from the ResourceBundle. */
enum AdminScreen {
    ACCOUNTS("admin.nav.accounts", AdminViewLoader.ACCOUNTS),
    REPORTS("admin.nav.reports", AdminViewLoader.REPORTS),
    GROUPS("admin.nav.groups", AdminViewLoader.GROUPS),
    REGISTRATIONS("admin.nav.registrations", AdminViewLoader.REGISTRATIONS),
    ANALYTICS("admin.nav.analytics", AdminViewLoader.ANALYTICS),
    AUDIT("admin.nav.audit", AdminViewLoader.AUDIT);

    private final String labelKey;
    private final String resource;

    AdminScreen(String labelKey, String resource) {
        this.labelKey = labelKey;
        this.resource = resource;
    }

    String label(I18n i18n) {
        return i18n.get(labelKey);
    }

    String resource() {
        return resource;
    }
}
