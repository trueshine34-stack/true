package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.ghost.GhostFormat;
import org.telegram.messenger.ghost.GhostStore;
import org.telegram.messenger.ghost.GhostStrings;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/** Every message this device saw get deleted or edited, newest change first. */
public class GhostArchiveActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int PAGE_SIZE = 200;

    private static final int MENU_ALL = 1;
    private static final int MENU_DELETED = 2;
    private static final int MENU_EDITED = 3;

    private final long filterDialogId;
    private int filter = GhostStore.FILTER_ALL;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final ArrayList<GhostStore.ArchivedMessage> messages = new ArrayList<>();
    private boolean loading = true;

    public GhostArchiveActivity() {
        this(0);
    }

    public GhostArchiveActivity(long dialogId) {
        filterDialogId = dialogId;
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.ghostArchiveUpdated);
        load();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.ghostArchiveUpdated);
        super.onFragmentDestroy();
    }

    private void load() {
        final int account = currentAccount;
        final int currentFilter = filter;
        new Thread(() -> {
            final ArrayList<GhostStore.ArchivedMessage> loaded =
                    GhostStore.getInstance(account).getArchived(currentFilter, filterDialogId, PAGE_SIZE, 0);
            final ArrayList<TLRPC.User> users = new ArrayList<>();
            final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
            for (int a = 0; a < loaded.size(); a++) {
                long dialogId = loaded.get(a).dialogId;
                if (dialogId > 0) {
                    if (MessagesController.getInstance(account).getUser(dialogId) == null) {
                        TLRPC.User user = MessagesStorage.getInstance(account).getUserSync(dialogId);
                        if (user != null) {
                            users.add(user);
                        }
                    }
                } else if (dialogId < 0) {
                    if (MessagesController.getInstance(account).getChat(-dialogId) == null) {
                        TLRPC.Chat chat = MessagesStorage.getInstance(account).getChatSync(-dialogId);
                        if (chat != null) {
                            chats.add(chat);
                        }
                    }
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController.getInstance(account).putUsers(users, true);
                MessagesController.getInstance(account).putChats(chats, true);
                messages.clear();
                messages.addAll(loaded);
                loading = false;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        }).start();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(GhostStrings.get("OpenArchive"));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_ALL) {
                    filter = GhostStore.FILTER_ALL;
                    load();
                } else if (id == MENU_DELETED) {
                    filter = GhostStore.FILTER_DELETED;
                    load();
                } else if (id == MENU_EDITED) {
                    filter = GhostStore.FILTER_EDITED;
                    load();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(MENU_ALL, GhostStrings.get("FilterAll"));
        other.addSubItem(MENU_DELETED, GhostStrings.get("FilterDeleted"));
        other.addSubItem(MENU_EDITED, GhostStrings.get("FilterEdited"));

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter());
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < messages.size()) {
                GhostStore.ArchivedMessage m = messages.get(position);
                presentFragment(new GhostArchiveMessageActivity(m.dialogId, m.messageId));
            }
        });

        return fragmentView;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.ghostArchiveUpdated) {
            load();
        }
    }

    private CharSequence titleOf(long dialogId) {
        if (dialogId > 0) {
            TLRPC.User user = getMessagesController().getUser(dialogId);
            return user != null ? UserObject.getUserName(user) : ("#" + dialogId);
        }
        TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        return chat != null ? chat.title : ("#" + dialogId);
    }

    private Object avatarOf(long dialogId) {
        return dialogId > 0 ? getMessagesController().getUser(dialogId) : getMessagesController().getChat(-dialogId);
    }

    private String badgeOf(GhostStore.ArchivedMessage m) {
        if (m.deleted) {
            return GhostStrings.get("WasDeleted");
        }
        return GhostStrings.format("WasEdited", m.edits);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private static final int TYPE_MESSAGE = 0;
        private static final int TYPE_EMPTY = 1;

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_MESSAGE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_MESSAGE) {
                view = new UserCell(parent.getContext(), 6, 0, false);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextInfoPrivacyCell(parent.getContext());
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == TYPE_MESSAGE) {
                GhostStore.ArchivedMessage m = messages.get(position);
                String preview = m.preview;
                if (preview == null || preview.length() == 0) {
                    preview = GhostStrings.get("NoText");
                }
                String status = badgeOf(m) + " · " + GhostFormat.dateTime(m.updatedAt) + " — " + preview;
                ((UserCell) holder.itemView).setData(avatarOf(m.dialogId), titleOf(m.dialogId), status, 0,
                        position != messages.size() - 1);
            } else {
                ((TextInfoPrivacyCell) holder.itemView).setText(
                        loading ? "" : GhostStrings.get("ArchiveEmpty") + "\n" + GhostStrings.get("ArchiveEmptyInfo"));
            }
        }

        @Override
        public int getItemViewType(int position) {
            return messages.isEmpty() ? TYPE_EMPTY : TYPE_MESSAGE;
        }

        @Override
        public int getItemCount() {
            return messages.isEmpty() ? 1 : messages.size();
        }
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (listView != null) {
            listView.setPadding(0, 0, 0, bottom);
            listView.setClipToPadding(false);
        }
    }
}
