package com.akin.wallet.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
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
import com.akin.wallet.db.VaultWarmCache;
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

/**
 * Dashboard (Home) — single-screen host, no fragments.
 */
public class DashboardActivity extends BaseVaultActivity {

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

    /** Shared carousel gap. */
    private RecyclerView.ItemDecoration sharedGap;

    private static final float CARD_ASPECT_RATIO = 1.586f;
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
     * 120ms debounce collapses fast typing.
     */
    private DashboardSearch.Index searchIndex = DashboardSearch.EMPTY_INDEX;
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private int searchGeneration;
    private static final long SEARCH_DEBOUNCE_MS = 120L;

    private int socialCapGeneration;

    private int lastSocialCount = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);
        applyChrome();
        sharedGap = Ui.carouselGapDecoration(this);

        setupHeader();
        setupCardCarousel();
        setupIdsCarousel();
        setupSocialAccounts();
        setupSearch();
        setupAddMenu();
        bindCachedSnapshot();

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
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_SEARCH_QUERY, currentQuery);
        outState.putBoolean(KEY_SEARCH_OPEN, searchShowing);
        outState.putBoolean(KEY_FAB_MENU_OPEN, isFabMenuOpen);
    }

    @Override
    protected void onDestroy() {
        cancelMenuEntrance();
        clearPendingMeasure(bankCardEmptyState);
        clearPendingMeasure(governmentIdEmptyState);
        clearPendingMeasure(socialAccountList);
        socialCapGeneration++;
        cancelSearch();
        searchExecutor.shutdownNow();
        super.onDestroy();
    }

    private void cancelSearch() {
        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        searchGeneration++;
    }

    private static void clearPendingMeasure(View empty) {
        if (empty != null) {
            Runnable pending = (Runnable) empty.getTag(R.id.tag_empty_state_measure);
            if (pending != null) {
                empty.removeCallbacks(pending);
                empty.setTag(R.id.tag_empty_state_measure, null);
            }
        }
    }

    /** TopAppBar header: search opens SearchView, gear opens Settings. */
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

    /** Full-screen SearchView: typing filters masters into result lists. */
    private void setupSearch() {
        searchView = findViewById(R.id.dashboard_search_view);
        if (searchView == null) {
            return;
        }
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
            if (newState == SearchView.TransitionState.SHOWN) {
                searchShowing = true;
                if (isFabMenuOpen) {
                    toggleAddMenu();
                }
                setFabVisible(false);
                // Re-filter on open (covers rotation restore: the query is
                // set before show, masters load separately).
                scheduleSearch();
            } else if (newState == SearchView.TransitionState.HIDDEN) {
                searchShowing = false;
                setFabVisible(true);
            }
        });
    }

    /** FAB hides while the SearchView covers the screen. */
    private void setFabVisible(boolean visible) {
        quickAddButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Rebuilds the search index from the current masters. Once per refresh. */
    private void rebuildSearchIndex() {
        try {
            searchIndex = DashboardSearch.buildIndex(allIds, allCards, allAccounts);
        } catch (RuntimeException e) {
            Log.w("Dashboard", "rebuildSearchIndex failed", e);
            searchIndex = DashboardSearch.EMPTY_INDEX;
        }
    }

    /** Debounced search entry point (UI thread only). Drops stale generations. */
    private void scheduleSearch() {
        if (searchIdAdapter == null || searchCardAdapter == null || searchSocialAdapter == null) {
            return;
        }
        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
        }
        final int generation = ++searchGeneration;
        final String raw = currentQuery;
        final DashboardSearch.Index snapshot =
                searchIndex != null ? searchIndex : DashboardSearch.EMPTY_INDEX;
        pendingSearch = () -> {
            try {
                searchExecutor.execute(() -> runSearch(generation, raw, snapshot));
            } catch (RuntimeException e) {
                Log.w("Dashboard", "search executor shut down", e);
            }
        };
        searchHandler.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
    }

    /** Background pass: normalize query, scan pre-lowered strings. */
    private void runSearch(int generation, String raw, DashboardSearch.Index snapshot) {
        final DashboardSearch.Query query;
        final DashboardSearch.Result result;
        try {
            query = DashboardSearch.normalizeQuery(raw);
            result = DashboardSearch.search(snapshot, query);
        } catch (RuntimeException e) {
            Log.w("Dashboard", "search failed, keeping previous results", e);
            return;
        }
        searchHandler.post(() -> {
            if (generation != searchGeneration || !isAlive()) {
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

    /** True while any search list is mid-layout. */
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

    /** Extended FAB: round main button expanding the 3-option menu above it. */
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
            if (isFabMenuOpen) {
                quickAddMenu.setVisibility(View.VISIBLE);
                playMenuEntrance();
            } else {
                cancelMenuEntrance();
                quickAddMenu.setVisibility(View.GONE);
            }
        }
        if (quickAddScrim != null) {
            quickAddScrim.setVisibility(isFabMenuOpen ? View.VISIBLE : View.GONE);
        }
        if (quickAddButton != null) {
            quickAddButton.setImageResource(isFabMenuOpen ? R.drawable.ic_close : R.drawable.ic_add);
        }
    }

    /** Staggered fade/rise entrance, top item first. */
    private void playMenuEntrance() {
        if (!(quickAddMenu instanceof ViewGroup)) {
            return;
        }
        ViewGroup menu = (ViewGroup) quickAddMenu;
        float rise = Ui.dp(this, 10);
        DecelerateInterpolator menuInterpolator = new DecelerateInterpolator();
        for (int i = 0; i < menu.getChildCount(); i++) {
            View child = menu.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(rise);
            child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(i * 45L)
                    .setDuration(180L)
                    .setInterpolator(menuInterpolator)
                    .start();
        }
    }

    private void cancelMenuEntrance() {
        if (!(quickAddMenu instanceof ViewGroup)) {
            return;
        }
        ViewGroup menu = (ViewGroup) quickAddMenu;
        for (int i = 0; i < menu.getChildCount(); i++) {
            View child = menu.getChildAt(i);
            child.animate().cancel();
            child.setAlpha(1f);
            child.setTranslationY(0f);
        }
    }

    /** Tap bumps updated_at so the row sorts newest-first on return. */
    private void openSocialEditor(SocialAccountModel item) {
        touchAndOpen(() -> db().touchSocialAccountUpdatedAt(item.getId()),
                SocialAccountActivity.editIntent(this, item));
    }

    /** Opens a creation screen. FAB is the only entry. */
    private void openCreator(Class<?> editorScreen) {
        if (isFabMenuOpen) {
            toggleAddMenu();
        }
        startActivity(new Intent(this, editorScreen));
    }

    /** Tap bumps updated_at so the row sorts newest-first on return. */
    private void openIdEditor(GovernmentIDModel item) {
        touchAndOpen(() -> db().touchIdCardUpdatedAt(item.getId()),
                GovernmentIDActivity.editIntent(this, item));
    }

    /** Tap bumps updated_at so the row sorts newest-first on return. */
    private void openBankEditor(BankCardModel item) {
        touchAndOpen(() -> db().touchBankCardUpdatedAt(item.getId()),
                BankCardActivity.editIntent(this, item));
    }

    private void touchAndOpen(Runnable touch, Intent intent) {
        vaultIo(touch);
        startActivity(intent);
    }

    private void setupHorizontalList(RecyclerView list, RecyclerView.Adapter<?> adapter) {
        list.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        list.setAdapter(adapter);
        list.addItemDecoration(sharedGap);
        list.setHasFixedSize(false);
        list.setItemViewCacheSize(4);
    }

    /** Horizontal snap carousel rendering the user's bank cards. */
    private void setupCardCarousel() {
        bankCardCarousel = findViewById(R.id.dashboard_bank_card_carousel);
        bankCardEmptyState = findViewById(R.id.dashboard_bank_card_empty_state);

        cardAdapter = new BankCardAdapter(this::openBankEditor);
        setupHorizontalList(bankCardCarousel, cardAdapter);
        new PagerSnapHelper().attachToRecyclerView(bankCardCarousel);

        bankCardEmptyState.setOnClickListener(v -> openCreator(BankCardActivity.class));
        findViewById(R.id.dashboard_bank_card_empty_action).setOnClickListener(
                v -> openCreator(BankCardActivity.class));
    }

    private void refreshCardCarousel(List<BankCardModel> cards) {
        if (cardAdapter == null || bankCardCarousel == null) {
            return;
        }
        cardAdapter.updateData(cards);
        boolean hasCards = cards != null && !cards.isEmpty();
        bankCardCarousel.setVisibility(hasCards ? View.VISIBLE : View.GONE);
        if (headerCards != null) {
            headerCards.setVisibility(View.VISIBLE);
        }
        if (bankCardEmptyState != null) {
            bankCardEmptyState.setVisibility(!hasCards ? View.VISIBLE : View.GONE);
            if (!hasCards) {
                matchEmptyHeightToCards(bankCardCarousel, bankCardEmptyState);
            }
        }
    }

    /** Sizes an empty-state card like one carousel page to keep section height. */
    private void matchEmptyHeightToCards(@NonNull RecyclerView carousel, @NonNull View empty) {
        Runnable pending = (Runnable) empty.getTag(R.id.tag_empty_state_measure);
        if (pending != null) {
            empty.removeCallbacks(pending);
        }
        Runnable measure = () -> {
            if (!isAlive()) {
                return;
            }
            int contentWidth = empty.getWidth();
            if (contentWidth <= 0) {
                return;
            }
            int viewport = contentWidth
                    - carousel.getPaddingStart() - carousel.getPaddingEnd();
            if (viewport <= 0) {
                return;
            }
            int pageHeight = (int) ((viewport * Ui.CAROUSEL_PAGE_RATIO - Ui.dp(empty.getContext(), 8))
                    / CARD_ASPECT_RATIO);
            if (pageHeight > 0 && empty.getLayoutParams().height != pageHeight) {
                empty.getLayoutParams().height = pageHeight;
                empty.requestLayout();
            }
        };
        empty.setTag(R.id.tag_empty_state_measure, measure);
        empty.post(measure);
    }

    /** Horizontal snap carousel rendering the user's government IDs. */
    private void setupIdsCarousel() {
        governmentIdCarousel = findViewById(R.id.dashboard_government_id_carousel);
        governmentIdEmptyState = findViewById(R.id.dashboard_government_id_empty_state);

        idAdapter = new GovernmentIdAdapter(this::openIdEditor);
        setupHorizontalList(governmentIdCarousel, idAdapter);
        new PagerSnapHelper().attachToRecyclerView(governmentIdCarousel);

        governmentIdEmptyState.setOnClickListener(v -> openCreator(GovernmentIDActivity.class));
        findViewById(R.id.dashboard_government_id_empty_action).setOnClickListener(
                v -> openCreator(GovernmentIDActivity.class));
    }

    private void refreshIdsCarousel(List<GovernmentIDModel> ids) {
        if (idAdapter == null || governmentIdCarousel == null) {
            return;
        }
        idAdapter.updateData(ids);
        boolean hasIds = ids != null && !ids.isEmpty();
        governmentIdCarousel.setVisibility(hasIds ? View.VISIBLE : View.GONE);
        if (headerIds != null) {
            headerIds.setVisibility(View.VISIBLE);
        }
        if (governmentIdEmptyState != null) {
            governmentIdEmptyState.setVisibility(!hasIds ? View.VISIBLE : View.GONE);
            if (!hasIds) {
                matchEmptyHeightToCards(governmentIdCarousel, governmentIdEmptyState);
            }
        }
    }

    /** Social Account — vertical list of created accounts. */
    private void setupSocialAccounts() {
        socialAccountCard = findViewById(R.id.dashboard_social_account_card);
        socialAccountList = findViewById(R.id.dashboard_social_account_list);
        socialAccountEmptyState = findViewById(R.id.dashboard_social_account_empty_state);

        socialAdapter = new SocialAccountAdapter(this::openSocialEditor);
        socialAccountList.setLayoutManager(new LinearLayoutManager(this));
        socialAccountList.setAdapter(socialAdapter);
        socialAccountList.setHasFixedSize(false);

        socialAccountEmptyState.setOnClickListener(v -> openCreator(SocialAccountActivity.class));
        findViewById(R.id.dashboard_social_account_empty_action).setOnClickListener(
                v -> openCreator(SocialAccountActivity.class));
    }

    /** Binds social rows then adopts-then-caps the height. Defers while mid-layout. */
    private void refreshSocialAccounts(List<SocialAccountModel> accounts) {
        if (socialAdapter == null || socialAccountList == null || !isAlive()) {
            return;
        }
        if (socialAccountList.isComputingLayout()
                || socialAccountList.hasPendingAdapterUpdates()) {
            final List<SocialAccountModel> snapshot =
                    accounts != null ? new ArrayList<>(accounts) : null;
            socialAccountList.post(() -> refreshSocialAccounts(snapshot));
            return;
        }
        socialAdapter.updateData(accounts);
        int count = accounts != null ? accounts.size() : 0;
        boolean hasAccounts = count > 0;
        if (socialAccountCard != null) {
            socialAccountCard.setVisibility(hasAccounts ? View.VISIBLE : View.GONE);
        }
        if (headerSocial != null) {
            headerSocial.setVisibility(View.VISIBLE);
        }
        if (socialAccountEmptyState != null) {
            socialAccountEmptyState.setVisibility(!hasAccounts ? View.VISIBLE : View.GONE);
        }
        if (!hasAccounts) {
            lastSocialCount = 0;
            resetSocialListHeight();
            return;
        }
        if (lastSocialCount >= 0 && count < lastSocialCount) {
            resetSocialListHeight();
        }
        lastSocialCount = count;
        capSocialListToViewport();
    }

    /** Drops any fixed viewport cap back to wrap_content with scrolling off. */
    private void resetSocialListHeight() {
        if (socialAccountList == null) {
            return;
        }
        ViewGroup.LayoutParams params = socialAccountList.getLayoutParams();
        if (params != null
                && params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            socialAccountList.setLayoutParams(params);
        }
        socialAccountList.setNestedScrollingEnabled(false);
    }

    /** Caps social list to viewport; headers stay pinned. */
    private void capSocialListToViewport() {
        final View card = socialAccountCard;
        final RecyclerView list = socialAccountList;
        if (card == null || list == null) {
            return;
        }
        final int generation = ++socialCapGeneration;
        Runnable pending = (Runnable) list.getTag(R.id.tag_empty_state_measure);
        if (pending != null) {
            list.removeCallbacks(pending);
        }
        final Runnable[] self = new Runnable[1];
        self[0] = () -> runSocialCap(generation, self[0]);
        list.setTag(R.id.tag_empty_state_measure, self[0]);
        list.post(self[0]);
    }

    private void runSocialCap(int generation, Runnable retry) {
        if (generation != socialCapGeneration || !isAlive()) {
            return;
        }
        final View card = socialAccountCard;
        final RecyclerView list = socialAccountList;
        if (card == null || list == null) {
            return;
        }
        if (list.isComputingLayout() || list.isAnimating()
                || list.hasPendingAdapterUpdates() || list.isLayoutRequested()) {
            list.post(retry);
            return;
        }
        View content = (View) card.getParent();
        if (content == null) {
            return;
        }
        if (content.getHeight() <= 0 || list.getWidth() <= 0) {
            list.post(retry);
            return;
        }
        ViewGroup.LayoutParams params = list.getLayoutParams();
        boolean capped = params.height != ViewGroup.LayoutParams.WRAP_CONTENT;
        int viewportBottom = content.getHeight() - content.getPaddingBottom();
        if (!capped) {
            if (card.getBottom() <= viewportBottom) {
                list.setNestedScrollingEnabled(false);
                return;
            }
            int remaining = content.getHeight()
                    - card.getTop()
                    - content.getPaddingBottom()
                    - card.getPaddingTop()
                    - card.getPaddingBottom();
            if (remaining <= 0) {
                return;
            }
            params.height = remaining;
            list.setLayoutParams(params);
            list.setNestedScrollingEnabled(true);
            return;
        }
        int remaining = content.getHeight()
                - card.getTop()
                - content.getPaddingBottom()
                - card.getPaddingTop()
                - card.getPaddingBottom();
        if (remaining <= 0) {
            return;
        }
        if (remaining != params.height) {
            resetSocialListHeight();
            list.post(retry);
            return;
        }
        list.setNestedScrollingEnabled(true);
    }

    /** Cache-first bind: preload snapshot before first draw; onResume revalidates. */
    private void bindCachedSnapshot() {
        VaultWarmCache.Snapshot cached = cache().snapshot();
        if (cached == null || !cached.hasActive()) {
            return;
        }
        bindSnapshot(new ArrayList<>(cached.activeIds),
                new ArrayList<>(cached.activeCards),
                new ArrayList<>(cached.activeAccounts));
    }

    private void bindSnapshot(List<GovernmentIDModel> ids, List<BankCardModel> cards,
                              List<SocialAccountModel> accounts) {
        allIds = ids;
        allCards = cards;
        allAccounts = accounts;
        refreshIdsCarousel(allIds);
        refreshCardCarousel(allCards);
        refreshSocialAccounts(allAccounts);
        rebuildSearchIndex();
    }

    private void refreshDashboard() {
        final int generation = nextLoadGeneration();
        vaultIo(() -> {
            final List<GovernmentIDModel> ids;
            final List<BankCardModel> cards;
            final List<SocialAccountModel> accounts;
            try {
                ids = db().getAllIdCards();
                cards = db().getAllBankCards();
                accounts = db().getAllSocialAccounts();
            } catch (RuntimeException e) {
                android.util.Log.w("Dashboard", "refresh failed", e);
                runIfAlive(generation, () -> showMessage(R.string.err_dashboard_load));
                return;
            }
            cache().publishActive(ids, cards, accounts);
            runIfAlive(generation, () -> {
                bindSnapshot(ids, cards, accounts);
                if (searchShowing) {
                    scheduleSearch();
                }
            });
        });
    }
}
