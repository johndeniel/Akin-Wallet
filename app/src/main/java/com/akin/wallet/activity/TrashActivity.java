package com.akin.wallet.activity;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.adapter.TrashAdapter;
import com.akin.wallet.db.AppDatabaseHelper;
import com.akin.wallet.db.VaultWarmCache;
import com.akin.wallet.model.BankCardModel;
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.util.Ui;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

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
public class TrashActivity extends AppCompatActivity {

    private AppDatabaseHelper dbHelper;
    private MaterialToolbar toolbar;
    private RecyclerView recyclerTrash;
    private TrashAdapter trashAdapter;
    private View emptyTrash;
    private View bottomActionBar;
    private MaterialButton btnBulkRestore;
    private MaterialButton btnBulkDelete;
    private AlertDialog bulkDeleteDialog;

    private static final String KEY_SELECTION = "trash_selection";

    /** Drops stale loads (rotation / rapid resume) — only the latest binds. */
    private int loadGeneration;
    /** Selection restored once after the first post-rotation load. */
    private ArrayList<String> pendingSelection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trash);
        Ui.applySystemBars(this);

        dbHelper = VaultWarmCache.get(this).helper();

        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> onNavigationBack());
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

        recyclerTrash = findViewById(R.id.recycler_trash);
        recyclerTrash.setLayoutManager(createGridLayoutManager());
        trashAdapter = new TrashAdapter();
        trashAdapter.setOnSelectionChangedListener(this::updateChrome);
        recyclerTrash.setAdapter(trashAdapter);

        emptyTrash = findViewById(R.id.empty_trash);
        bottomActionBar = findViewById(R.id.action_bar);
        btnBulkRestore = findViewById(R.id.btn_bulk_restore);
        btnBulkDelete = findViewById(R.id.btn_bulk_delete);
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

    @Override
    protected void onDestroy() {
        Ui.dismissOwnedDialog(bulkDeleteDialog);
        bulkDeleteDialog = null;
        // Vault I/O runs on the shared funnel (never shut down per-screen).
        // Shared helper lives with the process (VaultWarmCache); never close per-screen.
        dbHelper = null;
        super.onDestroy();
    }

    /** Back/close: clears an active selection first, finishes otherwise. */
    private void onNavigationBack() {
        if (trashAdapter != null && trashAdapter.getSelectedCount() > 0) {
            trashAdapter.clearSelection();
        } else {
            finish();
        }
    }

    private void loadTrash() {
        final AppDatabaseHelper db = dbHelper;
        if (db == null) {
            return;
        }
        final int generation = ++loadGeneration;
        VaultWarmCache.get(this).executeVaultIo(() -> {
            final List<GovernmentIDModel> ids;
            final List<BankCardModel> cards;
            final List<SocialAccountModel> accounts;
            try {
                ids = db.getTrashedIdCards();
                cards = db.getTrashedBankCards();
                accounts = db.getTrashedSocialAccounts();
            } catch (RuntimeException e) {
                runOnUiThread(() -> {
                    if (generation != loadGeneration || isFinishing()) {
                        return;
                    }
                    Snackbar.make(findViewById(android.R.id.content),
                            R.string.err_trash_load, Snackbar.LENGTH_SHORT).show();
                });
                return;
            }
            VaultWarmCache.get(TrashActivity.this).publishTrash(ids, cards, accounts);
            runOnUiThread(() -> {
                if (generation != loadGeneration || isFinishing()) {
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
        VaultWarmCache.Snapshot cached = VaultWarmCache.get(this).snapshot();
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
        final AppDatabaseHelper db = dbHelper;
        if (selected.isEmpty() || db == null) {
            return;
        }
        List<Integer> ids = new ArrayList<>();
        List<Integer> cards = new ArrayList<>();
        List<Integer> socials = new ArrayList<>();
        splitSelection(selected, ids, cards, socials);
        final int count = selected.size();
        VaultWarmCache.get(this).executeVaultIo(() -> {
            db.restoreIdCards(ids);
            db.restoreBankCards(cards);
            db.restoreSocialAccounts(socials);
            VaultWarmCache.get(TrashActivity.this).invalidate();
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                loadTrash();
                Snackbar.make(findViewById(android.R.id.content),
                        getString(R.string.trash_restored_count, count),
                        Snackbar.LENGTH_SHORT).show();
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
        final AppDatabaseHelper db = dbHelper;
        if (selected.isEmpty() || db == null) {
            return;
        }
        // Snapshot: deletes mutate the backing rows while the adapter
        // still holds them; keyed ids survive the reload either way.
        List<Integer> ids = new ArrayList<>();
        List<Integer> cards = new ArrayList<>();
        List<Integer> socials = new ArrayList<>();
        splitSelection(selected, ids, cards, socials);
        final int count = selected.size();
        bulkDeleteDialog = Ui.confirmDelete(this,
                getString(R.string.trash_delete_forever),
                getString(R.string.trash_delete_many, count),
                () -> VaultWarmCache.get(this).executeVaultIo(() -> {
                    db.deleteIdCards(ids);
                    db.deleteBankCards(cards);
                    db.deleteSocialAccounts(socials);
                    VaultWarmCache.get(TrashActivity.this).invalidate();
                    runOnUiThread(() -> {
                        if (isFinishing()) {
                            return;
                        }
                        loadTrash();
                        Snackbar.make(findViewById(android.R.id.content),
                                getString(R.string.trash_deleted_count, count),
                                Snackbar.LENGTH_SHORT).show();
                    });
                }));
    }
}
