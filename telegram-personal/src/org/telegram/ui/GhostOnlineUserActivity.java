package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
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
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Online timeline of a single person: every stretch of time this device saw them online,
 * grouped by day with per-day totals.
 */
public class GhostOnlineUserActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    /** Two "online" pings closer than this belong to the same visit. */
    private static final int SESSION_GAP = 5 * 60;

    private static final int MENU_7 = 1;
    private static final int MENU_30 = 2;
    private static final int MENU_ALL = 3;

    private final long userId;
    private int periodDays = 7;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private GhostStore.TrackedUser summary;
    private int eventCount;
    private final ArrayList<Item> items = new ArrayList<>();

    public GhostOnlineUserActivity(long userId) {
        this.userId = userId;
    }

    private static class Session {
        int start;
        int end;
        boolean ongoing;
    }

    private static class Item {
        static final int TYPE_USER = 0;
        static final int TYPE_DAY = 1;
        static final int TYPE_SESSION = 2;
        static final int TYPE_INFO = 3;

        int type;
        String title;
        String value;
        boolean divider;

        Item(int type, String title, String value) {
            this.type = type;
            this.title = title;
            this.value = value;
        }
    }

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
        final int since = periodDays <= 0 ? 0 : (int) (System.currentTimeMillis() / 1000L) - periodDays * 24 * 60 * 60;
        new Thread(() -> {
            GhostStore store = GhostStore.getInstance(account);
            final ArrayList<GhostStore.StatusEvent> events = store.getEvents(userId, since);
            GhostStore.TrackedUser found = null;
            ArrayList<GhostStore.TrackedUser> all = store.getTrackedUsers();
            for (int a = 0; a < all.size(); a++) {
                if (all.get(a).userId == userId) {
                    found = all.get(a);
                    break;
                }
            }
            if (MessagesController.getInstance(account).getUser(userId) == null) {
                TLRPC.User user = MessagesStorage.getInstance(account).getUserSync(userId);
                if (user != null) {
                    final TLRPC.User finalUser = user;
                    AndroidUtilities.runOnUIThread(() -> MessagesController.getInstance(account).putUser(finalUser, true));
                }
            }
            final GhostStore.TrackedUser finalFound = found;
            AndroidUtilities.runOnUIThread(() -> {
                summary = finalFound;
                eventCount = events.size();
                buildItems(buildSessions(events));
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        }).start();
    }

    /**
     * Turns the raw stream of status observations into visits. An "offline" event carries the
     * moment the person actually went offline, which closes the visit exactly.
     */
    private ArrayList<Session> buildSessions(ArrayList<GhostStore.StatusEvent> events) {
        ArrayList<Session> sessions = new ArrayList<>();
        Session open = null;
        int now = (int) (System.currentTimeMillis() / 1000L);
        for (int a = 0; a < events.size(); a++) {
            GhostStore.StatusEvent e = events.get(a);
            if (e.state == GhostStore.STATE_ONLINE) {
                if (open != null && e.eventTime - open.end <= SESSION_GAP) {
                    open.end = Math.max(open.end, e.eventTime);
                } else {
                    open = new Session();
                    open.start = e.eventTime;
                    open.end = e.eventTime;
                    sessions.add(open);
                }
                open.ongoing = e.expires > now;
            } else if (e.state == GhostStore.STATE_OFFLINE) {
                int wentOffline = e.expires > 0 ? Math.min(e.expires, e.eventTime) : e.eventTime;
                if (open != null) {
                    open.end = Math.max(open.end, wentOffline);
                    open.ongoing = false;
                    open = null;
                } else if (e.expires > 0) {
                    Session point = new Session();
                    point.start = wentOffline;
                    point.end = wentOffline;
                    sessions.add(point);
                }
            } else {
                open = null;
            }
        }
        return sessions;
    }

    private void buildItems(ArrayList<Session> sessions) {
        items.clear();
        items.add(new Item(Item.TYPE_USER, null, null));

        int lastDay = -1;
        int dayIndexStart = -1;
        for (int a = sessions.size() - 1; a >= 0; a--) {
            Session s = sessions.get(a);
            int day = GhostFormat.dayStart(s.start);
            if (day != lastDay) {
                if (dayIndexStart >= 0) {
                    items.get(items.size() - 1).divider = false;
                }
                int total = 0;
                for (int b = a; b >= 0; b--) {
                    if (GhostFormat.dayStart(sessions.get(b).start) != day) {
                        break;
                    }
                    total += Math.max(0, sessions.get(b).end - sessions.get(b).start);
                }
                items.add(new Item(Item.TYPE_DAY, GhostFormat.dayLabel(s.start),
                        GhostStrings.format("TotalPeriod", GhostFormat.duration(total))));
                lastDay = day;
                dayIndexStart = items.size();
            }
            String range = s.end > s.start
                    ? GhostStrings.format("SessionRange", GhostFormat.time(s.start), GhostFormat.time(s.end))
                    : GhostFormat.time(s.start);
            String value = s.ongoing ? GhostStrings.get("Ongoing") : GhostFormat.duration(Math.max(0, s.end - s.start));
            Item item = new Item(Item.TYPE_SESSION, range, value);
            item.divider = true;
            items.add(item);
        }
        if (!items.isEmpty()) {
            items.get(items.size() - 1).divider = false;
        }

        if (sessions.isEmpty()) {
            items.add(new Item(Item.TYPE_INFO, GhostStrings.get("OnlineEmptyInfo"), null));
        } else {
            items.add(new Item(Item.TYPE_INFO, GhostStrings.format("Points", String.valueOf(eventCount)), null));
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(GhostStrings.get("SessionsHeader"));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_7) {
                    periodDays = 7;
                    load();
                } else if (id == MENU_30) {
                    periodDays = 30;
                    load();
                } else if (id == MENU_ALL) {
                    periodDays = 0;
                    load();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(MENU_7, GhostStrings.get("Period7"));
        other.addSubItem(MENU_30, GhostStrings.get("Period30"));
        other.addSubItem(MENU_ALL, GhostStrings.get("PeriodAll"));

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter());
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position == 0) {
                Bundle args = new Bundle();
                args.putLong("user_id", userId);
                if (getMessagesController().checkCanOpenChat(args, GhostOnlineUserActivity.this)) {
                    presentFragment(new ChatActivity(args));
                }
            }
        });

        return fragmentView;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.ghostOnlineTracked && args.length > 0 && args[0] instanceof Long
                && (Long) args[0] == userId) {
            load();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == Item.TYPE_USER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            if (viewType == Item.TYPE_USER) {
                view = new UserCell(context, 6, 0, false);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == Item.TYPE_DAY) {
                view = new HeaderCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == Item.TYPE_SESSION) {
                view = new TextSettingsCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Item item = items.get(position);
            if (item.type == Item.TYPE_USER) {
                TLRPC.User user = getMessagesController().getUser(userId);
                CharSequence name = user != null ? UserObject.getUserName(user) : ("#" + userId);
                String status = summary != null ? GhostFormat.summary(summary) : GhostStrings.get("NeverOnline");
                ((UserCell) holder.itemView).setData(user, name, status, 0, false);
            } else if (item.type == Item.TYPE_DAY) {
                ((HeaderCell) holder.itemView).setText(item.title + " · " + item.value);
            } else if (item.type == Item.TYPE_SESSION) {
                ((TextSettingsCell) holder.itemView).setTextAndValue(item.title, item.value, item.divider);
            } else {
                ((TextInfoPrivacyCell) holder.itemView).setText(item.title);
            }
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @Override
        public int getItemCount() {
            return items.size();
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
