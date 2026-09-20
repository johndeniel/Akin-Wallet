package com.akin.wallet.activity;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
 * Dashboard (Home) — single-screen host, no fragments. Shows live Government
 * IDs, Bank Cards and Social Accounts from SQLite, plus the quick-add FAB
 * menu. Child screens open as full-screen activities and this refreshes in
 * onResume. (Merged from DashboardFragment: one screen never needed the
 * fragment back stack.)
 */
public class DashboardActivity extends BaseVaultActivity {

    private BankCardAdapter cardAdapter;
    private RecyclerView recyclerCarousel;
    private View emptyCards;
    private GovernmentIdAdapter idAdapter;
    private RecyclerView recyclerIdsCarousel;
    private View emptyIds;
    private SocialAccountAdapter socialAdapter;
    private View cardSocialAccounts;
    private RecyclerView recyclerSocialAccounts;
    private View emptySocialAccounts;

    /** One stateless 12dp gap shared by every carousel (never per-item state). */
    private RecyclerView.ItemDecoration sharedGap;

    /** Credit-card ratio shared with the carousel faces (width : height). */
    private static final float CARD_ASPECT_RATIO = 1.586f;
    private FloatingActionButton fabAdd;
    private View fabAddMenu;
    private View fabScrim;
    private boolean isFabMenuOpen = false;

    // M3 Search state. Masters hold the full newest-first rows; a pre-lowered
    // DashboardSearch.Index snapshot filters them off the UI thread into the
    // SearchView result lists (the dashboard behind stays whole).
    private List<GovernmentIDModel> allIds;
    private List<BankCardModel> allCards;
    private List<SocialAccountModel> allAccounts;
    private String currentQuery = "";
    private static final String KEY_SEARCH_QUERY = "dashboard_search_query";
    private static final String KEY_SEARCH_OPEN = "dashboard_search_open";
    private SearchView searchView;
    private boolean searchShowing = false;
    private BankCardAdapter searchCardAdapter;
    private RecyclerView recyclerSearchCards;
    private View searchHeaderCards;
    private GovernmentIdAdapter searchIdAdapter;
    private RecyclerView recyclerSearchIds;
    private View searchHeaderIds;
    private SocialAccountAdapter searchSocialAdapter;
    private RecyclerView recyclerSearchSocial;
    private View cardSearchSocial;
    private View searchHeaderSocial;
    private View headerIds;
    private View headerCards;
    private View headerSocial;
    private View emptySearchResults;
    private TextView emptySearchTitle;
    private TextView emptySearchSub;

    /**
     * Search pipeline: masters are normalized once per refresh into
     * {@link #searchIndex}; each keystroke scans pre-lowered strings on
     * {@link #searchExecutor}. Single thread = ordered, generation drops
     * stale results, 120ms debounce collapses fast typing.
     */
    private DashboardSearch.Index searchIndex = DashboardSearch.EMPTY_INDEX;
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private int searchGeneration;
    private static final long SEARCH_DEBOUNCE_MS = 120L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);
        applyChrome();
        sharedGap = gapDecoration();

        setupHeader();
        setupCardCarousel();
        setupIdsCarousel();
        setupSocialAccounts();
        setupSearch();
        setupAddMenu();
        bindCachedSnapshot();

        if (savedInstanceState != null) {
            // Rotation: restore the query and re-open the SearchView exactly
            // as left (masters load async in onResume, which re-filters into
            // the results).
            currentQuery = savedInstanceState.getString(KEY_SEARCH_QUERY, "");
            boolean open = savedInstanceState.getBoolean(KEY_SEARCH_OPEN, false);
            if (open && searchView != null) {
                if (!currentQuery.isEmpty()) {
                    searchView.getEditText().setText(currentQuery);
                }
                setFabVisible(false);
                searchView.post(() -> searchView.show());
            }
        }
        // No refresh here: onResume always follows onCreate and owns loading.
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
    }

    @Override
    protected void onDestroy() {
        // Animators hold child views; cancel so a mid-entrance finish cannot leak them.
        cancelMenuEntrance();
        // Drop pending empty-state measures that capture views (activity).
        clearPendingMeasure(emptyCards);
        clearPendingMeasure(emptyIds);
        // Search: drop debounced + in-flight work so no callback touches a
        // dead activity, then stop the single search thread.
        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        searchGeneration++;
        searchExecutor.shutdownNow();
        super.onDestroy();
    }

    private static void clearPendingMeasure(View empty) {
        if (empty != null) {
            Runnable pending = (Runnable) empty.getTag(R.id.tag_measure);
            if (pending != null) {
                empty.removeCallbacks(pending);
                empty.setTag(R.id.tag_measure, null);
            }
        }
    }

    /**
     * M3 TopAppBar header: title/subtitle are static in XML; search opens
     * the full-screen SearchView, settings opens Security preferences
     * (biometrics live there). Back closes search first.
     */
    private void setupHeader() {
        headerIds = findViewById(R.id.dashboard_header_ids);
        headerCards = findViewById(R.id.dashboard_header_cards);
        headerSocial = findViewById(R.id.dashboard_header_social);

        View btnSearch = findViewById(R.id.dashboard_header_search_button);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> {
                if (searchView != null) {
                    searchView.show();
                }
            });
        }
        View btnSettings = findViewById(R.id.dashboard_header_settings_button);
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
     * M3 Search: full-screen SearchView (toolbar back + field + clear built
     * in), opened from the header search IconButton. Typing filters the
     * master lists into its own result lists while the dashboard behind
     * stays whole. The FAB hides while results cover it.
     */
    private void setupSearch() {
        searchView = findViewById(R.id.dashboard_search_view);
        if (searchView == null) {
            return;
        }
        // Each search list keeps its own RecycledViewPool (the default).
        // IDs and cards inflate different layouts under the same viewType 0,
        // so sharing one pool recycled a bank-card view into the ID carousel
        // (and vice versa) on the 2nd search -> ClassCastException mid-layout.
        searchIdAdapter = new GovernmentIdAdapter(this::openIdEditor);
        recyclerSearchIds = findViewById(R.id.dashboard_search_ids_list);
        recyclerSearchIds.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        recyclerSearchIds.setAdapter(searchIdAdapter);
        recyclerSearchIds.addItemDecoration(sharedGap);
        recyclerSearchIds.setHasFixedSize(true);
        recyclerSearchIds.setItemViewCacheSize(4);
        searchHeaderIds = findViewById(R.id.dashboard_search_header_ids);

        searchCardAdapter = new BankCardAdapter(this::openBankEditor);
        recyclerSearchCards = findViewById(R.id.dashboard_search_cards_list);
        recyclerSearchCards.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        recyclerSearchCards.setAdapter(searchCardAdapter);
        recyclerSearchCards.addItemDecoration(sharedGap);
        recyclerSearchCards.setHasFixedSize(true);
        recyclerSearchCards.setItemViewCacheSize(4);
        searchHeaderCards = findViewById(R.id.dashboard_search_header_cards);

        // Row taps open the account's edit screen, same as the dashboard list.
        searchSocialAdapter = new SocialAccountAdapter(this::openSocialEditor);
        recyclerSearchSocial = findViewById(R.id.dashboard_search_social_list);
        recyclerSearchSocial.setLayoutManager(new LinearLayoutManager(this));
        recyclerSearchSocial.setAdapter(searchSocialAdapter);
        // Same wrap_content vertical list contract as the dashboard list.
        recyclerSearchSocial.setHasFixedSize(false);
        cardSearchSocial = findViewById(R.id.dashboard_search_social_card);
        searchHeaderSocial = findViewById(R.id.dashboard_search_header_social);

        emptySearchResults = findViewById(R.id.dashboard_search_empty_state);
        emptySearchTitle = findViewById(R.id.dashboard_search_empty_title);
        emptySearchSub = findViewById(R.id.dashboard_search_empty_subtitle);

        searchView.getEditText().addTextChangedListener(new Ui.SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                currentQuery = text != null ? text.toString() : "";
                // Cheap on UI: just re-schedule. Normalize + scan run on the
                // background search thread (see scheduleSearch).
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

    /** 12dp inter-card gap shared by the dashboard and search carousels. */
    private RecyclerView.ItemDecoration gapDecoration() {
        return new RecyclerView.ItemDecoration() {
            // Offset is a constant: computed once, not per child per layout.
            private int cachedGap = -1;

            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View child,
                                       @NonNull RecyclerView parent,
                                       @NonNull RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(child);
                if (position != RecyclerView.NO_POSITION
                        && position < state.getItemCount() - 1) {
                    if (cachedGap < 0) {
                        cachedGap = Ui.dp(parent.getContext(), 12);
                    }
                    outRect.right = cachedGap;
                }
            }
        };
    }

    /** FAB hides while the SearchView covers the screen. */
    private void setFabVisible(boolean visible) {
        if (fabAdd != null) {
            fabAdd.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Rebuilds the pre-lowered search index from the current masters.
     * Called once per masters refresh, never per keystroke.
     */
    private void rebuildSearchIndex() {
        try {
            searchIndex = DashboardSearch.buildIndex(allIds, allCards, allAccounts);
        } catch (RuntimeException e) {
            searchIndex = DashboardSearch.EMPTY_INDEX;
        }
    }

    /**
     * Debounced search entry point (UI thread only). Each schedule bumps the
     * generation so in-flight passes with an older generation are discarded.
     */
    private void scheduleSearch() {
        if (searchIdAdapter == null || searchCardAdapter == null || searchSocialAdapter == null) {
            // Pre-load: search sections default to gone in XML; the first
            // bind restores them.
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
                // Executor shut down (activity destroyed): nothing to bind.
            }
        };
        searchHandler.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
    }

    /**
     * Background pass: normalize only the query, scan pre-lowered strings.
     * Binds on the UI thread only if still current and the activity is alive.
     */
    private void runSearch(int generation, String raw, DashboardSearch.Index snapshot) {
        final DashboardSearch.Query query;
        final DashboardSearch.Result result;
        try {
            query = DashboardSearch.normalizeQuery(raw);
            result = DashboardSearch.search(snapshot, query);
        } catch (RuntimeException e) {
            // Defensive: search must never crash the app. Keep previous
            // results visible; next keystroke retries.
            return;
        }
        searchHandler.post(() -> {
            if (generation != searchGeneration || isFinishing() || isDestroyed()) {
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

    /** True while any search list is mid-layout (a diff's animations still running). */
    private boolean isAnySearchListLayingOut() {
        return (recyclerSearchIds != null && recyclerSearchIds.isComputingLayout())
                || (recyclerSearchCards != null && recyclerSearchCards.isComputingLayout())
                || (recyclerSearchSocial != null && recyclerSearchSocial.isComputingLayout());
    }

    private void bindSearchResults(List<GovernmentIDModel> ids, List<BankCardModel> cards,
                                    List<SocialAccountModel> accounts, boolean searching, int generation) {
        if (isFinishing() || isDestroyed() || generation != searchGeneration) {
            return;
        }
        // Never dispatch into a running layout pass; re-queue behind it.
        // The generation check above drops this if a newer keystroke won.
        if (isAnySearchListLayingOut()) {
            searchHandler.post(() ->
                    bindSearchResults(ids, cards, accounts, searching, generation));
            return;
        }
        // Adapters roll back on rejected dispatch, so bind every section:
        // no mid-bind early return that leaves half-updated lists behind.
        searchIdAdapter.updateData(ids);
        boolean hasIds = ids != null && !ids.isEmpty();
        if (recyclerSearchIds != null) {
            recyclerSearchIds.setVisibility(hasIds ? View.VISIBLE : View.GONE);
        }
        if (searchHeaderIds != null) {
            searchHeaderIds.setVisibility(hasIds ? View.VISIBLE : View.GONE);
        }

        searchCardAdapter.updateData(cards);
        boolean hasCards = cards != null && !cards.isEmpty();
        if (recyclerSearchCards != null) {
            recyclerSearchCards.setVisibility(hasCards ? View.VISIBLE : View.GONE);
        }
        if (searchHeaderCards != null) {
            searchHeaderCards.setVisibility(hasCards ? View.VISIBLE : View.GONE);
        }

        searchSocialAdapter.updateData(accounts);
        boolean hasAccounts = accounts != null && !accounts.isEmpty();
        if (cardSearchSocial != null) {
            cardSearchSocial.setVisibility(hasAccounts ? View.VISIBLE : View.GONE);
        }
        if (searchHeaderSocial != null) {
            searchHeaderSocial.setVisibility(hasAccounts ? View.VISIBLE : View.GONE);
        }

        boolean allEmpty = !hasIds && !hasCards && !hasAccounts;
        if (emptySearchResults != null) {
            emptySearchResults.setVisibility(allEmpty ? View.VISIBLE : View.GONE);
        }
        if (allEmpty) {
            // One card, two jobs: a dead-end query keeps the no-results
            // wording, while a genuinely empty vault gets its own invite.
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

    /** Extended FAB: round main button expanding the 3-option menu above it. */
    private void setupAddMenu() {
        fabAdd = findViewById(R.id.dashboard_fab_add);
        fabAddMenu = findViewById(R.id.dashboard_fab_menu);
        fabScrim = findViewById(R.id.dashboard_fab_scrim);
        if (fabAdd == null) {
            return;
        }
        fabAdd.setOnClickListener(v -> toggleAddMenu());
        if (fabScrim != null) {
            fabScrim.setOnClickListener(v -> {
                if (isFabMenuOpen) {
                    toggleAddMenu();
                }
            });
        }
        setMenuOption(R.id.dashboard_fab_option_id, this::openIdCreator);
        setMenuOption(R.id.dashboard_fab_option_card, this::openBankCreator);
        setMenuOption(R.id.dashboard_fab_option_account, this::openSocialCreator);
    }

    private void setMenuOption(int viewId, Runnable action) {
        View option = findViewById(viewId);
        if (option != null) {
            option.setOnClickListener(v -> action.run());
        }
    }

    private void toggleAddMenu() {
        isFabMenuOpen = !isFabMenuOpen;
        if (fabAddMenu != null) {
            if (isFabMenuOpen) {
                fabAddMenu.setVisibility(View.VISIBLE);
                playMenuEntrance();
            } else {
                cancelMenuEntrance();
                fabAddMenu.setVisibility(View.GONE);
            }
        }
        if (fabScrim != null) {
            fabScrim.setVisibility(isFabMenuOpen ? View.VISIBLE : View.GONE);
        }
        if (fabAdd != null) {
            fabAdd.setImageResource(isFabMenuOpen ? R.drawable.ic_close : R.drawable.ic_add);
        }
    }

    /** Staggered fade/rise entrance, top item first (M3 FAB menu motion). */
    private void playMenuEntrance() {
        if (!(fabAddMenu instanceof ViewGroup)) {
            return;
        }
        ViewGroup menu = (ViewGroup) fabAddMenu;
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
        if (!(fabAddMenu instanceof ViewGroup)) {
            return;
        }
        ViewGroup menu = (ViewGroup) fabAddMenu;
        for (int i = 0; i < menu.getChildCount(); i++) {
            View child = menu.getChildAt(i);
            child.animate().cancel();
            child.setAlpha(1f);
            child.setTranslationY(0f);
        }
    }

    /** Opens a social edit screen directly (dashboard rows open the editor). */
    private void openSocialEditor(SocialAccountModel item) {
        // Recency bump rides the I/O thread; navigation never waits for it.
        final int id = item.getId();
        vaultIo(() -> db().touchSocialAccountUpdatedAt(id));
        startActivity(SocialAccountActivity.editIntent(this, item));
    }

    /** Opens a creation screen directly (FAB is the only entry). */
    private void openCreator(Class<?> editorScreen) {
        if (isFabMenuOpen) {
            toggleAddMenu();
        }
        startActivity(new Intent(this, editorScreen));
    }

    /** Opens the ID creation screen directly (FAB is the only entry). */
    private void openIdCreator() {
        openCreator(GovernmentIDActivity.class);
    }

    /**
     * Opens an ID edit screen directly (dashboard is the editor). The tap itself
     * is a recency signal: updated_at is bumped first so the ID sorts
     * newest-first on return — mirroring the bank-card and account open paths.
     * No immediate refresh here; onResume re-queries after the editor closes.
     */
    private void openIdEditor(GovernmentIDModel item) {
        final int id = item.getId();
        vaultIo(() -> db().touchIdCardUpdatedAt(id));
        startActivity(GovernmentIDActivity.editIntent(this, item));
    }

    /** Opens the bank creation screen directly (FAB is the only entry). */
    private void openBankCreator() {
        openCreator(BankCardActivity.class);
    }

    /**
     * Opens a bank card for editing. The tap itself is a recency signal: the
     * card's updated_at is bumped first so it sorts newest-first when the
     * list refreshes on return — even if the edit is canceled. No immediate
     * refresh here; onResume already re-queries after the editor closes.
     */
    private void openBankEditor(BankCardModel item) {
        final int id = item.getId();
        vaultIo(() -> db().touchBankCardUpdatedAt(id));
        startActivity(BankCardActivity.editIntent(this, item));
    }

    /** Opens the account creation screen directly (dashboard rows open the editor). */
    private void openSocialCreator() {
        openCreator(SocialAccountActivity.class);
    }

    /** Horizontal snap carousel rendering the user's real bank cards. */
    private void setupCardCarousel() {
        recyclerCarousel = findViewById(R.id.dashboard_cards_carousel);
        emptyCards = findViewById(R.id.dashboard_cards_empty_state);

        cardAdapter = new BankCardAdapter(this::openBankEditor);
        recyclerCarousel.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        recyclerCarousel.setAdapter(cardAdapter);
        recyclerCarousel.addItemDecoration(sharedGap);
        recyclerCarousel.setHasFixedSize(true);
        recyclerCarousel.setItemViewCacheSize(4);
        new PagerSnapHelper().attachToRecyclerView(recyclerCarousel);

        if (emptyCards != null) {
            emptyCards.setOnClickListener(v -> openBankCreator());
        }
        View btnEmptyCards = findViewById(R.id.dashboard_cards_empty_action);
        if (btnEmptyCards != null) {
            btnEmptyCards.setOnClickListener(v -> openBankCreator());
        }
    }

    private void refreshCardCarousel(List<BankCardModel> cards) {
        if (cardAdapter == null || recyclerCarousel == null) {
            return;
        }
        cardAdapter.updateData(cards);
        boolean hasCards = cards != null && !cards.isEmpty();
        recyclerCarousel.setVisibility(hasCards ? View.VISIBLE : View.GONE);
        if (headerCards != null) {
            headerCards.setVisibility(View.VISIBLE);
        }
        if (emptyCards != null) {
            emptyCards.setVisibility(!hasCards ? View.VISIBLE : View.GONE);
            if (!hasCards) {
                matchEmptyHeightToCards(recyclerCarousel, emptyCards);
            }
        }
    }

    /**
     * Sizes an empty-state card exactly like one carousel page (0.68 viewport
     * width at 1.586:1) so the section keeps its height with no data.
     * Measures the visible empty card itself — the carousel is GONE here and
     * always measures zero.
     */
    private void matchEmptyHeightToCards(@NonNull RecyclerView carousel, @NonNull View empty) {
        // One pending measure per view: a second refresh supersedes the first
        // instead of stacking posts that outlive the screen.
        Runnable pending = (Runnable) empty.getTag(R.id.tag_measure);
        if (pending != null) {
            empty.removeCallbacks(pending);
        }
        Runnable measure = () -> {
            if (isFinishing()) {
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
            int pageHeight = (int) ((viewport * BankCardAdapter.PAGE_WIDTH_RATIO - Ui.dp(empty.getContext(), 8))
                    / CARD_ASPECT_RATIO);
            if (pageHeight > 0 && empty.getLayoutParams().height != pageHeight) {
                empty.getLayoutParams().height = pageHeight;
                empty.requestLayout();
            }
        };
        empty.setTag(R.id.tag_measure, measure);
        empty.post(measure);
    }

    /** Horizontal snap carousel rendering the user's real government IDs. */
    private void setupIdsCarousel() {
        recyclerIdsCarousel = findViewById(R.id.dashboard_ids_carousel);
        emptyIds = findViewById(R.id.dashboard_ids_empty_state);

        idAdapter = new GovernmentIdAdapter(this::openIdEditor);
        recyclerIdsCarousel.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        recyclerIdsCarousel.setAdapter(idAdapter);
        recyclerIdsCarousel.addItemDecoration(sharedGap);
        recyclerIdsCarousel.setHasFixedSize(true);
        recyclerIdsCarousel.setItemViewCacheSize(4);
        new PagerSnapHelper().attachToRecyclerView(recyclerIdsCarousel);

        if (emptyIds != null) {
            emptyIds.setOnClickListener(v -> openIdCreator());
        }
        View btnEmptyIds = findViewById(R.id.dashboard_ids_empty_action);
        if (btnEmptyIds != null) {
            btnEmptyIds.setOnClickListener(v -> openIdCreator());
        }
    }

    private void refreshIdsCarousel(List<GovernmentIDModel> ids) {
        if (idAdapter == null || recyclerIdsCarousel == null) {
            return;
        }
        idAdapter.updateData(ids);
        boolean hasIds = ids != null && !ids.isEmpty();
        recyclerIdsCarousel.setVisibility(hasIds ? View.VISIBLE : View.GONE);
        if (headerIds != null) {
            headerIds.setVisibility(View.VISIBLE);
        }
        if (emptyIds != null) {
            emptyIds.setVisibility(!hasIds ? View.VISIBLE : View.GONE);
            if (!hasIds) {
                matchEmptyHeightToCards(recyclerIdsCarousel, emptyIds);
            }
        }
    }

    /** Social Account — vertical list of created social accounts only. */
    private void setupSocialAccounts() {
        cardSocialAccounts = findViewById(R.id.dashboard_social_card);
        recyclerSocialAccounts = findViewById(R.id.dashboard_social_list);
        emptySocialAccounts = findViewById(R.id.dashboard_social_empty_state);

        // Row taps open the account's edit screen, same as the IDs and cards above.
        socialAdapter = new SocialAccountAdapter(this::openSocialEditor);
        recyclerSocialAccounts.setLayoutManager(new LinearLayoutManager(this));
        recyclerSocialAccounts.setAdapter(socialAdapter);
        // Vertical wrap_content list: height = f(item count), so it must
        // re-measure on every adapter change (delete/restore/add). Fixed
        // size would freeze the old height until activity recreation.
        recyclerSocialAccounts.setHasFixedSize(false);

        if (emptySocialAccounts != null) {
            emptySocialAccounts.setOnClickListener(v -> openSocialCreator());
        }
        View btnEmptySocial = findViewById(R.id.dashboard_social_empty_action);
        if (btnEmptySocial != null) {
            btnEmptySocial.setOnClickListener(v -> openSocialCreator());
        }
    }

    private void refreshSocialAccounts(List<SocialAccountModel> accounts) {
        if (socialAdapter == null || recyclerSocialAccounts == null) {
            return;
        }
        socialAdapter.updateData(accounts);
        boolean hasAccounts = accounts != null && !accounts.isEmpty();
        if (cardSocialAccounts != null) {
            cardSocialAccounts.setVisibility(hasAccounts ? View.VISIBLE : View.GONE);
        }
        if (headerSocial != null) {
            headerSocial.setVisibility(View.VISIBLE);
        }
        if (emptySocialAccounts != null) {
            emptySocialAccounts.setVisibility(!hasAccounts ? View.VISIBLE : View.GONE);
        }
        // Force a new measure pass so the wrap_content card adopts the new
        // row count immediately (N-1 delete, 0-1 restore, N+1 add) instead of
        // keeping the previously measured height until recreation.
        recyclerSocialAccounts.requestLayout();
        if (cardSocialAccounts != null) {
            cardSocialAccounts.requestLayout();
        }
    }

    /**
     * Cache-first bind: when the post-auth preload already ran (the common
     * unlock path), the carousels and search masters bind synchronously
     * before first draw — no header-only flash. Falls back to async load
     * on miss; {@link #refreshDashboard()} in {@code onResume} always
     * revalidates afterward.
     */
    private void bindCachedSnapshot() {
        com.akin.wallet.db.VaultWarmCache.Snapshot cached = cache().snapshot();
        if (cached == null || !cached.hasActive()) {
            return;
        }
        allIds = new ArrayList<>(cached.activeIds);
        allCards = new ArrayList<>(cached.activeCards);
        allAccounts = new ArrayList<>(cached.activeAccounts);
        refreshIdsCarousel(allIds);
        refreshCardCarousel(allCards);
        refreshSocialAccounts(allAccounts);
        // Masters changed -> index once so keystrokes scan pre-lowered data.
        rebuildSearchIndex();
    }

    private void refreshDashboard() {
        // Vault reads ride the shared funnel (ordered with warm-up/preload);
        // only the latest generation binds, so rotation/rapid resume cannot
        // show stale rows or touch a dead activity.
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
                // Mirror TrashActivity.loadTrash: never leave the screen
                // blank-and-silent. Keep the previous rows; the next onResume
                // retries the load.
                runOnUiThread(() -> {
                    if (!isCurrentGeneration(generation) || isFinishing()) {
                        return;
                    }
                    showError(R.string.err_dashboard_load);
                });
                return;
            }
            cache().publishActive(ids, cards, accounts);
            runOnUiThread(() -> {
                if (!isCurrentGeneration(generation) || isFinishing()) {
                    return;
                }
                allIds = ids;
                allCards = cards;
                allAccounts = accounts;

                refreshIdsCarousel(allIds);
                refreshCardCarousel(allCards);
                refreshSocialAccounts(allAccounts);

                // Masters changed -> re-index once; open search re-filters
                // off fresh masters through the debounced background path.
                rebuildSearchIndex();
                // Editors close back here: re-filter open results off fresh masters.
                if (searchShowing) {
                    scheduleSearch();
                }
            });
        });
    }
}
