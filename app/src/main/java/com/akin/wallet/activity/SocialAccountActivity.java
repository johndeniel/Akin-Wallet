package com.akin.wallet.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;


import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.adapter.AssociatedAccountAdapter;
import com.akin.wallet.adapter.SocialPlatformAdapter;
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.model.SocialPlatformModel;
import com.akin.wallet.util.Ui;
import com.google.android.material.search.SearchView;

import java.util.ArrayList;
import java.util.List;

/**
 * Social account creation/edit screen as a full screen. Add mode when no account
 * id is passed; edit mode otherwise. The platform and link pickers open as
 * full-screen activities. Callers refresh in onResume; RESULT_OK is set on
 * successful save.
 */
public class SocialAccountActivity extends BaseVaultActivity {

    public static final String EXTRA_ACCOUNT_ID = "extra_account_id";

    /** Default pick for a fresh form (also the icon fallback for stored rows). */
    public static final String DEFAULT_PLATFORM_NAME = "Google";

    /**
     * Intent that opens this screen to edit an existing credential. Carries the
     * row id only — the screen re-queries the vault, so secrets never travel
     * as Intent extras (recents/dumps) and edits always start current.
     */
    public static Intent editIntent(@NonNull Context context,
                                    @NonNull SocialAccountModel item) {
        return new Intent(context, SocialAccountActivity.class)
                .putExtra(EXTRA_ACCOUNT_ID, (long) item.getId());
    }

    private int selectedIcon = SocialPlatformModel.iconFor(DEFAULT_PLATFORM_NAME);
    private String selectedName = DEFAULT_PLATFORM_NAME;
    private ImageView platformIcon;
    private TextView platformName;
    private View linkedCard;
    private List<SocialAccountModel> linkPool = new ArrayList<>();
    private List<SocialAccountModel> linkedItems = new ArrayList<>();
    private AssociatedAccountAdapter linkedAdapter;
    private int linkSelfId = -1;

    // In-place M3 platform picker: full-screen SearchView reusing the old
    // picker adapter. Tapping the platform card opens it; tapping a row
    // picks the platform and closes it (no separate activity).
    private static final String KEY_PLATFORM_QUERY = "platform_search_query";
    private static final String KEY_PLATFORM_OPEN = "platform_search_open";
    private static final String KEY_SELECTED_ICON = "selected_icon";
    private static final String KEY_SELECTED_NAME = "selected_name";
    private static final String KEY_LINKED_IDS = "linked_ids";
    private SearchView platformSearchView;
    private SocialPlatformAdapter platformAdapter;
    private RecyclerView recyclerPlatformSearch;
    private View emptyPlatformResults;
    private String platformQuery = "";
    private boolean platformSearchOpen = false;

    // In-place M3 link picker: full-screen SearchView reusing the linked
    // account adapter in pick mode. The Add action opens it with the
    // currently-linkable accounts; tapping a row links it and closes it
    // (no separate activity).
    private static final String KEY_LINK_QUERY = "link_search_query";
    private static final String KEY_LINK_OPEN = "link_search_open";
    private SearchView linkSearchView;
    private AssociatedAccountAdapter linkSearchAdapter;
    private RecyclerView recyclerLinkSearch;
    private View emptyLinkResults;
    private String linkQuery = "";
    private boolean linkSearchOpen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_social_account);
        applyChrome();

        setupPlatformSearch(savedInstanceState);

        long id = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, -1);
        final int gen = nextLoadGeneration();
        vaultIo(() -> {
            final SocialAccountModel item;
            final List<SocialAccountModel> pool;
            final List<Integer> linkedIds;
            if (id == -1) {
                item = null;
                linkedIds = null;
            } else {
                item = db().getSocialAccountById((int) id);
                linkedIds = item != null ? db().getLinkedAccountIds(item.getId()) : null;
            }
            com.akin.wallet.db.VaultWarmCache.Snapshot snap = cache().snapshot();
            if (snap != null && snap.activeAccounts != null) {
                pool = new ArrayList<>(snap.activeAccounts);
            } else {
                pool = db().getAllSocialAccounts();
            }
            runOnUiThread(() -> {
                if (!isCurrentGeneration(gen) || isFinishing()) return;
                if (id != -1 && item == null) {
                    finish();
                    return;
                }
                if (id == -1) {
                    bindAddForm(pool);
                } else {
                    bindEditForm(item, pool, linkedIds);
                }
                if (savedInstanceState != null) {
                    restoreTransientState(savedInstanceState);
                }
                setupLinkSearch(savedInstanceState);
            });
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {        super.onSaveInstanceState(outState);
        outState.putString(KEY_PLATFORM_QUERY, platformQuery);
        outState.putBoolean(KEY_PLATFORM_OPEN, platformSearchOpen);
        outState.putString(KEY_LINK_QUERY, linkQuery);
        outState.putBoolean(KEY_LINK_OPEN, linkSearchOpen);
        // EditTexts restore their own text; the picker + links do not.
        outState.putInt(KEY_SELECTED_ICON, selectedIcon);
        outState.putString(KEY_SELECTED_NAME, selectedName);
        if (linkedItems != null && !linkedItems.isEmpty()) {
            int[] ids = new int[linkedItems.size()];
            for (int i = 0; i < linkedItems.size(); i++) {
                ids[i] = linkedItems.get(i).getId();
            }
            outState.putIntArray(KEY_LINKED_IDS, ids);
        }
    }

    /** Re-applies picker + link picks that views cannot restore themselves. */
    private void restoreTransientState(@NonNull Bundle savedInstanceState) {
        selectedIcon = savedInstanceState.getInt(KEY_SELECTED_ICON, selectedIcon);
        String name = savedInstanceState.getString(KEY_SELECTED_NAME, null);
        if (name != null) {
            selectedName = name;
        }
        if (platformIcon != null) {
            SocialPlatformModel.bindIcon(platformIcon, selectedName, selectedIcon);
        }
        if (platformName != null) {
            platformName.setText(selectedName);
        }
        int[] ids = savedInstanceState.getIntArray(KEY_LINKED_IDS);
        if (ids != null && ids.length > 0 && linkedAdapter != null) {
            // O(n) restore via id map (was O(n*m) nested scan).
            java.util.HashMap<Integer, SocialAccountModel> byId = new java.util.HashMap<>(linkPool.size() * 2);
            for (SocialAccountModel candidate : linkPool) {
                byId.put(candidate.getId(), candidate);
            }
            linkedItems.clear();
            for (int linkId : ids) {
                SocialAccountModel hit = byId.get(linkId);
                if (hit != null) {
                    linkedItems.add(hit);
                }
            }
            linkedAdapter.onExternalRestore(linkedItems);
            refreshLinkedVisibility();
        }
    }

    /**
     * In-place platform picker: the shared catalog adapter rebound inside the
     * form's SearchView. Row taps apply the platform directly (same fields
     * the old picker activity returned); back closes search first.
     */
    private void setupPlatformSearch(Bundle savedInstanceState) {
        platformSearchView = findViewById(R.id.platform_search_view);
        if (platformSearchView == null) {
            return;
        }
        recyclerPlatformSearch = findViewById(R.id.recycler_platform_search);
        emptyPlatformResults = findViewById(R.id.empty_platform_results);

        platformAdapter = new SocialPlatformAdapter(cache().catalog(),
                (iconRes, name, url) -> {
                    selectedIcon = iconRes;
                    selectedName = name;
                    if (platformIcon != null) {
                        SocialPlatformModel.bindIcon(platformIcon, selectedName, selectedIcon);
                    }
                    if (platformName != null) {
                        platformName.setText(selectedName);
                    }
                    platformSearchView.hide();
                });
        recyclerPlatformSearch.setLayoutManager(new LinearLayoutManager(this));
        recyclerPlatformSearch.setAdapter(platformAdapter);
        // Empty card mirrors the filter count (register before restoring).
        platformAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                boolean empty = platformAdapter.getItemCount() == 0;
                if (emptyPlatformResults != null) {
                    emptyPlatformResults.setVisibility(empty ? View.VISIBLE : View.GONE);
                }
                if (recyclerPlatformSearch != null) {
                    recyclerPlatformSearch.setVisibility(empty ? View.GONE : View.VISIBLE);
                }
            }
        });

        platformSearchView.getEditText().addTextChangedListener(new Ui.SimpleTextWatcher() {
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                platformQuery = text != null ? text.toString() : "";
                platformAdapter.getFilter().filter(text);
            }
        });
        platformSearchView.addTransitionListener((view, oldState, newState) ->
                platformSearchOpen = newState == SearchView.TransitionState.SHOWN);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (linkSearchOpen && linkSearchView != null) {
                    linkSearchView.hide();
                } else if (platformSearchOpen && platformSearchView != null) {
                    platformSearchView.hide();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (savedInstanceState != null) {
            platformQuery = savedInstanceState.getString(KEY_PLATFORM_QUERY, "");
            if (savedInstanceState.getBoolean(KEY_PLATFORM_OPEN, false)) {
                if (!platformQuery.isEmpty()) {
                    platformSearchView.getEditText().setText(platformQuery);
                }
                platformSearchView.post(() -> platformSearchView.show());
            }
        }
    }

    /** Opens the in-place platform SearchView (replaces the picker activity). */
    private void openPlatformSearch() {
        if (platformSearchView != null) {
            platformSearchView.show();
        }
    }

    /**
     * In-place link picker: rebuilt from the live pool on every open so it
     * can never offer the edited account itself or an already-linked one
     * (same exclusion the old picker activity received as ID extras).
     */
    private void setupLinkSearch(Bundle savedInstanceState) {
        linkSearchView = findViewById(R.id.link_search_view);
        if (linkSearchView == null) {
            return;
        }
        recyclerLinkSearch = findViewById(R.id.recycler_link_search);
        emptyLinkResults = findViewById(R.id.empty_link_results);
        recyclerLinkSearch.setLayoutManager(new LinearLayoutManager(this));

        linkSearchView.getEditText().addTextChangedListener(new Ui.SimpleTextWatcher() {
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                linkQuery = text != null ? text.toString() : "";
                if (linkSearchAdapter != null) {
                    linkSearchAdapter.getFilter().filter(text);
                }
            }
        });
        linkSearchView.addTransitionListener((view, oldState, newState) ->
                linkSearchOpen = newState == SearchView.TransitionState.SHOWN);

        if (savedInstanceState != null
                && savedInstanceState.getBoolean(KEY_LINK_OPEN, false)) {
            linkQuery = savedInstanceState.getString(KEY_LINK_QUERY, "");
            openLinkSearch();
            if (!linkQuery.isEmpty()) {
                linkSearchView.getEditText().setText(linkQuery);
            }
        }
    }

    /** Opens the in-place link SearchView (replaces the picker activity). */
    private void openLinkSearch() {
        if (linkSearchView == null || recyclerLinkSearch == null) {
            return;
        }
        // O(n) exclusion via set (was nested alreadyLinked scan).
        java.util.HashSet<Integer> linkedSet = new java.util.HashSet<>(linkedItems.size() * 2);
        for (SocialAccountModel linked : linkedItems) {
            linkedSet.add(linked.getId());
        }
        List<SocialAccountModel> available = new ArrayList<>();
        for (SocialAccountModel existing : linkPool) {
            if (existing.getId() == linkSelfId) {
                continue;
            }
            if (!linkedSet.contains(existing.getId())) {
                available.add(existing);
            }
        }

        if (available.isEmpty()) {
            showMessage("All accounts already linked");
            return;
        }

        linkSearchAdapter = new AssociatedAccountAdapter(available, false, (picked, removed) -> {
            boolean dup = false;
            for (SocialAccountModel alreadyLinked : linkedItems) {
                if (alreadyLinked.getId() == picked.getId()) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                linkedItems.add(picked);
                if (linkedAdapter != null) {
                    linkedAdapter.onExternalAdd(picked);
                    linkedAdapter.notifyItemInserted(linkedItems.size() - 1);
                }
                refreshLinkedVisibility();
            }
            linkSearchView.hide();
        });
        // [+] icon on each row mirrors the row tap: both link the account.
        linkSearchAdapter.setPickActionVisible(true);
        linkSearchAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                boolean empty = linkSearchAdapter.getItemCount() == 0;
                if (emptyLinkResults != null) {
                    emptyLinkResults.setVisibility(empty ? View.VISIBLE : View.GONE);
                }
                recyclerLinkSearch.setVisibility(empty ? View.GONE : View.VISIBLE);
            }
        });
        recyclerLinkSearch.setAdapter(linkSearchAdapter);
        // Fresh adapter holds the full list: reset any stale empty state and
        // query left over from the previous open before showing.
        if (emptyLinkResults != null) {
            emptyLinkResults.setVisibility(View.GONE);
        }
        recyclerLinkSearch.setVisibility(View.VISIBLE);
        linkSearchView.getEditText().setText("");
        linkSearchView.show();
    }

    private void bindAddForm(List<SocialAccountModel> pool) {
        selectedIcon = SocialPlatformModel.iconFor(DEFAULT_PLATFORM_NAME);
        selectedName = DEFAULT_PLATFORM_NAME;

        platformIcon = findViewById(R.id.platform_icon);
        platformName = findViewById(R.id.platform_name);

        SocialPlatformModel.bindIcon(platformIcon, selectedName, selectedIcon);
        platformName.setText(selectedName);

        findViewById(R.id.platform_selector).setOnClickListener(v -> openPlatformSearch());

        EditText inputPassword = findViewById(R.id.input_password);
        findViewById(R.id.btn_toggle_password).setOnClickListener(v ->
                Ui.togglePasswordVisibility(inputPassword));

        EditText inputPin = findViewById(R.id.input_pin);
        findViewById(R.id.btn_toggle_pin).setOnClickListener(v ->
                Ui.togglePasswordVisibility(inputPin));

        setupAssociateSection(pool, -1, java.util.Collections.emptyList());

        // Add mode keeps a single full-width Save button, same as the bank
        // screen: the delete view is GONE, so its row margin is dropped.
        View btnSaveAdd = findViewById(R.id.btn_save);
        Ui.makeSaveButtonFullWidth(btnSaveAdd);

        findViewById(R.id.btn_save).setOnClickListener(v -> {
            EditText inputUsername = findViewById(R.id.input_username);
            String username = inputUsername.getText().toString().trim();
            // Secrets are stored verbatim: trimming would silently mutate
            // credentials with significant leading/trailing spaces.
            String password = inputPassword.getText().toString();
            String pin = inputPin.getText().toString();

            if (validateUsername(inputUsername, username) != null) {
                return;
            }
            v.setEnabled(false);

            final List<Integer> linkedIds = collectLinkedIds();
            final SocialAccountModel fresh = new SocialAccountModel(selectedName, username,
                    password, pin, selectedIcon, 0, 0);
            vaultIo(() -> {
                long newId = db().saveSocialAccountWithLinks(fresh, linkedIds);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (newId < 0) {
                        v.setEnabled(true);
                        showSaveFailed();
                        return;
                    }
                    cache().invalidate();
                    setResult(RESULT_OK);
                    finish();
                    app().notifyOnReturn(R.string.msg_account_saved);
                });
            });
        });

    }


    /** Shared associate-accounts section for add (selfId -1) and edit modes. */
    private void setupAssociateSection(List<SocialAccountModel> existingAccounts, int selfId,
                                       java.util.List<Integer> preloadedLinkedIds) {
        linkPool = existingAccounts != null ? existingAccounts : new ArrayList<>();
        linkSelfId = selfId;
        linkedItems = new ArrayList<>();
        LinearLayout associateSection = findViewById(R.id.associate_section);
        RecyclerView recyclerLinked = findViewById(R.id.recycler_linked);
        linkedCard = findViewById(R.id.linked_card);

        setupAssociateSectionIds(preloadedLinkedIds != null
                ? preloadedLinkedIds : java.util.Collections.emptyList());

        // The header (and its icon-only Add action) always stays on screen;
        // the rows card shows only when something is linked.
        associateSection.setVisibility(View.VISIBLE);

        linkedAdapter = new AssociatedAccountAdapter(linkedItems, true,
                (linkedAccount, removed) -> refreshLinkedVisibility());
        recyclerLinked.setLayoutManager(new LinearLayoutManager(this));
        recyclerLinked.setHasFixedSize(true);
        recyclerLinked.setAdapter(linkedAdapter);
        refreshLinkedVisibility();

        findViewById(R.id.btn_add_associate).setOnClickListener(v -> openLinkSearch());
    }

    /** O(n) link resolution via id set (was O(n*m) nested scan). */
    private void setupAssociateSectionIds(java.util.List<Integer> linkedIds) {
        if (linkedIds.isEmpty() || linkPool.isEmpty()) {
            return;
        }
        java.util.HashSet<Integer> wanted = new java.util.HashSet<>(linkedIds);
        for (SocialAccountModel account : linkPool) {
            if (wanted.contains(account.getId())) {
                linkedItems.add(account);
            }
        }
    }

    /**
     * Rows-card toggle: hidden entirely when nothing is linked, rows
     * otherwise. The section header (and its Add action) stays visible in
     * both states.
     */
    private void refreshLinkedVisibility() {
        boolean empty = linkedItems == null || linkedItems.isEmpty();
        if (linkedCard != null) {
            linkedCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    /** Offending username field (error already set), null when valid. */
    private View validateUsername(EditText inputUsername, String username) {
        if (username.isEmpty()) {
            inputUsername.setError(getString(R.string.err_username_required));
            inputUsername.requestFocus();
            return inputUsername;
        }
        return null;
    }

    /** Outgoing link edges for the save transaction. */
    private List<Integer> collectLinkedIds() {
        List<Integer> linkedIds = new ArrayList<>(linkedItems.size());
        for (SocialAccountModel linkedAccount : linkedItems) {
            linkedIds.add(linkedAccount.getId());
        }
        return linkedIds;
    }

    private void showSaveFailed() {
        showError(R.string.err_save_failed);
    }

    private void bindEditForm(@NonNull SocialAccountModel item,
                              @NonNull List<SocialAccountModel> pool,
                              @NonNull java.util.List<Integer> linkedIds) {
        platformIcon = findViewById(R.id.platform_icon);
        platformName = findViewById(R.id.platform_name);
        TextView textSaveLabel = findViewById(R.id.text_save_label);
        EditText inputUsername = findViewById(R.id.input_username);
        EditText inputPassword = findViewById(R.id.input_password);
        EditText inputPin = findViewById(R.id.input_pin);

        textSaveLabel.setText(R.string.action_update);

        selectedIcon = SocialPlatformModel.iconFor(item.getPlatform(), item.getIconRes());
        selectedName = item.getPlatform();
        SocialPlatformModel.bindIcon(platformIcon, selectedName, selectedIcon);
        platformName.setText(item.getPlatform());
        inputUsername.setText(item.getUsername());
        inputPassword.setText(item.getPassword());
        inputPin.setText(item.getPin());

        findViewById(R.id.platform_selector).setOnClickListener(v -> openPlatformSearch());

        findViewById(R.id.btn_toggle_password).setOnClickListener(v ->
                Ui.togglePasswordVisibility(inputPassword));

        findViewById(R.id.btn_toggle_pin).setOnClickListener(v ->
                Ui.togglePasswordVisibility(inputPin));

        setupAssociateSection(pool, item.getId(), linkedIds != null
                ? linkedIds : java.util.Collections.emptyList());

        findViewById(R.id.btn_save).setOnClickListener(v -> {
            String username = inputUsername.getText().toString().trim();
            String password = inputPassword.getText().toString();
            String pin = inputPin.getText().toString();

            if (validateUsername(inputUsername, username) != null) {
                return;
            }
            v.setEnabled(false);

            // In-place update: same row id, so reverse links from other
            // accounts survive; the whole save is one transaction.
            final List<Integer> outIds = collectLinkedIds();
            final SocialAccountModel updated = new SocialAccountModel(item.getId(),
                    selectedName, username, password, pin,
                    selectedIcon, item.getCreatedAt(), 0);
            vaultIo(() -> {
                long savedId = db().saveSocialAccountWithLinks(updated, outIds);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (savedId < 0) {
                        v.setEnabled(true);
                        showSaveFailed();
                        return;
                    }
                    cache().invalidate();
                    setResult(RESULT_OK);
                    finish();
                    app().notifyOnReturn(R.string.msg_updated);
                });
            });
        });

        // Delete in edit mode, same in-row outline-red pattern as the bank
        // and government ID screens. Soft-deletes to Trash behind a normal
        // delete dialog.
        View btnDelete = findViewById(R.id.btn_delete);
        btnDelete.setVisibility(View.VISIBLE);
        btnDelete.setOnClickListener(v -> confirmDeleteToTrash(
                "Delete Account",
                "Are you sure you want to delete this "
                        + item.getPlatform() + " account?",
                () -> {
                    final int rowId = item.getId();
                    vaultIo(() -> {
                        db().moveSocialAccountToTrash(rowId);
                        cache().invalidate();
                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            setResult(RESULT_OK);
                            finish();
                            app().notifyOnReturn(R.string.msg_deleted);
                        });
                    });
                }));

    }

    /**
     * Link pool for the associated-account picker: warmed dashboard masters
     * when present (post-auth preload), direct query on miss. The picker
     * itself then only filters in memory — opening search never hits the DB.
     */
    private List<SocialAccountModel> cachedLinkPool() {
        com.akin.wallet.db.VaultWarmCache.Snapshot cached = cache().snapshot();
        if (cached != null && cached.activeAccounts != null) {
            return new ArrayList<>(cached.activeAccounts);
        }
        return db().getAllSocialAccounts();
    }
}
