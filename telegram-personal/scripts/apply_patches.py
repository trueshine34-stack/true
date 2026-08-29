#!/usr/bin/env python3
"""
Applies the personal-build changes to a checkout of the official Telegram Android client.

New screens and storage live in their own files under src/ and are simply copied in. What this
script does is splice the handful of call sites in the upstream classes that have to change:

  MessagesController  - never announce online / typing / read receipts, record contact statuses,
                        archive a message right before an edit is applied
  MessagesStorage     - archive messages right before they are deleted
  NotificationCenter  - two extra in-app events so the new screens refresh live
  PrivacySettings     - one row that opens the new settings screen

Every splice is anchored on an exact snippet of upstream source. If an anchor is missing the
script stops instead of producing a half-patched tree, which is what you want when moving the
patch to a newer Telegram release.

Usage: apply_patches.py <path-to-Telegram-checkout>
"""

import os
import shutil
import sys

MARKER = "org.telegram.messenger.ghost"

JAVA_ROOT = os.path.join("TMessagesProj", "src", "main", "java")


class PatchError(Exception):
    pass


def splice(text, anchor, replacement, name):
    count = text.count(anchor)
    if count != 1:
        raise PatchError(
            "anchor for '%s' matched %d times, expected exactly 1.\n"
            "Upstream changed here; re-anchor the patch." % (name, count)
        )
    return text.replace(anchor, replacement)


def patch_file(root, rel_path, edits, already_applied_probe):
    path = os.path.join(root, JAVA_ROOT, rel_path)
    if not os.path.isfile(path):
        raise PatchError("missing source file: %s" % path)
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    if already_applied_probe in text:
        print("  = %s already patched" % rel_path)
        return
    for name, anchor, replacement in edits:
        text = splice(text, anchor, replacement, "%s / %s" % (rel_path, name))
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)
    print("  + %s (%d edits)" % (rel_path, len(edits)))


# --------------------------------------------------------------------------- NotificationCenter

NOTIFICATION_CENTER = [(
    "extra events",
    "    public static final int communitySwitchedCollapsed = totalEvents++;\n",
    "    public static final int communitySwitchedCollapsed = totalEvents++;\n"
    "\n"
    "    // personal build: local online log and message archive changed\n"
    "    public static final int ghostOnlineTracked = totalEvents++;\n"
    "    public static final int ghostArchiveUpdated = totalEvents++;\n",
)]

# --------------------------------------------------------------------------- MessagesController

MESSAGES_CONTROLLER = [
    (
        "imports",
        "import org.telegram.messenger.browser.Browser;\n",
        "import org.telegram.messenger.browser.Browser;\n"
        "import org.telegram.messenger.ghost.GhostConfig;\n"
        "import org.telegram.messenger.ghost.GhostTracker;\n",
    ),
    (
        "never go online",
        "            if (!ignoreSetOnline && getConnectionsManager().getPauseTime() == 0 && ApplicationLoader.isScreenOn && !ApplicationLoader.mainInterfacePausedStageQueue) {\n",
        "            if (GhostConfig.isHideOnline()) {\n"
        "                // Ghost mode: skip account.updateStatus in both directions. Sending\n"
        "                // offline=true would move \"last seen\" to now, which is exactly the leak\n"
        "                // we are avoiding, so the server simply never hears from this device.\n"
        "            } else if (!ignoreSetOnline && getConnectionsManager().getPauseTime() == 0 && ApplicationLoader.isScreenOn && !ApplicationLoader.mainInterfacePausedStageQueue) {\n",
    ),
    (
        "never send typing",
        "    public boolean sendTyping(long dialogId, long threadMsgId, int action, String emojicon, int classGuid) {\n"
        "        if (action < 0 || action >= sendingTypings.length || dialogId == 0) {\n"
        "            return false;\n"
        "        }\n",
        "    public boolean sendTyping(long dialogId, long threadMsgId, int action, String emojicon, int classGuid) {\n"
        "        if (action < 0 || action >= sendingTypings.length || dialogId == 0) {\n"
        "            return false;\n"
        "        }\n"
        "        if (GhostConfig.isHideTyping()) {\n"
        "            return false;\n"
        "        }\n",
    ),
    (
        "never send read receipts",
        "    private void completeReadTask(ReadTask task) {\n",
        "    private void completeReadTask(ReadTask task) {\n"
        "        if (GhostConfig.isHideReadReceipts()) {\n"
        "            // Local unread state was already cleared by markDialogAsRead; only the\n"
        "            // network half of the read receipt is dropped.\n"
        "            return;\n"
        "        }\n",
    ),
    (
        "record status updates",
        "                        TLRPC.User toDbUser = new TLRPC.TL_user();\n"
        "                        toDbUser.id = update.user_id;\n"
        "                        toDbUser.status = update.status;\n"
        "                        dbUsersStatus.add(toDbUser);\n",
        "                        TLRPC.User toDbUser = new TLRPC.TL_user();\n"
        "                        toDbUser.id = update.user_id;\n"
        "                        toDbUser.status = update.status;\n"
        "                        dbUsersStatus.add(toDbUser);\n"
        "                        GhostTracker.getInstance(currentAccount).trackStatus(update.user_id, update.status);\n",
    ),
    (
        "record statuses carried by user objects",
        "        boolean updateStatus = false;\n"
        "        int count = users.size();\n"
        "        for (int a = 0; a < count; a++) {\n"
        "            TLRPC.User user = users.get(a);\n"
        "            if (putUser(user, fromCache)) {\n"
        "                updateStatus = true;\n"
        "            }\n"
        "        }\n",
        "        boolean updateStatus = false;\n"
        "        int count = users.size();\n"
        "        for (int a = 0; a < count; a++) {\n"
        "            TLRPC.User user = users.get(a);\n"
        "            if (putUser(user, fromCache)) {\n"
        "                updateStatus = true;\n"
        "            }\n"
        "        }\n"
        "        if (!fromCache) {\n"
        "            // Cached copies replay stale statuses, so only fresh server data is logged.\n"
        "            GhostTracker.getInstance(currentAccount).trackStatuses(users);\n"
        "        }\n",
    ),
    (
        "archive edits",
        "                MessageObject.getDialogId(message);\n"
        "\n"
        "                ConcurrentHashMap<Long, Integer> read_max = message.out ? dialogs_read_outbox_max : dialogs_read_inbox_max;\n",
        "                MessageObject.getDialogId(message);\n"
        "\n"
        "                // Queued before the storage write further down, so the copy of the message\n"
        "                // still holds the text that is about to be replaced.\n"
        "                GhostTracker.getInstance(currentAccount).archiveEdited(message);\n"
        "\n"
        "                ConcurrentHashMap<Long, Integer> read_max = message.out ? dialogs_read_outbox_max : dialogs_read_inbox_max;\n",
    ),
]

# --------------------------------------------------------------------------- MessagesStorage

MESSAGES_STORAGE = [
    (
        "imports",
        "import org.telegram.messenger.support.LongSparseIntArray;\n",
        "import org.telegram.messenger.ghost.GhostConfig;\n"
        "import org.telegram.messenger.ghost.GhostTracker;\n"
        "import org.telegram.messenger.support.LongSparseIntArray;\n",
    ),
    (
        "archive deletions",
        "    public ArrayList<Long> markMessagesAsDeleted(long dialogId, ArrayList<Integer> messages, boolean useQueue, boolean deleteFiles, int mode, int topicId) {\n"
        "        if (messages.isEmpty()) {\n"
        "            return null;\n"
        "        }\n",
        "    public ArrayList<Long> markMessagesAsDeleted(long dialogId, ArrayList<Integer> messages, boolean useQueue, boolean deleteFiles, int mode, int topicId) {\n"
        "        if (messages.isEmpty()) {\n"
        "            return null;\n"
        "        }\n"
        "        if (mode == 0 && GhostConfig.isSaveDeleted()) {\n"
        "            // Runs on the same serial storage queue, ahead of the delete below, so the rows\n"
        "            // are still there to copy.\n"
        "            final ArrayList<Integer> toArchive = new ArrayList<>(messages);\n"
        "            if (useQueue) {\n"
        "                storageQueue.postRunnable(() -> GhostTracker.getInstance(currentAccount).archiveDeletedOnStorageQueue(dialogId, toArchive));\n"
        "            } else {\n"
        "                GhostTracker.getInstance(currentAccount).archiveDeletedOnStorageQueue(dialogId, toArchive);\n"
        "            }\n"
        "        }\n",
    ),
    (
        "archive secret chat deletions",
        "                        AndroidUtilities.runOnUIThread(() -> getNotificationCenter().postNotificationName(NotificationCenter.messagesDeleted, mids, 0L, false));\n"
        "                        updateDialogsWithReadMessagesInternal(mids, null, null, null, null);\n"
        "                        markMessagesAsDeletedInternal(dialogId, mids, true, 0, 0);\n",
        "                        AndroidUtilities.runOnUIThread(() -> getNotificationCenter().postNotificationName(NotificationCenter.messagesDeleted, mids, 0L, false));\n"
        "                        updateDialogsWithReadMessagesInternal(mids, null, null, null, null);\n"
        "                        if (GhostConfig.isSaveDeleted()) {\n"
        "                            GhostTracker.getInstance(currentAccount).archiveDeletedOnStorageQueue(dialogId, mids);\n"
        "                        }\n"
        "                        markMessagesAsDeletedInternal(dialogId, mids, true, 0, 0);\n",
    ),
]

# --------------------------------------------------------------------------- PrivacySettingsActivity

PRIVACY_SETTINGS = [
    (
        "import",
        "import org.telegram.messenger.ContactsController;\n",
        "import org.telegram.messenger.ContactsController;\n"
        "import org.telegram.messenger.ghost.GhostStrings;\n",
    ),
    (
        "row field",
        "    @Keep\n"
        "    private int passcodeRow;\n",
        "    @Keep\n"
        "    private int passcodeRow;\n"
        "    private int ghostRow;\n",
    ),
    (
        "row index",
        "        passcodeRow = rowCount++;\n",
        "        passcodeRow = rowCount++;\n"
        "        ghostRow = rowCount++;\n",
    ),
    (
        "click",
        "            } else if (position == passcodeRow) {\n"
        "                presentFragment(PasscodeActivity.determineOpenFragment());\n",
        "            } else if (position == ghostRow) {\n"
        "                presentFragment(new GhostSettingsActivity());\n"
        "            } else if (position == passcodeRow) {\n"
        "                presentFragment(PasscodeActivity.determineOpenFragment());\n",
    ),
    (
        "enabled",
        "            return position == passcodeRow || position == passwordRow ||",
        "            return position == ghostRow || position == passcodeRow || position == passwordRow ||",
    ),
    (
        "bind",
        "                    } else if (position == passcodeRow) {\n"
        "                        int icon;\n",
        "                    } else if (position == ghostRow) {\n"
        "                        textCell2.setTextAndValueAndIcon(GhostStrings.get(\"Title\"), \"\", true, R.drawable.msg2_secret, true);\n"
        "                    } else if (position == passcodeRow) {\n"
        "                        int icon;\n",
    ),
    (
        "view type",
        "            } else if (position == autoDeleteMesages || position == sessionsRow || position == emailLoginRow || position == passwordRow || position == passkeysRow || position == passcodeRow || position == blockedRow) {\n",
        "            } else if (position == ghostRow || position == autoDeleteMesages || position == sessionsRow || position == emailLoginRow || position == passwordRow || position == passkeysRow || position == passcodeRow || position == blockedRow) {\n",
    ),
]


def copy_sources(repo_root, target_root):
    src_root = os.path.join(repo_root, "src")
    copied = 0
    for dirpath, _dirnames, filenames in os.walk(src_root):
        for filename in filenames:
            if not filename.endswith(".java"):
                continue
            source = os.path.join(dirpath, filename)
            relative = os.path.relpath(source, src_root)
            destination = os.path.join(target_root, JAVA_ROOT, relative)
            os.makedirs(os.path.dirname(destination), exist_ok=True)
            shutil.copyfile(source, destination)
            copied += 1
    print("  + %d new source files" % copied)


def main():
    if len(sys.argv) != 2:
        print(__doc__.strip())
        return 2
    target = os.path.abspath(sys.argv[1])
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    if not os.path.isdir(os.path.join(target, JAVA_ROOT)):
        print("error: %s does not look like a Telegram-Android checkout" % target)
        return 1

    print("patching %s" % target)
    copy_sources(repo_root, target)
    try:
        patch_file(target, "org/telegram/messenger/NotificationCenter.java",
                   NOTIFICATION_CENTER, "ghostOnlineTracked")
        patch_file(target, "org/telegram/messenger/MessagesController.java",
                   MESSAGES_CONTROLLER, MARKER)
        patch_file(target, "org/telegram/messenger/MessagesStorage.java",
                   MESSAGES_STORAGE, MARKER)
        patch_file(target, "org/telegram/ui/PrivacySettingsActivity.java",
                   PRIVACY_SETTINGS, MARKER)
    except PatchError as error:
        print("error: %s" % error)
        return 1
    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
