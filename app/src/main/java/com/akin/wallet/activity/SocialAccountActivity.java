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
import com.akin.wallet.db.VaultWarmCache;
import com.akin.wallet.util.Ui;
import com.google.android.material.search.SearchView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Social account create/edit screen. Add mode when no id passed, edit otherwise.
 */
public class SocialAccountActivity extends BaseVaultActivity {

    public static final String EXTRA_ACCOUNT_ID = "extra_account_id";

    /** Default pick for a fresh form (also the icon fallback for stored rows). */
    public static final String DEFAULT_PLATFORM_NAME = "Google";

    /** Row id only — secrets never travel as Intent extras. */
    public static Intent editIntent(@NonNull Context context,
                                    @NonNull SocialAccountModel item) {
        return new Intent(context, SocialAccountActivity.class)
                .putExtra(EXTRA_ACCOUNT_ID, (long) item.getId());
    }

    private int selectedIcon = SocialPlatformModel.iconFor(DEFAULT_PLATFORM_NAME);
    private String selectedName = DEFAULT_PLATFORM_NAME;
    private ImageView selectedPlatformIcon;
    private TextView selectedPlatformName;
    private View linkedAccountsCard;
    private List<SocialAccountModel> linkPool = new ArrayList<>();
    private List<SocialAccountModel> linkedItems = new ArrayList<>();
    private AssociatedAccountAdapter linkedAdapter;
    private int linkSelfId = -1;

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

    private static final String KEY_LINK_QUERY = "link_search_query";
    private static final String KEY_LINK_OPEN = "link_search_open";
    private static final String KEY_SOCIAL_USERNAME = "social_username";
    private static final String KEY_SOCIAL_PASSWORD = "social_password";
    private static final String KEY_SOCIAL_PIN = "social_pin";
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
            VaultWarmCache.Snapshot snap = cache().snapshot();
            if (snap != null && snap.activeAccounts != null) {
                pool = new ArrayList<>(snap.activeAccounts);
            } else {
                pool = db().getAllSocialAccounts();
            }
            runIfAlive(gen, () -> {
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
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_PLATFORM_QUERY, platformQuery);
        outState.putBoolean(KEY_PLATFORM_OPEN, platformSearchOpen);
        outState.putString(KEY_LINK_QUERY, linkQuery);
        outState.putBoolean(KEY_LINK_OPEN, linkSearchOpen);
        outState.putInt(KEY_SELECTED_ICON, selectedIcon);
        outState.putString(KEY_SELECTED_NAME, selectedName);
        // Typed credentials: async rebind runs after restore and would clobber them.
        EditText u = findViewById(R.id.social_account_username_field);
        EditText p = findViewById(R.id.social_account_password_field);
        EditText n = findViewById(R.id.social_account_pin_field);
        if (u != null) outState.putString(KEY_SOCIAL_USERNAME, u.getText().toString());
        if (p != null) outState.putString(KEY_SOCIAL_PASSWORD, p.getText().toString());
        if (n != null) outState.putString(KEY_SOCIAL_PIN, n.getText().toString());
        if (!linkedItems.isEmpty()) {
            outState.putIntArray(KEY_LINKED_IDS, idsOf(linkedItems));
        }
    }

    private static int[] idsOf(List<SocialAccountModel> items) {
        List<Integer> ids = toIdList(items);
        int[] out = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            out[i] = ids.get(i);
        }
        return out;
    }

    /** Re-applies picker + link picks + typed credentials that async rebind clobbers. */
    private void restoreTransientState(@NonNull Bundle savedInstanceState) {
        selectedIcon = savedInstanceState.getInt(KEY_SELECTED_ICON, selectedIcon);
        String name = savedInstanceState.getString(KEY_SELECTED_NAME, null);
        if (name != null) {
            selectedName = name;
        }
        if (selectedPlatformIcon != null) {
            SocialPlatformModel.bindIcon(selectedPlatformIcon, selectedName, selectedIcon);
        }
        if (selectedPlatformName != null) {
            selectedPlatformName.setText(selectedName);
        }
        restoreTypedDraft(savedInstanceState);
        restoreLinked(savedInstanceState);
    }

    private void restoreTypedDraft(@NonNull Bundle savedInstanceState) {
        if (!savedInstanceState.containsKey(KEY_SOCIAL_USERNAME)
                && !savedInstanceState.containsKey(KEY_SOCIAL_PASSWORD)
                && !savedInstanceState.containsKey(KEY_SOCIAL_PIN)) {
            return;
        }
        EditText u = findViewById(R.id.social_account_username_field);
        EditText p = findViewById(R.id.social_account_password_field);
        EditText n = findViewById(R.id.social_account_pin_field);
        if (u != null && savedInstanceState.containsKey(KEY_SOCIAL_USERNAME)) {
            u.setText(savedInstanceState.getString(KEY_SOCIAL_USERNAME, ""));
        }
        if (p != null && savedInstanceState.containsKey(KEY_SOCIAL_PASSWORD)) {
            p.setText(savedInstanceState.getString(KEY_SOCIAL_PASSWORD, ""));
        }
        if (n != null && savedInstanceState.containsKey(KEY_SOCIAL_PIN)) {
            n.setText(savedInstanceState.getString(KEY_SOCIAL_PIN, ""));
        }
    }

    private void restoreLinked(@NonNull Bundle savedInstanceState) {
        int[] ids = savedInstanceState.getIntArray(KEY_LINKED_IDS);
        if (ids != null && ids.length > 0 && linkedAdapter != null) {
            linkedItems.clear();
            HashMap<Integer, SocialAccountModel> byId = mapById(linkPool);
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

    private static HashMap<Integer, SocialAccountModel> mapById(List<SocialAccountModel> pool) {
        HashMap<Integer, SocialAccountModel> byId = new HashMap<>(pool.size() * 2);
        for (SocialAccountModel candidate : pool) {
            byId.put(candidate.getId(), candidate);
        }
        return byId;
    }

    /** In-place platform picker: row taps apply the platform and close. */
    private void setupPlatformSearch(Bundle savedInstanceState) {
        platformSearchView = findViewById(R.id.social_account_platform_search_view);
        recyclerPlatformSearch = findViewById(R.id.social_account_platform_search_list);
        emptyPlatformResults = findViewById(R.id.social_account_platform_empty_state);

        platformAdapter = new SocialPlatformAdapter(cache().catalog(),
                (iconRes, name, url) -> {
                    selectedIcon = iconRes;
                    selectedName = name;
                    SocialPlatformModel.bindIcon(selectedPlatformIcon, selectedName, selectedIcon);
                    selectedPlatformName.setText(selectedName);
                    platformSearchView.hide();
                });
        recyclerPlatformSearch.setLayoutManager(new LinearLayoutManager(this));
        setupSearchList(recyclerPlatformSearch, platformAdapter, emptyPlatformResults);

        setupVaultSearchView(platformSearchView, text -> {
            platformQuery = text;
            platformAdapter.getFilter().filter(text);
        }, shown -> platformSearchOpen = shown);

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

    /** In-place link picker: rebuilt on every open, excluding self + linked. */
    private void setupLinkSearch(Bundle savedInstanceState) {
        linkSearchView = findViewById(R.id.social_account_link_search_view);
        recyclerLinkSearch = findViewById(R.id.social_account_link_search_list);
        emptyLinkResults = findViewById(R.id.social_account_link_empty_state);
        recyclerLinkSearch.setLayoutManager(new LinearLayoutManager(this));

        setupVaultSearchView(linkSearchView, text -> {
            linkQuery = text;
            if (linkSearchAdapter != null) {
                linkSearchAdapter.getFilter().filter(text);
            }
        }, shown -> linkSearchOpen = shown);

        if (savedInstanceState != null
                && savedInstanceState.getBoolean(KEY_LINK_OPEN, false)) {
            linkQuery = savedInstanceState.getString(KEY_LINK_QUERY, "");
            openLinkSearch();
            if (!linkQuery.isEmpty()) {
                linkSearchView.getEditText().setText(linkQuery);
            }
        }
    }

    private static void setupVaultSearchView(SearchView searchView, Consumer<String> onQuery,
                                             Consumer<Boolean> onShown) {
        searchView.getEditText().setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        searchView.getEditText().addTextChangedListener(new Ui.SimpleTextWatcher() {
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                onQuery.accept(text != null ? text.toString() : "");
            }
        });
        searchView.addTransitionListener((view, oldState, newState) ->
                onShown.accept(newState == SearchView.TransitionState.SHOWN));
    }

    private static void setupSearchList(RecyclerView list,
                                        RecyclerView.Adapter<?> adapter, View empty) {
        list.setAdapter(adapter);
        observeEmpty(adapter, list, empty);
    }

    private static void observeEmpty(RecyclerView.Adapter<?> adapter, RecyclerView list, View empty) {
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                boolean isEmpty = adapter.getItemCount() == 0;
                empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            }
        });
    }

    /** Opens the in-place link SearchView. */
    private void openLinkSearch() {
        HashSet<Integer> linkedSet = new HashSet<>(linkedItems.size() * 2);
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
            showMessage(R.string.msg_all_linked);
            return;
        }

        linkSearchAdapter = new AssociatedAccountAdapter(available, false, (picked, removed) -> {
            if (!containsId(linkedItems, picked.getId())) {
                linkedItems.add(picked);
                linkedAdapter.onExternalAdd(picked);
                linkedAdapter.notifyItemInserted(linkedItems.size() - 1);
                refreshLinkedVisibility();
            }
            linkSearchView.hide();
        });
        setupSearchList(recyclerLinkSearch, linkSearchAdapter, emptyLinkResults);
        emptyLinkResults.setVisibility(View.GONE);
        recyclerLinkSearch.setVisibility(View.VISIBLE);
        linkSearchView.getEditText().setText("");
        linkSearchView.show();
    }

    private static boolean containsId(List<SocialAccountModel> items, int id) {
        for (SocialAccountModel item : items) {
            if (item.getId() == id) {
                return true;
            }
        }
        return false;
    }

    private void bindCommonViews() {
        selectedPlatformIcon = findViewById(R.id.social_account_platform_icon);
        selectedPlatformName = findViewById(R.id.social_account_platform_name);
        SocialPlatformModel.bindIcon(selectedPlatformIcon, selectedName, selectedIcon);
        selectedPlatformName.setText(selectedName);

        findViewById(R.id.social_account_platform_selector).setOnClickListener(v -> platformSearchView.show());

        EditText inputPassword = findViewById(R.id.social_account_password_field);
        findViewById(R.id.social_account_password_visibility_toggle).setOnClickListener(v ->
                Ui.togglePasswordVisibility(inputPassword));

        EditText inputPin = findViewById(R.id.social_account_pin_field);
        findViewById(R.id.social_account_pin_visibility_toggle).setOnClickListener(v ->
                Ui.togglePasswordVisibility(inputPin));
    }

    private void persistAccount(SocialAccountModel model, List<Integer> linkIds,
                                int doneMessage, View saveButton) {
        vaultIo(() -> {
            long savedId = db().saveSocialAccountWithLinks(model, linkIds);
            runOnUiThread(() -> {
                if (!isAlive()) return;
                if (savedId < 0) {
                    saveButton.setEnabled(true);
                    showMessage(R.string.err_save_failed);
                    return;
                }
                cache().invalidate();
                setResult(RESULT_OK);
                finish();
                app().notifyOnReturn(doneMessage);
            });
        });
    }

    private void bindAddForm(List<SocialAccountModel> pool) {
        selectedIcon = SocialPlatformModel.iconFor(DEFAULT_PLATFORM_NAME);
        selectedName = DEFAULT_PLATFORM_NAME;

        bindCommonViews();
        setupAssociateSection(pool, -1, Collections.emptyList());

        Ui.makeSaveButtonFullWidth(findViewById(R.id.form_primary_action));

        findViewById(R.id.form_primary_action).setOnClickListener(v -> {
            EditText inputUsername = findViewById(R.id.social_account_username_field);
            EditText inputPassword = findViewById(R.id.social_account_password_field);
            EditText inputPin = findViewById(R.id.social_account_pin_field);
            String username = inputUsername.getText().toString().trim();
            String password = inputPassword.getText().toString();
            String pin = inputPin.getText().toString();

            if (!validateUsername(inputUsername, username)) {
                return;
            }
            v.setEnabled(false);

            persistAccount(new SocialAccountModel(selectedName, username,
                    password, pin, selectedIcon, 0, 0),
                    collectLinkedIds(), R.string.msg_account_saved, v);
        });

    }

    /** Shared associate-accounts section for add (selfId -1) and edit modes. */
    private void setupAssociateSection(List<SocialAccountModel> existingAccounts, int selfId,
                                       List<Integer> preloadedLinkedIds) {
        linkPool = existingAccounts != null ? existingAccounts : new ArrayList<>();
        linkSelfId = selfId;
        linkedItems = new ArrayList<>();
        LinearLayout associateSection = findViewById(R.id.social_account_linked_section);
        RecyclerView recyclerLinked = findViewById(R.id.social_account_linked_list);
        linkedAccountsCard = findViewById(R.id.social_account_linked_card);

        setupAssociateSectionIds(preloadedLinkedIds);

        associateSection.setVisibility(View.VISIBLE);

        linkedAdapter = new AssociatedAccountAdapter(linkedItems, true,
                (linkedAccount, removed) -> refreshLinkedVisibility());
        recyclerLinked.setLayoutManager(new LinearLayoutManager(this));
        recyclerLinked.setHasFixedSize(false);
        recyclerLinked.setAdapter(linkedAdapter);
        refreshLinkedVisibility();

        findViewById(R.id.social_account_add_linked_button).setOnClickListener(v -> openLinkSearch());
    }

    private void setupAssociateSectionIds(List<Integer> linkedIds) {
        if (linkedIds == null || linkedIds.isEmpty() || linkPool.isEmpty()) {
            return;
        }
        HashSet<Integer> wanted = new HashSet<>(linkedIds);
        for (SocialAccountModel account : linkPool) {
            if (wanted.contains(account.getId())) {
                linkedItems.add(account);
            }
        }
    }

    /** Rows-card toggle: header stays visible in both states. */
    private void refreshLinkedVisibility() {
        linkedAccountsCard.setVisibility(linkedItems.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private boolean validateUsername(EditText inputUsername, String username) {
        if (username.isEmpty()) {
            inputUsername.setError(getString(R.string.err_username_required));
            inputUsername.requestFocus();
            return false;
        }
        return true;
    }

    /** Outgoing link edges for the save transaction. */
    private List<Integer> collectLinkedIds() {
        return toIdList(linkedItems);
    }

    private static List<Integer> toIdList(List<SocialAccountModel> items) {
        List<Integer> ids = new ArrayList<>(items.size());
        for (SocialAccountModel item : items) {
            ids.add(item.getId());
        }
        return ids;
    }

    private void bindEditForm(@NonNull SocialAccountModel item,
                              @NonNull List<SocialAccountModel> pool,
                              @NonNull List<Integer> linkedIds) {
        TextView textSaveLabel = findViewById(R.id.form_primary_action_label);
        EditText inputUsername = findViewById(R.id.social_account_username_field);
        EditText inputPassword = findViewById(R.id.social_account_password_field);
        EditText inputPin = findViewById(R.id.social_account_pin_field);

        textSaveLabel.setText(R.string.action_update);

        selectedIcon = SocialPlatformModel.iconFor(item.getPlatform(), item.getIconRes());
        selectedName = item.getPlatform();
        bindCommonViews();
        inputUsername.setText(item.getUsername());
        inputPassword.setText(item.getPassword());
        inputPin.setText(item.getPin());

        setupAssociateSection(pool, item.getId(), linkedIds);

        findViewById(R.id.form_primary_action).setOnClickListener(v -> {
            String username = inputUsername.getText().toString().trim();
            String password = inputPassword.getText().toString();
            String pin = inputPin.getText().toString();

            if (!validateUsername(inputUsername, username)) {
                return;
            }
            v.setEnabled(false);

            persistAccount(new SocialAccountModel(item.getId(),
                    selectedName, username, password, pin,
                    selectedIcon, item.getCreatedAt(), 0),
                    collectLinkedIds(), R.string.msg_updated, v);
        });

        View btnDelete = findViewById(R.id.form_destructive_action);
        btnDelete.setVisibility(View.VISIBLE);
        btnDelete.setOnClickListener(v -> confirmDeleteToTrash(
                getString(R.string.confirm_delete_account_title),
                getString(R.string.confirm_delete_account_message, item.getPlatform()),
                () -> {
                    final int rowId = item.getId();
                    vaultIo(() -> {
                        db().moveSocialAccountToTrash(rowId);
                        cache().invalidate();
                        runOnUiThread(() -> {
                            if (!isAlive()) return;
                            setResult(RESULT_OK);
                            finish();
                            app().notifyOnReturn(R.string.msg_deleted);
                        });
                    });
                }));

    }
}
