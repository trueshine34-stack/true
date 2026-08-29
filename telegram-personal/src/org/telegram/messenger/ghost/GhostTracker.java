package org.telegram.messenger.ghost;

import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Collects everything this build remembers locally: when contacts were online, and the content of
 * messages right before they were deleted or edited.
 */
public class GhostTracker {

    private static final GhostTracker[] instances = new GhostTracker[UserConfig.MAX_ACCOUNT_COUNT];

    public static GhostTracker getInstance(int account) {
        GhostTracker local = instances[account];
        if (local == null) {
            synchronized (GhostTracker.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new GhostTracker(account);
                }
            }
        }
        return local;
    }

    private final int currentAccount;
    private final DispatchQueue queue;

    private GhostTracker(int account) {
        currentAccount = account;
        queue = new DispatchQueue("ghostTracker" + account);
    }

    private GhostStore store() {
        return GhostStore.getInstance(currentAccount);
    }

    // ---------------------------------------------------------------- online status

    public static int stateOf(TLRPC.UserStatus status) {
        if (status instanceof TLRPC.TL_userStatusOnline) {
            return GhostStore.STATE_ONLINE;
        } else if (status instanceof TLRPC.TL_userStatusOffline) {
            return GhostStore.STATE_OFFLINE;
        } else if (status instanceof TLRPC.TL_userStatusRecently) {
            return GhostStore.STATE_RECENTLY;
        } else if (status instanceof TLRPC.TL_userStatusLastWeek) {
            return GhostStore.STATE_LAST_WEEK;
        } else if (status instanceof TLRPC.TL_userStatusLastMonth) {
            return GhostStore.STATE_LAST_MONTH;
        }
        return GhostStore.STATE_HIDDEN;
    }

    /**
     * Called for every status we learn about, whether it arrives as an update or as part of a
     * user object. Storage work happens off the caller's thread.
     */
    public void trackStatus(long userId, TLRPC.UserStatus status) {
        if (!GhostConfig.isTrackOnline() || status == null || userId <= 0) {
            return;
        }
        if (userId == UserConfig.getInstance(currentAccount).getClientUserId()) {
            return;
        }
        final int state = stateOf(status);
        // The app rewrites "recently"/"last week"/"last month" expires to sentinel negatives; the
        // sentinel carries no timing information, so store 0 instead of the marker value.
        final int expires = state == GhostStore.STATE_ONLINE || state == GhostStore.STATE_OFFLINE
                ? Math.max(0, status.expires) : 0;
        final int now = (int) (System.currentTimeMillis() / 1000L);
        queue.postRunnable(() -> {
            boolean changed = store().putStatus(userId, state, expires, now);
            if (changed) {
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() ->
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.ghostOnlineTracked, userId));
            }
        });
    }

    public void trackStatuses(List<TLRPC.User> users) {
        if (!GhostConfig.isTrackOnline() || users == null) {
            return;
        }
        for (int a = 0; a < users.size(); a++) {
            TLRPC.User user = users.get(a);
            if (user != null && !user.bot && user.status != null) {
                trackStatus(user.id, user.status);
            }
        }
    }

    // ---------------------------------------------------------------- message archive

    /**
     * Copies messages out of the app database just before the app deletes them.
     * Must be called on {@link MessagesStorage#getStorageQueue()}, ahead of the delete.
     */
    public void archiveDeletedOnStorageQueue(long dialogId, ArrayList<Integer> ids) {
        if (!GhostConfig.isSaveDeleted() || ids == null || ids.isEmpty()) {
            return;
        }
        SQLiteCursor cursor = null;
        try {
            org.telegram.SQLite.SQLiteDatabase database = MessagesStorage.getInstance(currentAccount).getDatabase();
            if (database == null) {
                return;
            }
            String idsString = TextUtils.join(",", ids);
            if (dialogId != 0) {
                cursor = database.queryFinalized(String.format(Locale.US,
                        "SELECT uid, mid, data, out FROM messages_v2 WHERE mid IN(%s) AND uid = %d", idsString, dialogId));
            } else {
                cursor = database.queryFinalized(String.format(Locale.US,
                        "SELECT uid, mid, data, out FROM messages_v2 WHERE mid IN(%s) AND is_channel = 0", idsString));
            }
            long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            ArrayList<Object[]> pending = new ArrayList<>();
            while (cursor.next()) {
                long did = cursor.longValue(0);
                int mid = cursor.intValue(1);
                boolean out = cursor.intValue(3) != 0;
                NativeByteBuffer data = cursor.byteBufferValue(2);
                if (data == null) {
                    continue;
                }
                try {
                    int length = data.limit();
                    data.rewind();
                    byte[] raw = data.readData(length, false);
                    data.rewind();
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message == null) {
                        continue;
                    }
                    message.readAttachPath(data, selfId);
                    pending.add(new Object[]{did, mid, message, out, raw});
                } finally {
                    data.reuse();
                }
            }
            cursor.dispose();
            cursor = null;

            for (int a = 0; a < pending.size(); a++) {
                Object[] row = (Object[]) pending.get(a);
                long did = (Long) row[0];
                int mid = (Integer) row[1];
                TLRPC.Message message = (TLRPC.Message) row[2];
                boolean out = (Boolean) row[3];
                byte[] raw = (byte[]) row[4];
                store().addVersion(did, mid, senderOf(message), message.date, out || message.out,
                        GhostStore.KIND_DELETED, message.message, mediaLabel(message), raw);
            }
            if (!pending.isEmpty()) {
                notifyArchiveChanged();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                try {
                    cursor.dispose();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * Stores the version of a message that was live before an edit landed, then the edited one, so
     * the archive shows the full chain even for messages edited many times.
     */
    public void archiveEdited(TLRPC.Message newMessage) {
        if (!GhostConfig.isSaveEdited() || newMessage == null) {
            return;
        }
        final long dialogId = newMessage.dialog_id != 0 ? newMessage.dialog_id : org.telegram.messenger.MessageObject.getDialogId(newMessage);
        final int messageId = newMessage.id;
        final String newText = newMessage.message;
        final String newMedia = mediaLabel(newMessage);
        final long fromId = senderOf(newMessage);
        final int date = newMessage.date;
        final boolean out = newMessage.out;
        final byte[] newRaw = serialize(newMessage);

        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            SQLiteCursor cursor = null;
            try {
                org.telegram.SQLite.SQLiteDatabase database = MessagesStorage.getInstance(currentAccount).getDatabase();
                if (database != null) {
                    cursor = database.queryFinalized(String.format(Locale.US,
                            "SELECT data FROM messages_v2 WHERE mid = %d AND uid = %d", messageId, dialogId));
                    if (cursor.next()) {
                        NativeByteBuffer data = cursor.byteBufferValue(0);
                        if (data != null) {
                            try {
                                int length = data.limit();
                                data.rewind();
                                byte[] raw = data.readData(length, false);
                                data.rewind();
                                TLRPC.Message old = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                                if (old != null) {
                                    old.readAttachPath(data, UserConfig.getInstance(currentAccount).getClientUserId());
                                    store().addVersion(dialogId, messageId, senderOf(old), old.date, old.out,
                                            GhostStore.KIND_ORIGINAL, old.message, mediaLabel(old), raw);
                                }
                            } finally {
                                data.reuse();
                            }
                        }
                    }
                    cursor.dispose();
                    cursor = null;
                }
                store().addVersion(dialogId, messageId, fromId, date, out,
                        GhostStore.KIND_EDITED, newText, newMedia, newRaw);
                notifyArchiveChanged();
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    try {
                        cursor.dispose();
                    } catch (Throwable ignore) {
                    }
                }
            }
        });
    }

    private void notifyArchiveChanged() {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.ghostArchiveUpdated));
    }

    private static long senderOf(TLRPC.Message message) {
        if (message == null) {
            return 0;
        }
        if (message.from_id != null) {
            return org.telegram.messenger.DialogObject.getPeerDialogId(message.from_id);
        }
        if (message.peer_id != null) {
            return org.telegram.messenger.DialogObject.getPeerDialogId(message.peer_id);
        }
        return 0;
    }

    private static byte[] serialize(TLRPC.Message message) {
        NativeByteBuffer buffer = null;
        try {
            buffer = new NativeByteBuffer(message.getObjectSize());
            message.serializeToStream(buffer);
            int length = buffer.position();
            buffer.rewind();
            return buffer.readData(length, false);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (buffer != null) {
                try {
                    buffer.reuse();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** Short, human readable description of the attachment of a message, or null for plain text. */
    public static String mediaLabel(TLRPC.Message message) {
        if (message == null || message.media == null || message.media instanceof TLRPC.TL_messageMediaEmpty) {
            return null;
        }
        TLRPC.MessageMedia media = message.media;
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            return "photo";
        } else if (media instanceof TLRPC.TL_messageMediaDocument) {
            TLRPC.Document document = media.document;
            if (document != null) {
                for (int a = 0; a < document.attributes.size(); a++) {
                    TLRPC.DocumentAttribute attribute = document.attributes.get(a);
                    if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                        return ((TLRPC.TL_documentAttributeAudio) attribute).voice ? "voice" : "audio";
                    } else if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                        return ((TLRPC.TL_documentAttributeVideo) attribute).round_message ? "video message" : "video";
                    } else if (attribute instanceof TLRPC.TL_documentAttributeSticker) {
                        return "sticker";
                    } else if (attribute instanceof TLRPC.TL_documentAttributeAnimated) {
                        return "gif";
                    }
                }
                if (!TextUtils.isEmpty(document.file_name_fixed)) {
                    return document.file_name_fixed;
                }
            }
            return "file";
        } else if (media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaGeoLive) {
            return "location";
        } else if (media instanceof TLRPC.TL_messageMediaVenue) {
            return "venue";
        } else if (media instanceof TLRPC.TL_messageMediaContact) {
            return "contact";
        } else if (media instanceof TLRPC.TL_messageMediaPoll) {
            return "poll";
        } else if (media instanceof TLRPC.TL_messageMediaWebPage) {
            return null;
        }
        return "attachment";
    }
}
