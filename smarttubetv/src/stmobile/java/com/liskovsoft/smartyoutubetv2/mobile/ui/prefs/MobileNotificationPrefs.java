package com.liskovsoft.smartyoutubetv2.mobile.ui.prefs;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Flavor-local persistence for the phone "Upload notifications" feature (Part 2 — push).
 *
 * Holds two things:
 *  - the master on/off toggle (default OFF — opt-in), and
 *  - the set of already-seen upload video ids, used by {@code NotificationPollWorker} to
 *    avoid alerting twice for the same video.
 *
 * Seen ids are stored most-recent-first and capped at {@link #MAX_SEEN} so the prefs entry
 * can't grow without bound. Mirrors the structure of {@link MobileThemePrefs}; stmobile-only,
 * so shared {@code common} code stays untouched and the fork stays upstream-mergeable.
 */
public final class MobileNotificationPrefs {
    private static final String PREFS_NAME = "mobile_notification_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SEEN = "seen_ids";
    private static final String DELIM = "\n";
    /** Plenty to cover a poll's worth of new uploads while bounding the stored string. */
    private static final int MAX_SEEN = 300;

    private MobileNotificationPrefs() {}

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** Seen upload ids, most-recent-first. Never null. */
    public static LinkedHashSet<String> getSeenIds(Context context) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String raw = prefs(context).getString(KEY_SEEN, null);
        if (!TextUtils.isEmpty(raw)) {
            for (String id : raw.split(DELIM)) {
                if (!TextUtils.isEmpty(id)) {
                    result.add(id);
                }
            }
        }
        return result;
    }

    /**
     * Persist the seen set. {@code freshest} ids are written first (kept on overflow); any
     * leftover {@code older} ids follow. The combined list is de-duplicated and capped at
     * {@link #MAX_SEEN}.
     */
    public static void saveSeenIds(Context context, Collection<String> freshest, Collection<String> older) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (freshest != null) {
            merged.addAll(freshest);
        }
        if (older != null) {
            merged.addAll(older);
        }

        List<String> capped = new ArrayList<>(merged);
        if (capped.size() > MAX_SEEN) {
            capped = capped.subList(0, MAX_SEEN);
        }

        prefs(context).edit().putString(KEY_SEEN, TextUtils.join(DELIM, capped)).apply();
    }

    /** Forget all seen ids — used on account switch so a new account seeds fresh (no cross-account alerts). */
    public static void clearSeenIds(Context context) {
        prefs(context).edit().remove(KEY_SEEN).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
