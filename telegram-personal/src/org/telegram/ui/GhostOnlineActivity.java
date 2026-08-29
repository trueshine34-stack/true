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
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/** People whose online status this device has been writing down, most recently active first. */
public class GhostOnlineActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final ArrayList<GhostStore.TrackedUser> users = new ArrayList<>();
    private boolean loading = true;

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.ghostOnlineTracked);
        load();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.ghostOnlineTracked);
        super.onFragmentDestroy();
    }

    private void load() {
        final int account = currentAccount;
        new Thread(() -> {
            final ArrayList<GhostStore.TrackedUser> loaded = GhostStore.getInstance(account).getTrackedUsers();
            // Warm the user cache so names and avatars are ready by the time the list binds.
            final ArrayList<TLRPC.User> fetched = new ArrayList<>();
            for (int a = 0; a < loaded.size(); a++) {
                long id = loaded.get(a).userId;
                if (MessagesController.getInstance(account).getUser(id) == null) {
                    TLRPC.User user = MessagesStorage.getInstance(account).getUserSync(id);
                    if (user != null) {
                        fetched.add(user);
                    }
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                for (int a = 0; a < fetched.size(); a++) {
                    MessagesController.getInstance(account).putUser(fetched.get(a), true);
                }
                users.clear();
                users.addAll(loaded);
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
        actionBar.setTitle(GhostStrings.get("OpenOnline"));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter());
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < users.size()) {
                presentFragment(new GhostOnlineUserActivity(users.get(position).userId));
            }
        });

        return fragmentView;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.ghostOnlineTracked) {
            load();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private static final int TYPE_USER = 0;
        private static final int TYPE_EMPTY = 1;

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_USER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_USER) {
                view = new UserCell(parent.getContext(), 6, 0, false);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextInfoPrivacyCell(parent.getContext());
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == TYPE_USER) {
                GhostStore.TrackedUser tracked = users.get(position);
                TLRPC.User user = getMessagesController().getUser(tracked.userId);
                CharSequence name = user != null ? UserObject.getUserName(user) : ("#" + tracked.userId);
                ((UserCell) holder.itemView).setData(user, name, GhostFormat.summary(tracked), 0, position != users.size() - 1);
            } else {
                ((TextInfoPrivacyCell) holder.itemView).setText(
                        loading ? "" : GhostStrings.get("OnlineEmpty") + "\n" + GhostStrings.get("OnlineEmptyInfo"));
            }
        }

        @Override
        public int getItemViewType(int position) {
            return users.isEmpty() ? TYPE_EMPTY : TYPE_USER;
        }

        @Override
        public int getItemCount() {
            return users.isEmpty() ? 1 : users.size();
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
