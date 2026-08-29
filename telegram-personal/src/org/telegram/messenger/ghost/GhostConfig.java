package org.telegram.messenger.ghost;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Local-only settings for the privacy additions of this build.
 * Nothing here is ever synced to the server.
 */
public class GhostConfig {

    private static final String PREFS = "ghostconfig";

    /** Never announce "online" to the server while using the app. */
    public static boolean hideOnline = true;
    /** Never send "typing" / "recording" / "choosing sticker" actions. */
    public static boolean hideTyping = true;
    /** Never send read receipts (chats stay unread for the other side). */
    public static boolean hideReadReceipts = true;
    /** Remember when each contact was online. */
    public static boolean trackOnline = true;
    /** Keep a copy of messages that get deleted. */
    public static boolean saveDeleted = true;
    /** Keep every version of messages that get edited. */
    public static boolean saveEdited = true;
    /** Drop archived data older than this many days, 0 = keep forever. */
    public static int retentionDays = 0;

    private static volatile boolean loaded;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    public static void load() {
        if (loaded) {
            return;
        }
        synchronized (GhostConfig.class) {
            if (loaded) {
                return;
            }
            try {
                SharedPreferences p = prefs();
                hideOnline = p.getBoolean("hideOnline", true);
                hideTyping = p.getBoolean("hideTyping", true);
                hideReadReceipts = p.getBoolean("hideReadReceipts", true);
                trackOnline = p.getBoolean("trackOnline", true);
                saveDeleted = p.getBoolean("saveDeleted", true);
                saveEdited = p.getBoolean("saveEdited", true);
                retentionDays = p.getInt("retentionDays", 0);
            } catch (Exception ignore) {
            }
            loaded = true;
        }
    }

    private static void put(String key, boolean value) {
        try {
            prefs().edit().putBoolean(key, value).apply();
        } catch (Exception ignore) {
        }
    }

    public static void setHideOnline(boolean value) {
        load();
        hideOnline = value;
        put("hideOnline", value);
    }

    public static void setHideTyping(boolean value) {
        load();
        hideTyping = value;
        put("hideTyping", value);
    }

    public static void setHideReadReceipts(boolean value) {
        load();
        hideReadReceipts = value;
        put("hideReadReceipts", value);
    }

    public static void setTrackOnline(boolean value) {
        load();
        trackOnline = value;
        put("trackOnline", value);
    }

    public static void setSaveDeleted(boolean value) {
        load();
        saveDeleted = value;
        put("saveDeleted", value);
    }

    public static void setSaveEdited(boolean value) {
        load();
        saveEdited = value;
        put("saveEdited", value);
    }

    public static void setRetentionDays(int days) {
        load();
        retentionDays = days;
        try {
            prefs().edit().putInt("retentionDays", days).apply();
        } catch (Exception ignore) {
        }
    }

    public static boolean isHideOnline() {
        load();
        return hideOnline;
    }

    public static boolean isHideTyping() {
        load();
        return hideTyping;
    }

    public static boolean isHideReadReceipts() {
        load();
        return hideReadReceipts;
    }

    public static boolean isTrackOnline() {
        load();
        return trackOnline;
    }

    public static boolean isSaveDeleted() {
        load();
        return saveDeleted;
    }

    public static boolean isSaveEdited() {
        load();
        return saveEdited;
    }
}
