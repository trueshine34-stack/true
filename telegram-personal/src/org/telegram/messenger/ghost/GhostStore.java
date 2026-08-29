package org.telegram.messenger.ghost;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;

/**
 * Private, on-device database holding the online-status timeline and the archive of
 * deleted / edited messages. One file per logged in account, never uploaded anywhere.
 */
public class GhostStore extends SQLiteOpenHelper {

    private static final int VERSION = 1;

    public static final int STATE_OFFLINE = 0;
    public static final int STATE_ONLINE = 1;
    public static final int STATE_RECENTLY = 2;
    public static final int STATE_LAST_WEEK = 3;
    public static final int STATE_LAST_MONTH = 4;
    public static final int STATE_HIDDEN = 5;

    public static final int KIND_ORIGINAL = 0;
    public static final int KIND_EDITED = 1;
    public static final int KIND_DELETED = 2;

    public static final int FILTER_ALL = 0;
    public static final int FILTER_DELETED = 1;
    public static final int FILTER_EDITED = 2;

    private static final GhostStore[] instances = new GhostStore[UserConfig.MAX_ACCOUNT_COUNT];

    public static GhostStore getInstance(int account) {
        GhostStore local = instances[account];
        if (local == null) {
            synchronized (GhostStore.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new GhostStore(ApplicationLoader.applicationContext, account);
                }
            }
        }
        return local;
    }

    private GhostStore(Context context, int account) {
        super(context, "ghost_account" + account + ".db", null, VERSION);
    }

    @Override
    public void onCreate(@NonNull SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS online_events (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "user_id INTEGER NOT NULL," +
                "state INTEGER NOT NULL," +
                "expires INTEGER NOT NULL," +
                "event_time INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_online_events_user ON online_events(user_id, event_time)");

        db.execSQL("CREATE TABLE IF NOT EXISTS online_users (" +
                "user_id INTEGER PRIMARY KEY," +
                "last_state INTEGER NOT NULL," +
                "last_expires INTEGER NOT NULL," +
                "last_event_time INTEGER NOT NULL," +
                "last_online INTEGER NOT NULL DEFAULT 0," +
                "first_seen INTEGER NOT NULL," +
                "events INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE TABLE IF NOT EXISTS msg_index (" +
                "dialog_id INTEGER NOT NULL," +
                "message_id INTEGER NOT NULL," +
                "from_id INTEGER NOT NULL DEFAULT 0," +
                "msg_date INTEGER NOT NULL DEFAULT 0," +
                "out INTEGER NOT NULL DEFAULT 0," +
                "deleted INTEGER NOT NULL DEFAULT 0," +
                "edits INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER NOT NULL DEFAULT 0," +
                "preview TEXT," +
                "PRIMARY KEY(dialog_id, message_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_index_updated ON msg_index(updated_at)");

        db.execSQL("CREATE TABLE IF NOT EXISTS msg_versions (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "dialog_id INTEGER NOT NULL," +
                "message_id INTEGER NOT NULL," +
                "ver INTEGER NOT NULL," +
                "kind INTEGER NOT NULL," +
                "text TEXT," +
                "media TEXT," +
                "captured_at INTEGER NOT NULL," +
                "data BLOB)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_versions ON msg_versions(dialog_id, message_id, ver)");
    }

    @Override
    public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
        // Only version 1 exists so far; future migrations go here.
    }

    private SQLiteDatabase db() {
        try {
            return getWritableDatabase();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    // ---------------------------------------------------------------- online status

    public static class StatusEvent {
        public long userId;
        public int state;
        public int expires;
        public int eventTime;
    }

    public static class TrackedUser {
        public long userId;
        public int lastState;
        public int lastExpires;
        public int lastEventTime;
        public int lastOnline;
        public int firstSeen;
        public int events;
    }

    /**
     * Records a status observation. Consecutive identical observations are ignored so the
     * timeline stays readable.
     *
     * @return true when a new point was actually stored
     */
    public boolean putStatus(long userId, int state, int expires, int eventTime) {
        SQLiteDatabase db = db();
        if (db == null) {
            return false;
        }
        Cursor c = null;
        try {
            int prevState = -1;
            int prevExpires = -1;
            int firstSeen = eventTime;
            int events = 0;
            int lastOnline = 0;
            c = db.rawQuery("SELECT last_state, last_expires, first_seen, events, last_online FROM online_users WHERE user_id = ?",
                    new String[]{Long.toString(userId)});
            if (c.moveToFirst()) {
                prevState = c.getInt(0);
                prevExpires = c.getInt(1);
                firstSeen = c.getInt(2);
                events = c.getInt(3);
                lastOnline = c.getInt(4);
            }
            c.close();
            c = null;

            if (prevState == state && prevExpires == expires) {
                return false;
            }

            db.beginTransaction();
            try {
                ContentValues ev = new ContentValues();
                ev.put("user_id", userId);
                ev.put("state", state);
                ev.put("expires", expires);
                ev.put("event_time", eventTime);
                db.insert("online_events", null, ev);

                if (state == STATE_ONLINE) {
                    lastOnline = eventTime;
                } else if (state == STATE_OFFLINE && expires > lastOnline) {
                    lastOnline = expires;
                }

                ContentValues su = new ContentValues();
                su.put("user_id", userId);
                su.put("last_state", state);
                su.put("last_expires", expires);
                su.put("last_event_time", eventTime);
                su.put("last_online", lastOnline);
                su.put("first_seen", firstSeen);
                su.put("events", events + 1);
                db.insertWithOnConflict("online_users", null, su, SQLiteDatabase.CONFLICT_REPLACE);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    public ArrayList<TrackedUser> getTrackedUsers() {
        ArrayList<TrackedUser> result = new ArrayList<>();
        SQLiteDatabase db = db();
        if (db == null) {
            return result;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT user_id, last_state, last_expires, last_event_time, last_online, first_seen, events " +
                    "FROM online_users ORDER BY MAX(last_online, last_event_time) DESC", null);
            while (c.moveToNext()) {
                TrackedUser u = new TrackedUser();
                u.userId = c.getLong(0);
                u.lastState = c.getInt(1);
                u.lastExpires = c.getInt(2);
                u.lastEventTime = c.getInt(3);
                u.lastOnline = c.getInt(4);
                u.firstSeen = c.getInt(5);
                u.events = c.getInt(6);
                result.add(u);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignore) {
                }
            }
        }
        return result;
    }

    public ArrayList<StatusEvent> getEvents(long userId, int sinceTime) {
        ArrayList<StatusEvent> result = new ArrayList<>();
        SQLiteDatabase db = db();
        if (db == null) {
            return result;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT state, expires, event_time FROM online_events WHERE user_id = ? AND event_time >= ? ORDER BY event_time ASC",
                    new String[]{Long.toString(userId), Integer.toString(sinceTime)});
            while (c.moveToNext()) {
                StatusEvent e = new StatusEvent();
                e.userId = userId;
                e.state = c.getInt(0);
                e.expires = c.getInt(1);
                e.eventTime = c.getInt(2);
                result.add(e);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignore) {
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- message archive

    public static class ArchivedMessage {
        public long dialogId;
        public int messageId;
        public long fromId;
        public int date;
        public boolean out;
        public boolean deleted;
        public int edits;
        public int updatedAt;
        public String preview;
    }

    public static class MessageVersion {
        public int ver;
        public int kind;
        public String text;
        public String media;
        public int capturedAt;
        public byte[] data;
    }

    /**
     * Appends one version of a message to the archive. Version numbers grow per message, so the
     * full edit history of a single message stays ordered and de-duplicated by content.
     */
    public void addVersion(long dialogId, int messageId, long fromId, int date, boolean out,
                           int kind, String text, String media, byte[] data) {
        SQLiteDatabase db = db();
        if (db == null) {
            return;
        }
        Cursor c = null;
        try {
            int nextVer = 0;
            String lastText = null;
            String lastMedia = null;
            int lastKind = -1;
            c = db.rawQuery("SELECT ver, kind, text, media FROM msg_versions WHERE dialog_id = ? AND message_id = ? ORDER BY ver DESC LIMIT 1",
                    new String[]{Long.toString(dialogId), Integer.toString(messageId)});
            if (c.moveToFirst()) {
                nextVer = c.getInt(0) + 1;
                lastKind = c.getInt(1);
                lastText = c.getString(2);
                lastMedia = c.getString(3);
            }
            c.close();
            c = null;

            boolean sameContent = eq(lastText, text) && eq(lastMedia, media);
            if (sameContent && lastKind == kind) {
                return;
            }
            if (sameContent && kind != KIND_DELETED) {
                return;
            }

            int now = (int) (System.currentTimeMillis() / 1000L);
            db.beginTransaction();
            try {
                ContentValues v = new ContentValues();
                v.put("dialog_id", dialogId);
                v.put("message_id", messageId);
                v.put("ver", nextVer);
                v.put("kind", kind);
                v.put("text", text);
                v.put("media", media);
                v.put("captured_at", now);
                v.put("data", data);
                db.insertWithOnConflict("msg_versions", null, v, SQLiteDatabase.CONFLICT_REPLACE);

                int edits = 0;
                boolean wasDeleted = false;
                long knownFrom = fromId;
                int knownDate = date;
                boolean knownOut = out;
                Cursor ic = db.rawQuery("SELECT edits, deleted, from_id, msg_date, out FROM msg_index WHERE dialog_id = ? AND message_id = ?",
                        new String[]{Long.toString(dialogId), Integer.toString(messageId)});
                if (ic.moveToFirst()) {
                    edits = ic.getInt(0);
                    wasDeleted = ic.getInt(1) != 0;
                    if (knownFrom == 0) {
                        knownFrom = ic.getLong(2);
                    }
                    if (knownDate == 0) {
                        knownDate = ic.getInt(3);
                    }
                    if (!knownOut) {
                        knownOut = ic.getInt(4) != 0;
                    }
                }
                ic.close();

                if (kind == KIND_EDITED) {
                    edits++;
                }

                ContentValues idx = new ContentValues();
                idx.put("dialog_id", dialogId);
                idx.put("message_id", messageId);
                idx.put("from_id", knownFrom);
                idx.put("msg_date", knownDate);
                idx.put("out", knownOut ? 1 : 0);
                idx.put("deleted", (wasDeleted || kind == KIND_DELETED) ? 1 : 0);
                idx.put("edits", edits);
                idx.put("updated_at", now);
                idx.put("preview", preview(text, media));
                db.insertWithOnConflict("msg_index", null, idx, SQLiteDatabase.CONFLICT_REPLACE);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String preview(String text, String media) {
        if (text != null && text.length() > 0) {
            return text.length() > 160 ? text.substring(0, 160) : text;
        }
        return media;
    }

    public ArrayList<ArchivedMessage> getArchived(int filter, long dialogId, int limit, int offset) {
        ArrayList<ArchivedMessage> result = new ArrayList<>();
        SQLiteDatabase db = db();
        if (db == null) {
            return result;
        }
        StringBuilder where = new StringBuilder();
        ArrayList<String> args = new ArrayList<>();
        if (filter == FILTER_DELETED) {
            where.append(" WHERE deleted = 1");
        } else if (filter == FILTER_EDITED) {
            where.append(" WHERE edits > 0");
        }
        if (dialogId != 0) {
            where.append(where.length() == 0 ? " WHERE" : " AND").append(" dialog_id = ?");
            args.add(Long.toString(dialogId));
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT dialog_id, message_id, from_id, msg_date, out, deleted, edits, updated_at, preview FROM msg_index" +
                            where + " ORDER BY updated_at DESC LIMIT " + limit + " OFFSET " + offset,
                    args.toArray(new String[0]));
            while (c.moveToNext()) {
                ArchivedMessage m = new ArchivedMessage();
                m.dialogId = c.getLong(0);
                m.messageId = c.getInt(1);
                m.fromId = c.getLong(2);
                m.date = c.getInt(3);
                m.out = c.getInt(4) != 0;
                m.deleted = c.getInt(5) != 0;
                m.edits = c.getInt(6);
                m.updatedAt = c.getInt(7);
                m.preview = c.getString(8);
                result.add(m);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignore) {
                }
            }
        }
        return result;
    }

    public ArrayList<MessageVersion> getVersions(long dialogId, int messageId) {
        ArrayList<MessageVersion> result = new ArrayList<>();
        SQLiteDatabase db = db();
        if (db == null) {
            return result;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT ver, kind, text, media, captured_at, data FROM msg_versions WHERE dialog_id = ? AND message_id = ? ORDER BY ver ASC",
                    new String[]{Long.toString(dialogId), Integer.toString(messageId)});
            while (c.moveToNext()) {
                MessageVersion v = new MessageVersion();
                v.ver = c.getInt(0);
                v.kind = c.getInt(1);
                v.text = c.getString(2);
                v.media = c.getString(3);
                v.capturedAt = c.getInt(4);
                v.data = c.getBlob(5);
                result.add(v);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignore) {
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- maintenance

    public int[] counts() {
        int[] out = new int[]{0, 0, 0};
        SQLiteDatabase db = db();
        if (db == null) {
            return out;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT (SELECT COUNT(*) FROM online_users), " +
                    "(SELECT COUNT(*) FROM msg_index WHERE deleted = 1), " +
                    "(SELECT COUNT(*) FROM msg_index WHERE edits > 0)", null);
            if (c.moveToFirst()) {
                out[0] = c.getInt(0);
                out[1] = c.getInt(1);
                out[2] = c.getInt(2);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignore) {
                }
            }
        }
        return out;
    }

    public void clearOnline() {
        SQLiteDatabase db = db();
        if (db == null) {
            return;
        }
        try {
            db.execSQL("DELETE FROM online_events");
            db.execSQL("DELETE FROM online_users");
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void clearArchive() {
        SQLiteDatabase db = db();
        if (db == null) {
            return;
        }
        try {
            db.execSQL("DELETE FROM msg_versions");
            db.execSQL("DELETE FROM msg_index");
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void purgeOlderThan(int days) {
        if (days <= 0) {
            return;
        }
        SQLiteDatabase db = db();
        if (db == null) {
            return;
        }
        int cutoff = (int) (System.currentTimeMillis() / 1000L) - days * 24 * 60 * 60;
        try {
            db.execSQL("DELETE FROM online_events WHERE event_time < " + cutoff);
            db.execSQL("DELETE FROM msg_versions WHERE captured_at < " + cutoff);
            db.execSQL("DELETE FROM msg_index WHERE updated_at < " + cutoff);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
