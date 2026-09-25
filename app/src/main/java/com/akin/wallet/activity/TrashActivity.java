package com.akin.wallet.activity;

import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.adapter.TrashAdapter;
import com.akin.wallet.db.VaultWarmCache;
import com.akin.wallet.model.BankCardModel;
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.model.GovernmentIDModel;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Trash — restorable soft-deletes as selectable tiles. Tap toggles selection;
 * toolbar turns contextual, bottom bar restores or deletes in bulk.
 */
public class TrashActivity extends BaseVaultActivity {

    private MaterialToolbar toolbar;
    private RecyclerView trashGrid;
    private TrashAdapter trashAdapter;
    private View trashEmptyState;
    private View bulkActionBar;
    private MaterialButton restoreSelectedButton;
    private MaterialButton deleteForeverButton;

    private static final String KEY_SELECTION = "trash_selection";
    private static final String KEY_BULK_CONFIRM = "trash_bulk_confirm";

    /** Selection restored once after the first post-rotation load. */
    private ArrayList<String> pendingSelection;
    /** Bulk-delete confirm re-shown after rotation if open when destroyed. */
    private boolean pendingBulkConfirm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trash);
        applyChrome();

        toolbar = findViewById(R.id.toolbar);
        MenuItem selectAllItem = toolbar.getMenu().add(Menu.NONE, R.id.trash_select_all_action,
                Menu.NONE, R.string.trash_select_all);
        selectAllItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        selectAllItem.setVisible(false);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.trash_select_all_action) {
                trashAdapter.selectAll();
                return true;
            }
            return false;
        });

        trashGrid = findViewById(R.id.trash_grid);
        trashGrid.setLayoutManager(createGridLayoutManager());
        trashAdapter = new TrashAdapter();
        trashAdapter.setOnSelectionChangedListener(this::updateChrome);
        trashGrid.setAdapter(trashAdapter);
        trashGrid.setHasFixedSize(true);
        trashGrid.setItemViewCacheSize(6);

        trashEmptyState = findViewById(R.id.trash_empty_state);
        bulkActionBar = findViewById(R.id.trash_bulk_action_bar);
        restoreSelectedButton = findViewById(R.id.trash_restore_selected_button);
        deleteForeverButton = findViewById(R.id.trash_delete_forever_button);
        restoreSelectedButton.setOnClickListener(v -> bulkRestore());
        deleteForeverButton.setOnClickListener(v -> confirmBulkDelete());

        updateChrome(0, 0);

        if (savedInstanceState != null) {
            pendingSelection = savedInstanceState.getStringArrayList(KEY_SELECTION);
            pendingBulkConfirm = savedInstanceState.getBoolean(KEY_BULK_CONFIRM, false);
        }
        bindCachedSnapshot();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (trashAdapter.getSelectedCount() > 0) {
                    trashAdapter.clearSelection();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    /** Tiles pair two-per-row on phones; three on sw>=600dp. */
    private GridLayoutManager createGridLayoutManager() {
        final int spanCount = getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 3 : 2;
        GridLayoutManager grid = new GridLayoutManager(this, spanCount);
        grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                int full = grid.getSpanCount();
                if (position < 0 || position >= trashAdapter.getItemCount()) {
                    return full;
                }
                return trashAdapter.getItemViewType(position)
                        == TrashAdapter.TYPE_TILE ? 1 : full;
            }
        });
        return grid;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (trashAdapter != null) {
            outState.putStringArrayList(KEY_SELECTION, trashAdapter.saveSelection());
        }
        outState.putBoolean(KEY_BULK_CONFIRM, pendingBulkConfirm);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTrash();
    }

    /** Back/close: clears an active selection first, finishes otherwise. */
    @Override
    protected void onNavigateBack() {
        if (trashAdapter != null && trashAdapter.getSelectedCount() > 0) {
            trashAdapter.clearSelection();
        } else {
            finish();
        }
    }

    private void loadTrash() {
        final int generation = nextLoadGeneration();
        vaultIo(() -> {
            final List<GovernmentIDModel> ids;
            final List<BankCardModel> cards;
            final List<SocialAccountModel> accounts;
            try {
                ids = db().getTrashedIdCards();
                cards = db().getTrashedBankCards();
                accounts = db().getTrashedSocialAccounts();
            } catch (RuntimeException e) {
                Log.w("Trash", "load failed", e);
                runIfAlive(generation, () -> showMessage(R.string.err_trash_load));
                return;
            }
            cache().publishTrash(ids, cards, accounts);
            runIfAlive(generation, () -> bindTrash(ids, cards, accounts));
        });
    }

    /** Cache-first bind: preload snapshot before first draw; onResume revalidates. */
    private void bindCachedSnapshot() {
        VaultWarmCache.Snapshot cached = cache().snapshot();
        if (cached == null || !cached.hasTrash() || trashAdapter == null) {
            return;
        }
        bindTrash(new ArrayList<>(cached.trashedIds),
                new ArrayList<>(cached.trashedCards),
                new ArrayList<>(cached.trashedAccounts));
    }

    /** Binds one loaded snapshot on the UI thread (adapter + chrome). */
    private void bindTrash(List<GovernmentIDModel> ids, List<BankCardModel> cards,
                           List<SocialAccountModel> accounts) {
        List<TrashAdapter.Entry> entries = new ArrayList<>();
        addIdSection(entries, ids);
        addCardSection(entries, cards);
        addSocialSection(entries, accounts);

        trashAdapter.updateData(entries);
        if (pendingSelection != null) {
            trashAdapter.restoreSelection(pendingSelection);
            pendingSelection = null;
        }
        boolean allEmpty = entries.isEmpty();
        trashEmptyState.setVisibility(allEmpty ? View.VISIBLE : View.GONE);
        trashGrid.setVisibility(allEmpty ? View.GONE : View.VISIBLE);
        updateChrome(trashAdapter.getSelectedCount(), trashAdapter.getSelectableCount());
        if (pendingBulkConfirm && trashAdapter.getSelectedCount() > 0) {
            trashGrid.post(this::confirmBulkDelete);
        }
        pendingBulkConfirm = false;
    }

    /** Contextual chrome: idle shows Trash + back; selecting shows count + actions. */
    private void updateChrome(int selected, int total) {
        boolean selecting = selected > 0;
        toolbar.setTitle(selecting
                ? getString(R.string.trash_selected, selected)
                : getString(R.string.trash_title));
        toolbar.setNavigationIcon(selecting
                ? R.drawable.ic_close : R.drawable.ic_arrow_back);
        MenuItem selectAll = toolbar.getMenu().findItem(R.id.trash_select_all_action);
        selectAll.setVisible(selecting && selected < total);
        bulkActionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (selecting) {
            restoreSelectedButton.setText(getString(R.string.trash_restore_count, selected));
            deleteForeverButton.setText(getString(R.string.trash_delete_count, selected));
        }
    }

    private void addIdSection(List<TrashAdapter.Entry> entries, List<GovernmentIDModel> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        entries.add(TrashAdapter.Entry.header(getString(R.string.trash_section_ids), items.size()));
        for (GovernmentIDModel item : items) {
            entries.add(TrashAdapter.Entry.id(item));
        }
    }

    private void addCardSection(List<TrashAdapter.Entry> entries, List<BankCardModel> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        entries.add(TrashAdapter.Entry.header(getString(R.string.trash_section_cards), cards.size()));
        for (BankCardModel item : cards) {
            entries.add(TrashAdapter.Entry.card(item));
        }
    }

    private void addSocialSection(List<TrashAdapter.Entry> entries, List<SocialAccountModel> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return;
        }
        entries.add(TrashAdapter.Entry.header(getString(R.string.trash_section_social), accounts.size()));
        for (SocialAccountModel item : accounts) {
            entries.add(TrashAdapter.Entry.social(item));
        }
    }

    private static final class Selection {
        final List<Integer> ids = new ArrayList<>();
        final List<Integer> cards = new ArrayList<>();
        final List<Integer> socials = new ArrayList<>();
        final int count;
        Selection(List<TrashAdapter.Entry> selected) {
            for (TrashAdapter.Entry entry : selected) {
                switch (entry.kind) {
                    case TrashAdapter.KIND_ID:
                        if (entry.idCard != null) {
                            ids.add(entry.idCard.getId());
                        }
                        break;
                    case TrashAdapter.KIND_CARD:
                        if (entry.bankCard != null) {
                            cards.add(entry.bankCard.getId());
                        }
                        break;
                    case TrashAdapter.KIND_SOCIAL:
                        if (entry.socialAccount != null) {
                            socials.add(entry.socialAccount.getId());
                        }
                        break;
                    default:
                        break;
                }
            }
            count = selected.size();
        }
    }

    private void runBulkWrite(Selection selection, Runnable write, int doneMessage) {
        vaultIo(() -> {
            write.run();
            cache().invalidate();
            runIfAlive(() -> {
                loadTrash();
                showMessage(doneMessage, selection.count);
            });
        });
    }

    /** Restores every selected item to its vault, then reloads. */
    private void bulkRestore() {
        List<TrashAdapter.Entry> selected = trashAdapter.selectedEntries();
        if (selected.isEmpty()) {
            return;
        }
        Selection selection = new Selection(selected);
        runBulkWrite(selection, () -> {
            db().restoreIdCards(selection.ids);
            db().restoreBankCards(selection.cards);
            db().restoreSocialAccounts(selection.socials);
        }, R.string.trash_restored_count);
    }

    /** Confirms, then permanently deletes every selected item. */
    private void confirmBulkDelete() {
        List<TrashAdapter.Entry> selected = trashAdapter.selectedEntries();
        if (selected.isEmpty()) {
            return;
        }
        Selection selection = new Selection(selected);
        pendingBulkConfirm = true;
        AlertDialog dialog = confirmDeleteToTrash(
                getString(R.string.trash_delete_forever),
                getString(R.string.trash_delete_many, selection.count),
                () -> {
                    pendingBulkConfirm = false;
                    runBulkWrite(selection, () -> {
                        db().deleteIdCards(selection.ids);
                        db().deleteBankCards(selection.cards);
                        db().deleteSocialAccounts(selection.socials);
                    }, R.string.trash_deleted_count);
                });
        dialog.setOnDismissListener(d -> pendingBulkConfirm = false);
    }
}
