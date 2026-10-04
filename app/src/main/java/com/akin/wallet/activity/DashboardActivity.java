package com.akin.wallet.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.adapter.BankCardAdapter;
import com.akin.wallet.adapter.GovernmentIdAdapter;
import com.akin.wallet.adapter.SocialAccountAdapter;
import com.akin.wallet.db.VaultStore;
import com.akin.wallet.model.BankCardModel;
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.util.DashboardSearch;
import com.akin.wallet.util.Ui;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.search.SearchView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Dashboard (Home) — single-screen host, no fragments.
 */
public class DashboardActivity extends BaseVaultActivity {

    public static final class DashboardState extends androidx.lifecycle.ViewModel {
        List<GovernmentIDModel> ids;
        List<BankCardModel> cards;
        List<SocialAccountModel> accounts;
        DashboardSearch.Index index = DashboardSearch.EMPTY_INDEX;
        long session = -1;
        long data = -1;
        long idRevision = -1;
        long cardRevision = -1;
        long accountRevision = -1;

        @Override
        protected void onCleared() {
            clear();
        }

        void clear() {
            ids = null;
            cards = null;
            accounts = null;
            index = DashboardSearch.EMPTY_INDEX;
            session = -1;
            data = -1;
            idRevision = cardRevision = accountRevision = -1;
        }
    }

    private DashboardState display;
    private View dashboardContent;
    private boolean dashboardLoadInFlight;
    private int dashboardBindGeneration;
    private boolean dashboardReady;
    private BankCardAdapter cardAdapter;
    private RecyclerView bankCardCarousel;
    private View bankCardEmptyState;
    private GovernmentIdAdapter idAdapter;
    private RecyclerView governmentIdCarousel;
    private View governmentIdEmptyState;
    private SocialAccountAdapter socialAdapter;
    private View socialAccountCard;
    private RecyclerView socialAccountList;
    private View socialAccountEmptyState;

    /**
     * Shared carousel gap.
     */
    private RecyclerView.ItemDecoration sharedGap;

    private FloatingActionButton quickAddButton;
    private View quickAddMenu;
    private View quickAddScrim;
    private boolean isFabMenuOpen = false;

    // M3 Search state. Masters hold full rows; keystrokes filter on a background thread.
    private List<GovernmentIDModel> allIds;
    private List<BankCardModel> allCards;
    private List<SocialAccountModel> allAccounts;
    private String currentQuery = "";
    private static final String KEY_SEARCH_QUERY = "dashboard_search_query";
    private static final String KEY_SEARCH_OPEN = "dashboard_search_open";
    private static final String KEY_FAB_MENU_OPEN = "dashboard_fab_menu_open";
    private SearchView searchView;
    private boolean searchShowing = false;
    private BankCardAdapter searchCardAdapter;
    private RecyclerView searchBankCardList;
    private View searchHeaderCards;
    private GovernmentIdAdapter searchIdAdapter;
    private RecyclerView searchGovernmentIdList;
    private View searchHeaderIds;
    private SocialAccountAdapter searchSocialAdapter;
    private RecyclerView searchSocialAccountList;
    private View searchSocialAccountCard;
    private View searchHeaderSocial;
    private View headerIds;
    private View headerCards;
    private View headerSocial;
    private View emptySearchResults;
    private TextView emptySearchTitle;
    private TextView emptySearchSub;

    /**
     * Search pipeline: single thread = ordered, generation drops stale results,
     * A short debounce collapses fast typing; obsolete queued work is cancelled.
     */
    private DashboardSearch.Index searchIndex = DashboardSearch.EMPTY_INDEX;
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private Future<?> searchTask;
    private int searchGeneration;
    private long boundSearchData = -1;
    private String boundSearchQuery;
    private static final long SEARCH_DEBOUNCE_MS = 50L;


    @Override
    protected void onCreate(Bundle instanceState) {
        super.onCreate(instanceState);
        final Bundle savedInstanceState = restoredDraft();
        display = new androidx.lifecycle.ViewModelProvider(this).get(DashboardState.class);
        setContentView(R.layout.activity_dashboard);
        dashboardContent = findViewById(R.id.dashboard_content);
        applyChrome();
        sharedGap = Ui.carouselGapDecoration(this);

        setupHeader();
        setupCardCarousel();
        setupIdsCarousel();
        setupSocialAccounts();
        setupSearch();
        setupAddMenu();

        if (savedInstanceState != null) {
            currentQuery = savedInstanceState.getString(KEY_SEARCH_QUERY, "");
            boolean open = savedInstanceState.getBoolean(KEY_SEARCH_OPEN, false);
            if (open && searchView != null) {
                if (!currentQuery.isEmpty()) {
                    searchView.getEditText().setText(currentQuery);
                }
                setFabVisible(false);
                searchView.post(() -> searchView.show());
            } else if (savedInstanceState.getBoolean(KEY_FAB_MENU_OPEN, false)) {
                quickAddMenu.post(() -> {
                    if (!isFabMenuOpen) {
                        toggleAddMenu();
                    }
                });
            }
        }
        // No refresh here: onResume owns loading.
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDashboard();
    }

    @Override
    protected void captureDraft() {
        Bundle outState = draftState();
        outState.clear();
        outState.putString(KEY_SEARCH_QUERY, currentQuery);
        outState.putBoolean(KEY_SEARCH_OPEN, searchShowing);
        outState.putBoolean(KEY_FAB_MENU_OPEN, isFabMenuOpen);
    }

    @Override
    protected void onDestroy() {
        cancelSearch();
        searchExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onVaultLocked() {
        super.onVaultLocked();
        cancelSearch();
        dashboardLoadInFlight = false;
        dashboardBindGeneration++;
        dashboardReady = false;
        dashboardContent.setVisibility(View.INVISIBLE);
        if (display != null) display.clear();
        searchIndex = DashboardSearch.EMPTY_INDEX;
        boundSearchData = -1;
        boundSearchQuery = null;
        allIds = null;
        allCards = null;
        allAccounts = null;
        if (idAdapter != null) idAdapter.updateData(java.util.Collections.emptyList());
        if (cardAdapter != null) cardAdapter.updateData(java.util.Collections.emptyList());
        if (socialAdapter != null) socialAdapter.updateData(java.util.Collections.emptyList());
        if (searchIdAdapter != null) searchIdAdapter.updateData(java.util.Collections.emptyList());
        if (searchCardAdapter != null)
            searchCardAdapter.updateData(java.util.Collections.emptyList());
        if (searchSocialAdapter != null)
            searchSocialAdapter.updateData(java.util.Collections.emptyList());
    }

    private void cancelSearch() {
        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        searchGeneration++;
        if (searchTask != null) {
            searchTask.cancel(true);
            searchTask = null;
        }
    }


    /**
     * TopAppBar header: search opens SearchView, gear opens Settings.
     */
    private void setupHeader() {
        headerIds = findViewById(R.id.dashboard_government_id_section_header);
        headerCards = findViewById(R.id.dashboard_bank_card_section_header);
        headerSocial = findViewById(R.id.dashboard_social_account_section_header);

        View btnSearch = findViewById(R.id.dashboard_search_button);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> {
                if (searchView != null) {
                    searchView.show();
                }
            });
        }
        View btnSettings = findViewById(R.id.dashboard_settings_button);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(
                    v -> startActivity(new Intent(this, SettingsActivity.class)));
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (searchShowing && searchView != null) {
                    searchView.hide();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    /**
     * Full-screen SearchView: typing filters masters into result lists.
     */
    private void setupSearch() {
        searchView = findViewById(R.id.dashboard_search_view);
        // Each search list keeps its own pool: IDs and cards share viewType 0
        // but inflate different layouts, so a shared pool recycles across lists.
        searchIdAdapter = new GovernmentIdAdapter(this::openIdEditor);
        searchGovernmentIdList = findViewById(R.id.dashboard_search_government_id_list);
        setupHorizontalList(searchGovernmentIdList, searchIdAdapter);
        searchHeaderIds = findViewById(R.id.dashboard_search_government_id_header);

        searchCardAdapter = new BankCardAdapter(this::openBankEditor);
        searchBankCardList = findViewById(R.id.dashboard_search_bank_card_list);
        setupHorizontalList(searchBankCardList, searchCardAdapter);
        searchHeaderCards = findViewById(R.id.dashboard_search_bank_card_header);

        // Row taps open the edit screen, same as the dashboard list.
        searchSocialAdapter = new SocialAccountAdapter(this::openSocialEditor);
        searchSocialAccountList = findViewById(R.id.dashboard_search_social_account_list);
        searchSocialAccountList.setLayoutManager(new LinearLayoutManager(this));
        searchSocialAccountList.setAdapter(searchSocialAdapter);
        searchSocialAccountList.setHasFixedSize(false);
        searchSocialAccountCard = findViewById(R.id.dashboard_search_social_account_card);
        searchHeaderSocial = findViewById(R.id.dashboard_search_social_account_header);

        emptySearchResults = findViewById(R.id.dashboard_search_empty_state);
        emptySearchTitle = findViewById(R.id.dashboard_search_empty_title);
        emptySearchSub = findViewById(R.id.dashboard_search_empty_subtitle);

        // Vault terms must not enter IME dictionary/history.
        searchView.getEditText().setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        searchView.getEditText().addTextChangedListener(new Ui.SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                currentQuery = text != null ? text.toString() : "";
                scheduleSearch();
            }
        });
        searchView.addTransitionListener((view, oldState, newState) -> {
            if (newState == SearchView.TransitionState.SHOWING) {
                // Prepare during the opening animation instead of after it.
                scheduleSearch();
            } else if (newState == SearchView.TransitionState.SHOWN) {
                searchShowing = true;
                // The opaque, full-screen search now covers the Dashboard.
                // Stop measuring/rendering that hierarchy until closing starts.
                dashboardContent.setVisibility(View.GONE);
                if (isFabMenuOpen) {
                    toggleAddMenu();
                }
                setFabVisible(false);
                // Re-filter on open (covers rotation restore: the query is
                // set before show, masters load separately).
                scheduleSearch();
            } else if (newState == SearchView.TransitionState.HIDING) {
                dashboardContent.setVisibility(dashboardReady ? View.VISIBLE : View.INVISIBLE);
            } else if (newState == SearchView.TransitionState.HIDDEN) {
                searchShowing = false;
                cancelSearch();
                setFabVisible(true);
            }
        });
    }

    /**
     * FAB hides while the SearchView covers the screen.
     */
    private void setFabVisible(boolean visible) {
        quickAddButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Debounced search entry point (UI thread only). Drops stale generations.
     */
    private void scheduleSearch() {
        if (searchIdAdapter == null || searchCardAdapter == null || searchSocialAdapter == null) {
            return;
        }
        cancelSearch();
        final int generation = ++searchGeneration;
        if (boundSearchData == display.data && boundSearchData == store().dataGeneration()
                && currentQuery.equals(boundSearchQuery)) return;
        final String raw = currentQuery;
        final DashboardSearch.Index snapshot =
                searchIndex != null ? searchIndex : DashboardSearch.EMPTY_INDEX;
        final long sessionToken = app().getSession().generation();
        final long dataToken = display.data;
        pendingSearch = () -> {
            pendingSearch = null;
            try {
                searchTask = searchExecutor.submit(() -> runSearch(generation, raw, snapshot, sessionToken, dataToken));
            } catch (RuntimeException e) {
                if (com.akin.wallet.BuildConfig.DEBUG)
                    Log.w("Dashboard", "search executor shut down", e);
            }
        };
        searchHandler.postDelayed(pendingSearch, raw.trim().isEmpty() ? 0L : SEARCH_DEBOUNCE_MS);
    }

    /**
     * Background pass: normalize query, scan pre-lowered strings.
     */
    private void runSearch(int generation, String raw, DashboardSearch.Index snapshot,
                           long sessionToken, long dataToken) {
        final DashboardSearch.Query query;
        final DashboardSearch.Result result;
        try {
            query = DashboardSearch.normalizeQuery(raw);
            result = DashboardSearch.search(snapshot, query);
        } catch (RuntimeException e) {
            if (com.akin.wallet.BuildConfig.DEBUG)
                Log.w("Dashboard", "search failed, keeping previous results", e);
            return;
        }
        searchHandler.post(() -> {
            if (snapshot != searchIndex || generation != searchGeneration || !isAlive() || !app().getSession().isCurrent(sessionToken) || dataToken != store().dataGeneration()) {
                return;
            }
            if (allIds == null || allCards == null || allAccounts == null
                    || searchIdAdapter == null || searchCardAdapter == null
                    || searchSocialAdapter == null) {
                return;
            }
            bindSearchResults(result.ids, result.cards, result.accounts, !query.empty, generation);
        });
    }

    /**
     * True while any search list is mid-layout.
     */
    private boolean isAnySearchListLayingOut() {
        return (searchGovernmentIdList != null && searchGovernmentIdList.isComputingLayout())
                || (searchBankCardList != null && searchBankCardList.isComputingLayout())
                || (searchSocialAccountList != null && searchSocialAccountList.isComputingLayout());
    }

    private void bindSearchResults(List<GovernmentIDModel> ids, List<BankCardModel> cards,
                                   List<SocialAccountModel> accounts, boolean searching, int generation) {
        if (!isAlive() || generation != searchGeneration) {
            return;
        }
        // Never dispatch into a running layout pass; re-queue behind it.
        if (isAnySearchListLayingOut()) {
            searchHandler.post(() ->
                    bindSearchResults(ids, cards, accounts, searching, generation));
            return;
        }
        searchIdAdapter.updateData(ids);
        boolean hasIds = ids != null && !ids.isEmpty();
        setVisible(searchGovernmentIdList, hasIds);
        setVisible(searchHeaderIds, hasIds);

        searchCardAdapter.updateData(cards);
        boolean hasCards = cards != null && !cards.isEmpty();
        setVisible(searchBankCardList, hasCards);
        setVisible(searchHeaderCards, hasCards);

        searchSocialAdapter.updateData(accounts);
        boolean hasAccounts = accounts != null && !accounts.isEmpty();
        setVisible(searchSocialAccountCard, hasAccounts);
        setVisible(searchHeaderSocial, hasAccounts);

        boundSearchData = display.data;
        boundSearchQuery = currentQuery;
        boolean allEmpty = !hasIds && !hasCards && !hasAccounts;
        setVisible(emptySearchResults, allEmpty);
        if (allEmpty) {
            if (searching) {
                if (emptySearchTitle != null) {
                    emptySearchTitle.setText(R.string.search_empty_title);
                }
                if (emptySearchSub != null) {
                    emptySearchSub.setText(getString(R.string.search_empty_sub_for,
                            getString(R.string.search_empty_sub), currentQuery.trim()));
                }
            } else {
                if (emptySearchTitle != null) {
                    emptySearchTitle.setText(R.string.search_empty_vault_title);
                }
                if (emptySearchSub != null) {
                    emptySearchSub.setText(R.string.search_empty_vault_sub);
                }
            }
        }
    }

    private static void setVisible(View view, boolean visible) {
        if (view != null) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Extended FAB: round main button expanding the 3-option menu above it.
     */
    private void setupAddMenu() {
        quickAddButton = findViewById(R.id.dashboard_quick_add_button);
        quickAddMenu = findViewById(R.id.dashboard_quick_add_menu);
        quickAddScrim = findViewById(R.id.dashboard_quick_add_scrim);
        quickAddButton.setOnClickListener(v -> toggleAddMenu());
        quickAddScrim.setOnClickListener(v -> {
            if (isFabMenuOpen) {
                toggleAddMenu();
            }
        });
        findViewById(R.id.dashboard_quick_add_option_government_id)
                .setOnClickListener(v -> openCreator(GovernmentIDActivity.class));
        findViewById(R.id.dashboard_quick_add_option_bank_card)
                .setOnClickListener(v -> openCreator(BankCardActivity.class));
        findViewById(R.id.dashboard_quick_add_option_social_account)
                .setOnClickListener(v -> openCreator(SocialAccountActivity.class));
    }

    private void toggleAddMenu() {
        isFabMenuOpen = !isFabMenuOpen;
        if (quickAddMenu != null) {
            quickAddMenu.setVisibility(isFabMenuOpen ? View.VISIBLE : View.GONE);
        }
        if (quickAddScrim != null) {
            quickAddScrim.setVisibility(isFabMenuOpen ? View.VISIBLE : View.GONE);
        }
        if (quickAddButton != null) {
            quickAddButton.setImageResource(isFabMenuOpen ? R.drawable.ic_close : R.drawable.ic_add);
        }
    }

    /**
     * Tap bumps updated_at so the row sorts newest-first on return.
     */
    private void openSocialEditor(SocialAccountModel item) {
        touchAndOpen(() -> db().touchSocialAccountUpdatedAt(item.getId()),
                SocialAccountActivity.editIntent(this, item), VaultStore.Section.SOCIAL_ACCOUNTS);
    }

    /**
     * Opens a creation screen. FAB is the only entry.
     */
    private void openCreator(Class<?> editorScreen) {
        if (isFabMenuOpen) {
            toggleAddMenu();
        }
        startActivity(new Intent(this, editorScreen));
    }

    private void openIdEditor(GovernmentIDModel item) {
        touchAndOpen(() -> db().touchIdCardUpdatedAt(item.getId()),
                GovernmentIDActivity.editIntent(this, item), VaultStore.Section.GOVERNMENT_IDS);
    }

    private void openBankEditor(BankCardModel item) {
        touchAndOpen(() -> db().touchBankCardUpdatedAt(item.getId()),
                BankCardActivity.editIntent(this, item), VaultStore.Section.BANK_CARDS);
    }

    private void touchAndOpen(Runnable touch, Intent intent, VaultStore.Section section) {
        vaultIo(() -> {
            touch.run();
            store().invalidate(section);
            runIfAlive(() -> startActivity(intent));
        });
    }

    private void setupHorizontalList(RecyclerView list, RecyclerView.Adapter<?> adapter) {
        list.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        list.setAdapter(adapter);
        list.setItemAnimator(null);
        list.addItemDecoration(sharedGap);
        list.setHasFixedSize(false);
        list.setItemViewCacheSize(4);
    }

    /**
     * Horizontal snap carousel rendering the user's bank cards.
     */
    private void setupCardCarousel() {
        bankCardCarousel = findViewById(R.id.dashboard_bank_card_carousel);
        bankCardEmptyState = findViewById(R.id.dashboard_bank_card_empty_state);

        cardAdapter = new BankCardAdapter(this::openBankEditor);
        wireSnapCarousel(bankCardCarousel, bankCardEmptyState,
                R.id.dashboard_bank_card_empty_action, cardAdapter, BankCardActivity.class);
    }

    private void refreshCardCarousel(List<BankCardModel> cards, Runnable committed) {
        if (cardAdapter == null || bankCardCarousel == null) {
            return;
        }
        cardAdapter.updateData(cards, () -> {
            committed.run();
        });
    }

    /**
     * Horizontal snap carousel rendering the user's government IDs.
     */
    private void setupIdsCarousel() {
        governmentIdCarousel = findViewById(R.id.dashboard_government_id_carousel);
        governmentIdEmptyState = findViewById(R.id.dashboard_government_id_empty_state);

        idAdapter = new GovernmentIdAdapter(this::openIdEditor);
        wireSnapCarousel(governmentIdCarousel, governmentIdEmptyState,
                R.id.dashboard_government_id_empty_action, idAdapter, GovernmentIDActivity.class);
    }

    private void wireSnapCarousel(RecyclerView carousel, View empty, int emptyActionId,
                                  RecyclerView.Adapter<?> adapter, Class<?> creator) {
        setupHorizontalList(carousel, adapter);
        new PagerSnapHelper().attachToRecyclerView(carousel);
        empty.setOnClickListener(v -> openCreator(creator));
        findViewById(emptyActionId).setOnClickListener(v -> openCreator(creator));
    }

    private void refreshIdsCarousel(List<GovernmentIDModel> ids, Runnable committed) {
        if (idAdapter == null || governmentIdCarousel == null) {
            return;
        }
        idAdapter.updateData(ids, () -> {
            committed.run();
        });
    }

    private static void bindCarouselVisibility(RecyclerView carousel, View empty, View header,
                                               boolean hasItems) {
        carousel.setVisibility(hasItems ? View.VISIBLE : View.GONE);
        if (header != null) {
            header.setVisibility(View.VISIBLE);
        }
        if (empty != null) {
            empty.setVisibility(!hasItems ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Social Account — vertical list of created accounts.
     */
    private void setupSocialAccounts() {
        socialAccountCard = findViewById(R.id.dashboard_social_account_card);
        socialAccountList = findViewById(R.id.dashboard_social_account_list);
        socialAccountEmptyState = findViewById(R.id.dashboard_social_account_empty_state);

        socialAdapter = new SocialAccountAdapter(this::openSocialEditor);
        socialAccountList.setLayoutManager(new LinearLayoutManager(this));
        socialAccountList.setAdapter(socialAdapter);
        socialAccountList.setItemAnimator(null);
        socialAccountList.setHasFixedSize(false);

        socialAccountEmptyState.setOnClickListener(v -> openCreator(SocialAccountActivity.class));
        findViewById(R.id.dashboard_social_account_empty_action).setOnClickListener(
                v -> openCreator(SocialAccountActivity.class));
    }

    /**
     * Binds social rows into the adaptive card. Defers only during an active layout.
     */
    private void refreshSocialAccounts(List<SocialAccountModel> accounts, Runnable committed) {
        if (socialAdapter == null || socialAccountList == null || !isAlive()) {
            return;
        }
        // Pending updates are safe to replace. A hidden or zero-height list cannot
        // consume them until this binding makes its populated card visible again.
        if (socialAccountList.isComputingLayout()) {
            final List<SocialAccountModel> snapshot =
                    accounts != null ? new ArrayList<>(accounts) : null;
            socialAccountList.post(() -> refreshSocialAccounts(snapshot, committed));
            return;
        }
        socialAdapter.updateData(accounts, committed);
    }

    private void bindSnapshot(List<GovernmentIDModel> ids, List<BankCardModel> cards,
                              List<SocialAccountModel> accounts) {
        final int generation = ++dashboardBindGeneration;
        final long sessionToken = app().getSession().generation();
        final int[] pending = {3};
        Runnable committed = () -> {
            if (!isAlive() || generation != dashboardBindGeneration
                    || !app().getSession().isCurrent(sessionToken) || --pending[0] != 0) return;
            // Publish the sections together only after all three adapters commit.
            // The first frame contains IDs, bank cards, then populated social rows.
            governmentIdCarousel.scrollToPosition(0);
            bindCarouselVisibility(governmentIdCarousel, governmentIdEmptyState, headerIds, !ids.isEmpty());
            bankCardCarousel.scrollToPosition(0);
            bindCarouselVisibility(bankCardCarousel, bankCardEmptyState, headerCards, !cards.isEmpty());
            socialAccountList.scrollToPosition(0);
            setVisible(socialAccountCard, !accounts.isEmpty());
            setVisible(socialAccountEmptyState, accounts.isEmpty());
            setVisible(headerSocial, true);
            dashboardReady = true;
            if (!searchShowing) dashboardContent.setVisibility(View.VISIBLE);
        };
        if (allIds != ids) refreshIdsCarousel(ids, committed);
        else committed.run();
        if (allCards != cards) refreshCardCarousel(cards, committed);
        else committed.run();
        if (allAccounts != accounts) refreshSocialAccounts(accounts, committed);
        else committed.run();
        allIds = ids;
        allCards = cards;
        allAccounts = accounts;
        searchIndex = display.index;
    }

    private void refreshDashboard() {
        if (!isAlive()) return;
        final long sessionToken = app().getSession().generation();
        final long dataToken = store().dataGeneration();
        if (display.session == sessionToken && display.data == dataToken && display.ids != null) {
            if (allIds != display.ids) bindSnapshot(display.ids, display.cards, display.accounts);
            return;
        }
        if (dashboardLoadInFlight) return;
        dashboardLoadInFlight = true;
        final long idRevision = store().sectionGeneration(VaultStore.Section.GOVERNMENT_IDS);
        final long cardRevision = store().sectionGeneration(VaultStore.Section.BANK_CARDS);
        final long accountRevision = store().sectionGeneration(VaultStore.Section.SOCIAL_ACCOUNTS);
        final boolean sameSession = display.session == sessionToken;
        final List<GovernmentIDModel> cachedIds = sameSession && display.idRevision == idRevision ? display.ids : null;
        final List<BankCardModel> cachedCards = sameSession && display.cardRevision == cardRevision ? display.cards : null;
        final List<SocialAccountModel> cachedAccounts = sameSession && display.accountRevision == accountRevision ? display.accounts : null;
        final DashboardSearch.Index previousIndex = sameSession ? display.index : DashboardSearch.EMPTY_INDEX;
        final int generation = nextLoadGeneration();
        vaultIo(() -> {
            final List<GovernmentIDModel> ids;
            final List<BankCardModel> cards;
            final List<SocialAccountModel> accounts;
            try {
                ids = cachedIds != null ? cachedIds : db().getAllIdCards();
                cards = cachedCards != null ? cachedCards : db().getAllBankCards();
                accounts = cachedAccounts != null ? cachedAccounts : db().getAllSocialAccounts();
            } catch (RuntimeException e) {
                if (com.akin.wallet.BuildConfig.DEBUG) Log.w("Dashboard", "refresh failed", e);
                runIfAlive(generation, () -> {
                    dashboardLoadInFlight = false;
                    showMessage(R.string.err_dashboard_load);
                });
                return;
            }
            DashboardSearch.Index prepared = DashboardSearch.buildIndex(previousIndex, ids, cards, accounts);
            runIfAlive(generation, () -> {
                dashboardLoadInFlight = false;
                if (dataToken != store().dataGeneration()) {
                    refreshDashboard();
                    return;
                }
                display.ids = prepared.idRows();
                display.cards = prepared.cardRows();
                display.accounts = prepared.accountRows();
                display.index = prepared;
                display.session = sessionToken;
                display.data = dataToken;
                display.idRevision = idRevision;
                display.cardRevision = cardRevision;
                display.accountRevision = accountRevision;
                bindSnapshot(display.ids, display.cards, display.accounts);
                if (searchShowing) {
                    scheduleSearch();
                }
            });
        });
    }
}
