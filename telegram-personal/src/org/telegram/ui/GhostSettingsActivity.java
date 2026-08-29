package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.ghost.GhostConfig;
import org.telegram.messenger.ghost.GhostStore;
import org.telegram.messenger.ghost.GhostStrings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/**
 * Entry point for everything this build adds: the ghost-mode switches and the two local logs.
 */
public class GhostSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private RecyclerListView listView;
    private ListAdapter adapter;

    private int rowCount;
    private int ghostHeaderRow;
    private int hideOnlineRow;
    private int hideTypingRow;
    private int hideReadRow;
    private int ghostInfoRow;
    private int trackHeaderRow;
    private int trackOnlineRow;
    private int saveDeletedRow;
    private int saveEditedRow;
    private int trackInfoRow;
    private int openOnlineRow;
    private int openArchiveRow;
    private int openInfoRow;
    private int dataHeaderRow;
    private int clearOnlineRow;
    private int clearArchiveRow;
    private int dataInfoRow;

    private int[] counts = new int[]{0, 0, 0};

    @Override
    public boolean onFragmentCreate() {
        GhostConfig.load();
        getNotificationCenter().addObserver(this, NotificationCenter.ghostArchiveUpdated);
        getNotificationCenter().addObserver(this, NotificationCenter.ghostOnlineTracked);
        updateRows();
        loadCounts();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.ghostArchiveUpdated);
        getNotificationCenter().removeObserver(this, NotificationCenter.ghostOnlineTracked);
        super.onFragmentDestroy();
    }

    private void loadCounts() {
        final int account = currentAccount;
        new Thread(() -> {
            final int[] result = GhostStore.getInstance(account).counts();
            AndroidUtilities.runOnUIThread(() -> {
                counts = result;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        }).start();
    }

    private void updateRows() {
        rowCount = 0;
        ghostHeaderRow = rowCount++;
        hideOnlineRow = rowCount++;
        hideTypingRow = rowCount++;
        hideReadRow = rowCount++;
        ghostInfoRow = rowCount++;
        trackHeaderRow = rowCount++;
        trackOnlineRow = rowCount++;
        saveDeletedRow = rowCount++;
        saveEditedRow = rowCount++;
        trackInfoRow = rowCount++;
        openOnlineRow = rowCount++;
        openArchiveRow = rowCount++;
        openInfoRow = rowCount++;
        dataHeaderRow = rowCount++;
        clearOnlineRow = rowCount++;
        clearArchiveRow = rowCount++;
        dataInfoRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(GhostStrings.get("Title"));
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
            if (position == hideOnlineRow) {
                GhostConfig.setHideOnline(!GhostConfig.hideOnline);
                ((TextCheckCell) view).setChecked(GhostConfig.hideOnline);
            } else if (position == hideTypingRow) {
                GhostConfig.setHideTyping(!GhostConfig.hideTyping);
                ((TextCheckCell) view).setChecked(GhostConfig.hideTyping);
            } else if (position == hideReadRow) {
                GhostConfig.setHideReadReceipts(!GhostConfig.hideReadReceipts);
                ((TextCheckCell) view).setChecked(GhostConfig.hideReadReceipts);
            } else if (position == trackOnlineRow) {
                GhostConfig.setTrackOnline(!GhostConfig.trackOnline);
                ((TextCheckCell) view).setChecked(GhostConfig.trackOnline);
            } else if (position == saveDeletedRow) {
                GhostConfig.setSaveDeleted(!GhostConfig.saveDeleted);
                ((TextCheckCell) view).setChecked(GhostConfig.saveDeleted);
            } else if (position == saveEditedRow) {
                GhostConfig.setSaveEdited(!GhostConfig.saveEdited);
                ((TextCheckCell) view).setChecked(GhostConfig.saveEdited);
            } else if (position == openOnlineRow) {
                presentFragment(new GhostOnlineActivity());
            } else if (position == openArchiveRow) {
                presentFragment(new GhostArchiveActivity());
            } else if (position == clearOnlineRow) {
                confirmClear(true);
            } else if (position == clearArchiveRow) {
                confirmClear(false);
            }
        });

        return fragmentView;
    }

    private void confirmClear(boolean online) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(GhostStrings.get(online ? "ClearOnline" : "ClearArchive"));
        builder.setMessage(GhostStrings.get("ClearConfirm"));
        builder.setPositiveButton(GhostStrings.get("Clear"), (dialog, which) -> {
            final int account = currentAccount;
            new Thread(() -> {
                if (online) {
                    GhostStore.getInstance(account).clearOnline();
                } else {
                    GhostStore.getInstance(account).clearArchive();
                }
                AndroidUtilities.runOnUIThread(() -> {
                    loadCounts();
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, GhostStrings.get("Cleared")).show();
                });
            }).start();
        });
        builder.setNegativeButton(GhostStrings.get("Cancel"), null);
        showDialog(builder.create());
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.ghostArchiveUpdated || id == NotificationCenter.ghostOnlineTracked) {
            loadCounts();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private static final int TYPE_HEADER = 0;
        private static final int TYPE_CHECK = 1;
        private static final int TYPE_INFO = 2;
        private static final int TYPE_SETTINGS = 3;

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == TYPE_CHECK || type == TYPE_SETTINGS;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            switch (viewType) {
                case TYPE_HEADER:
                    view = new HeaderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_CHECK:
                    view = new TextCheckCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_SETTINGS:
                    view = new TextSettingsCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                default:
                    view = new TextInfoPrivacyCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == ghostHeaderRow) {
                        cell.setText(GhostStrings.get("GhostHeader"));
                    } else if (position == trackHeaderRow) {
                        cell.setText(GhostStrings.get("TrackingHeader"));
                    } else if (position == dataHeaderRow) {
                        cell.setText(GhostStrings.get("DataHeader"));
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == hideOnlineRow) {
                        cell.setTextAndCheck(GhostStrings.get("HideOnline"), GhostConfig.hideOnline, true);
                    } else if (position == hideTypingRow) {
                        cell.setTextAndCheck(GhostStrings.get("HideTyping"), GhostConfig.hideTyping, true);
                    } else if (position == hideReadRow) {
                        cell.setTextAndCheck(GhostStrings.get("HideRead"), GhostConfig.hideReadReceipts, false);
                    } else if (position == trackOnlineRow) {
                        cell.setTextAndCheck(GhostStrings.get("TrackOnline"), GhostConfig.trackOnline, true);
                    } else if (position == saveDeletedRow) {
                        cell.setTextAndCheck(GhostStrings.get("SaveDeleted"), GhostConfig.saveDeleted, true);
                    } else if (position == saveEditedRow) {
                        cell.setTextAndCheck(GhostStrings.get("SaveEdited"), GhostConfig.saveEdited, false);
                    }
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == openOnlineRow) {
                        cell.setTextAndValue(GhostStrings.get("OpenOnline"),
                                GhostStrings.format("PeopleTracked", LocaleController.formatShortNumber(counts[0], null)), true);
                    } else if (position == openArchiveRow) {
                        cell.setTextAndValue(GhostStrings.get("OpenArchive"),
                                GhostStrings.format("DeletedCount", LocaleController.formatShortNumber(counts[1], null))
                                        + " · " + GhostStrings.format("EditedCount", LocaleController.formatShortNumber(counts[2], null)), false);
                    } else if (position == clearOnlineRow) {
                        cell.setText(GhostStrings.get("ClearOnline"), true);
                    } else if (position == clearArchiveRow) {
                        cell.setText(GhostStrings.get("ClearArchive"), false);
                    }
                    break;
                }
                default: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == ghostInfoRow) {
                        cell.setText(GhostStrings.get("GhostInfo"));
                    } else if (position == trackInfoRow) {
                        cell.setText(GhostStrings.get("SaveInfo"));
                    } else if (position == openInfoRow) {
                        cell.setText(GhostStrings.get("TrackOnlineInfo"));
                    } else if (position == dataInfoRow) {
                        cell.setText(GhostStrings.get("DataInfo"));
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ghostHeaderRow || position == trackHeaderRow || position == dataHeaderRow) {
                return TYPE_HEADER;
            } else if (position == hideOnlineRow || position == hideTypingRow || position == hideReadRow
                    || position == trackOnlineRow || position == saveDeletedRow || position == saveEditedRow) {
                return TYPE_CHECK;
            } else if (position == openOnlineRow || position == openArchiveRow
                    || position == clearOnlineRow || position == clearArchiveRow) {
                return TYPE_SETTINGS;
            }
            return TYPE_INFO;
        }

        @Override
        public int getItemCount() {
            return rowCount;
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
