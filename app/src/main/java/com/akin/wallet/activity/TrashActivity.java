package com.akin.wallet.activity;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.adapter.TrashAdapter;
import com.akin.wallet.model.BankCardModel;
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.model.GovernmentIDModel;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Trash — restorable soft-deletes as selectable tiles. Deleting a Government
 * ID, Bank Card or Social Account stamps deleted_at (dashboard hides it);
 * this screen groups the trashed rows: one uniform set of tiles (centered
 * icon + title + masked hint, paired two-per-row). Tapping a tile toggles
 * its selection; the toolbar turns contextual (count + select-all) and the
 * bottom bar restores or permanently deletes the selection in bulk.
 */
public class TrashActivity extends BaseVaultActivity {

    private MaterialToolbar toolbar;
    private RecyclerView recyclerTrash;
    private TrashAdapter trashAdapter;
    private View emptyTrash;
    private View bottomActionBar;
    private MaterialButton btnBulkRestore;
    private MaterialButton btnBulkDelete;

    private static final String KEY_SELECTION = "trash_selection";

    /** Selection restored once after the first post-rotation load. */
    private ArrayList<String> pendingSelection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trash);
        applyChrome();

        toolbar = findViewById(R.id.trash_toolbar);
        // Select All lives in code, not a menu XML: single always-shown item,
        // hidden until a selection starts (see updateChrome).
        MenuItem selectAllItem = toolbar.getMenu().add(Menu.NONE, R.id.action_select_all,
                Menu.NONE, R.string.trash_select_all);
        selectAllItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        selectAllItem.setVisible(false);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_select_all) {
                trashAdapter.selectAll();
                return true;
            }
            return false;
        });

        recyclerTrash = findViewById(R.id.trash_list);
        recyclerTrash.setLayoutManager(createGridLayoutManager());
        trashAdapter = new TrashAdapter();
        trashAdapter.setOnSelectionChangedListener(this::updateChrome);
        recyclerTrash.setAdapter(trashAdapter);
        recyclerTrash.setHasFixedSize(true);
        recyclerTrash.setItemViewCacheSize(6);

        emptyTrash = findViewById(R.id.trash_empty_state);
        bottomActionBar = findViewById(R.id.trash_bulk_action_bar);
        btnBulkRestore = findViewById(R.id.trash_bulk_restore_button);
        btnBulkDelete = findViewById(R.id.trash_bulk_delete_button);
        btnBulkRestore.setOnClickListener(v -> bulkRestore());
        btnBulkDelete.setOnClickListener(v -> confirmBulkDelete());

        // Idle chrome before the first load lands: without this, Select All
        // stays at its inflated visibility until bindTrash runs updateChrome.
        updateChrome(0, 0);

        if (savedInstanceState != null) {
            pendingSelection = savedInstanceState.getStringArrayList(KEY_SELECTION);
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

    /** Two-per-row tiles; headers span the full row. */
    private GridLayoutManager createGridLayoutManager() {
        GridLayoutManager grid = new GridLayoutManager(this, 2);
        // Uniform tiles pair up; headers take the full row. Bounds-guarded:
        // layout can probe positions mid-animation that no longer exist.
        grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (trashAdapter == null
                        || position < 0 || position >= trashAdapter.getItemCount()) {
                    return 2;
                }
                return trashAdapter.getItemViewType(position)
                        == TrashAdapter.TYPE_TILE ? 1 : 2;
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
                runOnUiThread(() -> {
                    if (!isCurrentGeneration(generation) || isFinishing()) {
                        return;
                    }
                    showError(R.string.err_trash_load);
                });
                return;
            }
            cache().publishTrash(ids, cards, accounts);
            runOnUiThread(() -> {
                if (!isCurrentGeneration(generation) || isFinishing()) {
                    return;
                }
                bindTrash(ids, cards, accounts);
            });
        });
    }

    /**
     * Cache-first bind: when the post-auth preload already ran, tiles render
     * synchronously before first draw. Falls back to async load on miss;
     * {@link #loadTrash()} in {@code onResume} always revalidates afterward.
     */
    private void bindCachedSnapshot() {
        com.akin.wallet.db.VaultWarmCache.Snapshot cached = cache().snapshot();
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
        if (ids != null && !ids.isEmpty()) {
            entries.add(TrashAdapter.Entry.header(
                    getString(R.string.trash_section_ids), ids.size()));
            for (GovernmentIDModel item : ids) {
                entries.add(TrashAdapter.Entry.id(item));
            }
        }
        if (cards != null && !cards.isEmpty()) {
            entries.add(TrashAdapter.Entry.header(
                    getString(R.string.trash_section_cards), cards.size()));
            for (BankCardModel item : cards) {
                entries.add(TrashAdapter.Entry.card(item));
            }
        }
        if (accounts != null && !accounts.isEmpty()) {
            entries.add(TrashAdapter.Entry.header(
                    getString(R.string.trash_section_social), accounts.size()));
            for (SocialAccountModel item : accounts) {
                entries.add(TrashAdapter.Entry.social(item));
            }
        }

        trashAdapter.updateData(entries);
        if (pendingSelection != null) {
            trashAdapter.restoreSelection(pendingSelection);
            pendingSelection = null;
        }
        boolean allEmpty = entries.isEmpty();
        emptyTrash.setVisibility(allEmpty ? View.VISIBLE : View.GONE);
        recyclerTrash.setVisibility(allEmpty ? View.GONE : View.VISIBLE);
        updateChrome(trashAdapter.getSelectedCount(), trashAdapter.getSelectableCount());
    }

    /**
     * Contextual chrome: idle shows "Trash" + back; selecting shows the
     * count + close, the select-all overflow and the bulk action bar.
     */
    private void updateChrome(int selected, int total) {
        boolean selecting = selected > 0;
        toolbar.setTitle(selecting
                ? getString(R.string.trash_selected, selected)
                : getString(R.string.trash_title));
        toolbar.setNavigationIcon(selecting
                ? R.drawable.ic_close : R.drawable.ic_arrow_back);
        if (toolbar.getMenu() != null) {
            MenuItem selectAll = toolbar.getMenu().findItem(R.id.action_select_all);
            if (selectAll != null) {
                selectAll.setVisible(selecting && selected < total);
            }
        }
        bottomActionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (selecting) {
            btnBulkRestore.setText(getString(R.string.trash_restore_count, selected));
            btnBulkDelete.setText(getString(R.string.trash_delete_count, selected));
        }
    }

    /** Restores every selected item to its vault, then reloads. */
    private void bulkRestore() {
        List<TrashAdapter.Entry> selected = trashAdapter.selectedEntries();
        if (selected.isEmpty()) {
            return;
        }
        List<Integer> ids = new ArrayList<>();
        List<Integer> cards = new ArrayList<>();
        List<Integer> socials = new ArrayList<>();
        splitSelection(selected, ids, cards, socials);
        final int count = selected.size();
        vaultIo(() -> {
            db().restoreIdCards(ids);
            db().restoreBankCards(cards);
            db().restoreSocialAccounts(socials);
            cache().invalidate();
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                loadTrash();
                showMessage(getString(R.string.trash_restored_count, count));
            });
        });
    }

    /** Groups selected entries into per-table id lists for batch writes. */
    private static void splitSelection(List<TrashAdapter.Entry> selected,
                                       List<Integer> ids, List<Integer> cards,
                                       List<Integer> socials) {
        for (TrashAdapter.Entry entry : selected) {
            if (entry.kind == TrashAdapter.KIND_ID && entry.idCard != null) {
                ids.add(entry.idCard.getId());
            } else if (entry.kind == TrashAdapter.KIND_CARD && entry.bankCard != null) {
                cards.add(entry.bankCard.getId());
            } else if (entry.kind == TrashAdapter.KIND_SOCIAL && entry.socialAccount != null) {
                socials.add(entry.socialAccount.getId());
            }
        }
    }

    /** Confirms, then permanently deletes every selected item. */
    private void confirmBulkDelete() {
        List<TrashAdapter.Entry> selected = trashAdapter.selectedEntries();
        if (selected.isEmpty()) {
            return;
        }
        // Snapshot: deletes mutate the backing rows while the adapter
        // still holds them; keyed ids survive the reload either way.
        List<Integer> ids = new ArrayList<>();
        List<Integer> cards = new ArrayList<>();
        List<Integer> socials = new ArrayList<>();
        splitSelection(selected, ids, cards, socials);
        final int count = selected.size();
        confirmDeleteToTrash(
                getString(R.string.trash_delete_forever),
                getString(R.string.trash_delete_many, count),
                () -> vaultIo(() -> {
                    db().deleteIdCards(ids);
                    db().deleteBankCards(cards);
                    db().deleteSocialAccounts(socials);
                    cache().invalidate();
                    runOnUiThread(() -> {
                        if (isFinishing()) {
                            return;
                        }
                        loadTrash();
                        showMessage(getString(R.string.trash_deleted_count, count));
                    });
                }));
    }
}
