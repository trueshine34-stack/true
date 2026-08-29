package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.ghost.GhostFormat;
import org.telegram.messenger.ghost.GhostStore;
import org.telegram.messenger.ghost.GhostStrings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/** Full version chain of one archived message: original, every edit, and the pre-deletion copy. */
public class GhostArchiveMessageActivity extends BaseFragment {

    private final long dialogId;
    private final int messageId;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final ArrayList<GhostStore.MessageVersion> versions = new ArrayList<>();

    public GhostArchiveMessageActivity(long dialogId, int messageId) {
        this.dialogId = dialogId;
        this.messageId = messageId;
    }

    @Override
    public boolean onFragmentCreate() {
        final int account = currentAccount;
        new Thread(() -> {
            final ArrayList<GhostStore.MessageVersion> loaded = GhostStore.getInstance(account).getVersions(dialogId, messageId);
            AndroidUtilities.runOnUIThread(() -> {
                versions.clear();
                versions.addAll(loaded);
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        }).start();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(GhostStrings.get("MessageHistory"));
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
            if (position < versions.size()) {
                GhostStore.MessageVersion v = versions.get(position);
                String text = v.text != null ? v.text : v.media;
                if (text != null && text.length() > 0) {
                    AndroidUtilities.addToClipboard(text);
                    BulletinFactory.of(this).createCopyBulletin(GhostStrings.get("Copied")).show();
                }
            } else {
                Bundle args = new Bundle();
                if (dialogId > 0) {
                    args.putLong("user_id", dialogId);
                } else {
                    args.putLong("chat_id", -dialogId);
                }
                args.putInt("message_id", messageId);
                if (getMessagesController().checkCanOpenChat(args, GhostArchiveMessageActivity.this)) {
                    presentFragment(new ChatActivity(args));
                }
            }
        });

        return fragmentView;
    }

    private String labelOf(GhostStore.MessageVersion v, int index) {
        if (v.kind == GhostStore.KIND_DELETED) {
            return GhostStrings.get("VersionDeleted");
        }
        if (index == 0 || v.kind == GhostStore.KIND_ORIGINAL) {
            return GhostStrings.get("VersionOriginal");
        }
        return GhostStrings.format("VersionEdited", index);
    }

    /** Two-line row: which version this is, and the text it held. */
    private static class VersionCell extends LinearLayout {

        private final TextView headerView;
        private final TextView textView;

        VersionCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(21), dp(10), dp(21), dp(12));
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            headerView = new TextView(context);
            headerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            headerView.setTypeface(AndroidUtilities.bold());
            headerView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            headerView.setGravity(Gravity.START);
            addView(headerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setGravity(Gravity.START);
            addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }

        void set(String header, String text) {
            headerView.setText(header);
            textView.setText(text);
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private static final int TYPE_VERSION = 0;
        private static final int TYPE_OPEN = 1;
        private static final int TYPE_INFO = 2;

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != TYPE_INFO;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            if (viewType == TYPE_VERSION) {
                view = new VersionCell(context);
            } else if (viewType == TYPE_OPEN) {
                view = new TextSettingsCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == TYPE_VERSION) {
                GhostStore.MessageVersion v = versions.get(position);
                String body = v.text != null && v.text.length() > 0 ? v.text : null;
                if (body == null) {
                    body = v.media != null ? "[" + v.media + "]" : GhostStrings.get("NoText");
                } else if (v.media != null) {
                    body = "[" + v.media + "]\n" + body;
                }
                ((VersionCell) holder.itemView).set(
                        labelOf(v, position) + " · " + GhostFormat.dateTime(v.capturedAt), body);
            } else if (holder.getItemViewType() == TYPE_OPEN) {
                ((TextSettingsCell) holder.itemView).setText(GhostStrings.get("OpenChat"), false);
            } else {
                ((TextInfoPrivacyCell) holder.itemView).setText(GhostStrings.get("CopyText"));
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position < versions.size()) {
                return TYPE_VERSION;
            } else if (position == versions.size()) {
                return TYPE_OPEN;
            }
            return TYPE_INFO;
        }

        @Override
        public int getItemCount() {
            return versions.size() + 2;
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
