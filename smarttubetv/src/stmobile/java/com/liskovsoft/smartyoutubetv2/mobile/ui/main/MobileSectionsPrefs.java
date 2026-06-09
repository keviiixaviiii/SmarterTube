package com.liskovsoft.smartyoutubetv2.mobile.ui.main;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Flavor-local (stmobile) persistence for one-time nav-section migrations.
 *
 * Kept out of the shared {@code AppPrefs} so the phone fork stays upstream-mergeable
 * (zero {@code common} drift). Same self-contained {@link SharedPreferences} pattern as
 * {@link com.liskovsoft.smartyoutubetv2.mobile.ui.prefs.MobileThemePrefs}.
 */
public final class MobileSectionsPrefs {
    private static final String PREFS_NAME = "mobile_sections_prefs";
    private static final String KEY_NOTIFICATIONS_MIGRATED = "notifications_section_migrated";

    private MobileSectionsPrefs() {}

    /** True once the notifications section has been enabled-by-default for this install. */
    public static boolean isNotificationsMigrated(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFICATIONS_MIGRATED, false);
    }

    public static void setNotificationsMigrated(Context context, boolean migrated) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS_MIGRATED, migrated).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
