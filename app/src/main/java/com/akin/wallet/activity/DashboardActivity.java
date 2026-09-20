package com.akin.wallet.activity;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.adapter.BankCardAdapter;
import com.akin.wallet.adapter.GovermentIdAdapter;
import com.akin.wallet.adapter.SocialAccountAdapter;
import com.akin.wallet.db.AppDatabaseHelper;
import com.akin.wallet.db.VaultWarmCache;
import com.akin.wallet.security.AppLockManager;
import com.akin.wallet.model.BankCardModel;
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.util.Ui;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.search.SearchView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dashboard (Home) — single-screen host, no fragments. Shows live Government
 * IDs, Bank Cards and Social Accounts from SQLite, plus the quick-add FAB
 * menu. Child screens open as full-screen activities and this refreshes in
 * onResume. (Merged from DashboardFragment: one screen never needed the
 * fragment back stack.)
 */
public class DashboardActivity extends AppCompatActivity {

    private AppDatabaseHelper dbHelper;
    private BankCardAdapter cardAdapter;
    private RecyclerView recyclerCarousel;
    private View emptyCards;
    private GovermentIdAdapter idAdapter;
    private RecyclerView recyclerIdsCarousel;
    private View emptyIds;
    private SocialAccountAdapter socialAdapter;
    private View cardSocialAccounts;
    private RecyclerView recyclerSocialAccounts;
    private View emptySocialAccounts;

    /** Drops stale loads (rotation / rapid resume) — only the latest binds. */
    private int loadGeneration;
    /** One stateless 12dp gap shared by every carousel (never per-item state). */
    private RecyclerView.ItemDecoration sharedGap;

    /** Credit-card ratio shared with the carousel faces (width : height). */
    private static final float CARD_ASPECT_RATIO = 1.586f;
    /** Precompiled: digit extraction runs per card per keystroke while searching. */
    private static final java.util.regex.Pattern NON_DIGITS =
            java.util.regex.Pattern.compile("\\D");
    private FloatingActionButton fabAdd;
    private View fabAddMenu;
    private View fabScrim;
    private boolean isFabMenuOpen = false;

    // M3 Search state. Masters hold the full newest-first rows; the SearchView
    // filters them into its own result lists (the dashboard behind stays whole).
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
    private GovermentIdAdapter searchIdAdapter;
    private RecyclerView recyclerSearchIds;
    private View searchHeaderIds;
    private SocialAccountAdapter searchSocialAdapter;
    private View cardSearchSocial;
    private View searchHeaderSocial;
    private View headerIds;
    private View headerCards;
    private View headerSocial;
    private View emptySearchResults;
    private TextView emptySearchTitle;
    private TextView emptySearchSub;
    private long lastBackgroundAt;

    /**
     * Session re-lock: backing out of the verify screen means "do not enter",
     * so the dashboard closes instead of sitting unlocked behind it.
     */
    private final ActivityResultLauncher<Intent> verifyLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK) {
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);
        setupSystemBars();

        dbHelper = VaultWarmCache.get(this).helper();
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
        Ui.showPendingMessage(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Vault re-lock: cold starts always land here locked, and returning
        // after the app sat in the background past the grace period asks
        // again. Quick trips (editors) stay unlocked.
        if (dbHelper == null || !AppLockManager.isPinSet(this)) {
            return;
        }
        boolean graceExpired = lastBackgroundAt > 0
                && System.currentTimeMillis() - lastBackgroundAt
                > AppLockManager.SESSION_GRACE_MS;
        if (!AppLockManager.isSessionUnlocked() || graceExpired) {
            // Drop row plaintext before the lock screen covers us; the
            // post-auth preload repopulates before we are visible again.
            VaultWarmCache.get(this).clearSensitiveOnLock();
            verifyLauncher.launch(new Intent(this, LockActivity.class)
                    .putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_VERIFY));
        }
    }

    @Override
    protected void onStop() {
        lastBackgroundAt = System.currentTimeMillis();
        super.onStop();
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
        // Vault I/O runs on the shared funnel (never shut down per-screen).
        dbHelper = null;
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            setupSystemBars();
        }
    }

    /**
     * System bars stay visible (no immersive mode): the bottom navigation bar
     * is shown, not hidden. Re-applied on focus in case the system hid it
     * transiently (e.g. after a fullscreen intent returns).
     */
    private void setupSystemBars() {
        Ui.applySystemBars(this);
    }

    /**
     * M3 TopAppBar header: title/subtitle are static in XML; search opens
     * the full-screen SearchView, settings opens Security preferences
     * (biometrics live there). Back closes search first.
     */
    private void setupHeader() {
        headerIds = findViewById(R.id.header_ids);
        headerCards = findViewById(R.id.header_cards);
        headerSocial = findViewById(R.id.header_social);

        View btnSearch = findViewById(R.id.btn_header_search);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> {
                if (searchView != null) {
                    searchView.show();
                }
            });
        }
        View btnSettings = findViewById(R.id.btn_header_settings);
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
        searchView = findViewById(R.id.search_view);
        if (searchView == null) {
            return;
        }

        searchIdAdapter = new GovermentIdAdapter(this::openIdEditor);
        recyclerSearchIds = findViewById(R.id.recycler_search_ids);
        recyclerSearchIds.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        recyclerSearchIds.setAdapter(searchIdAdapter);
        recyclerSearchIds.addItemDecoration(sharedGap);
        searchHeaderIds = findViewById(R.id.search_header_ids);

        searchCardAdapter = new BankCardAdapter(this::openBankEditor);
        recyclerSearchCards = findViewById(R.id.recycler_search_cards);
        recyclerSearchCards.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        recyclerSearchCards.setAdapter(searchCardAdapter);
        recyclerSearchCards.addItemDecoration(sharedGap);
        searchHeaderCards = findViewById(R.id.search_header_cards);

        // Row taps open the account's edit screen, same as the dashboard list.
        searchSocialAdapter = new SocialAccountAdapter(this::openSocialEditor);
        RecyclerView recyclerSearchSocial = findViewById(R.id.recycler_search_social);
        recyclerSearchSocial.setLayoutManager(new LinearLayoutManager(this));
        recyclerSearchSocial.setAdapter(searchSocialAdapter);
        cardSearchSocial = findViewById(R.id.card_search_social);
        searchHeaderSocial = findViewById(R.id.search_header_social);

        emptySearchResults = findViewById(R.id.empty_search_results);
        emptySearchTitle = findViewById(R.id.empty_search_title);
        emptySearchSub = findViewById(R.id.empty_search_sub);

        searchView.getEditText().addTextChangedListener(new Ui.SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                currentQuery = text != null ? text.toString() : "";
                updateSearchResults();
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
                updateSearchResults();
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
     * Filters the master lists into the SearchView results. Empty query shows
     * everything newest-first; non-empty collapses empty sections and shows
     * one global "no results" card when nothing matches anywhere.
     */
    private void updateSearchResults() {
        if (allIds == null || allCards == null || allAccounts == null
                || searchIdAdapter == null || searchCardAdapter == null
                || searchSocialAdapter == null) {
            // Pre-load: search sections default to gone in XML; the first
            // bind restores them.
            return;
        }
        String query = currentQuery.trim().toLowerCase(Locale.US);
        if (query.isEmpty()) {
            bindSearchResults(allIds, allCards, allAccounts, false);
            return;
        }
        String digits = NON_DIGITS.matcher(query).replaceAll("");
        bindSearchResults(filterIds(allIds, query), filterCards(allCards, query, digits),
                filterAccounts(allAccounts, query), true);
    }

    private void bindSearchResults(List<GovernmentIDModel> ids, List<BankCardModel> cards,
                                    List<SocialAccountModel> accounts, boolean searching) {
        searchIdAdapter.updateData(ids);
        boolean hasIds = ids != null && !ids.isEmpty();
        recyclerSearchIds.setVisibility(hasIds ? View.VISIBLE : View.GONE);
        searchHeaderIds.setVisibility(hasIds ? View.VISIBLE : View.GONE);

        searchCardAdapter.updateData(cards);
        boolean hasCards = cards != null && !cards.isEmpty();
        recyclerSearchCards.setVisibility(hasCards ? View.VISIBLE : View.GONE);
        searchHeaderCards.setVisibility(hasCards ? View.VISIBLE : View.GONE);

        searchSocialAdapter.updateData(accounts);
        boolean hasAccounts = accounts != null && !accounts.isEmpty();
        cardSearchSocial.setVisibility(hasAccounts ? View.VISIBLE : View.GONE);
        searchHeaderSocial.setVisibility(hasAccounts ? View.VISIBLE : View.GONE);

        boolean allEmpty = !hasIds && !hasCards && !hasAccounts;
        emptySearchResults.setVisibility(allEmpty ? View.VISIBLE : View.GONE);
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

    private static List<GovernmentIDModel> filterIds(List<GovernmentIDModel> source, String query) {
        List<GovernmentIDModel> out = new ArrayList<>();
        for (GovernmentIDModel item : source) {
            if (containsText(item.getIdType(), query) || idFieldsContain(item, query)) {
                out.add(item);
            }
        }
        return out;
    }

    private static List<BankCardModel> filterCards(
            List<BankCardModel> source, String query, String digits) {
        List<BankCardModel> out = new ArrayList<>();
        for (BankCardModel item : source) {
            // Non-secret fields only: card number matches on digits so "1234"
            // finds "•••• •••• •••• 1234". CVV/PIN are never matched.
            if (containsText(item.getBankName(), query)
                    || containsText(item.getHolderName(), query)
                    || containsText(item.getCardType(), query)
                    || containsText(item.getCardNetwork(), query)
                    || cardNumberContains(item, digits)) {
                out.add(item);
            }
        }
        return out;
    }

    private static List<SocialAccountModel> filterAccounts(List<SocialAccountModel> source, String query) {
        List<SocialAccountModel> out = new ArrayList<>();
        for (SocialAccountModel item : source) {
            // Non-secret fields only: password/PIN stay out of the index.
            if (containsText(item.getPlatform(), query)
                    || containsText(item.getUsername(), query)) {
                out.add(item);
            }
        }
        return out;
    }

    private static boolean containsText(String value, String query) {
        return value != null && !value.trim().isEmpty()
                && value.toLowerCase(Locale.US).contains(query);
    }

    private static boolean idFieldsContain(@NonNull GovernmentIDModel id, String query) {
        Map<String, String> fields = id.getFields();
        for (String value : fields.values()) {
            if (containsText(value, query)) {
                return true;
            }
        }
        return false;
    }

    /** Card numbers are raw digits; match on digits only (CVV/PIN excluded). */
    private static boolean cardNumberContains(@NonNull BankCardModel card, String digits) {
        if (digits.isEmpty() || card.getCardNumber() == null) {
            return false;
        }
        String numberDigits = NON_DIGITS.matcher(card.getCardNumber()).replaceAll("");
        return !numberDigits.isEmpty() && numberDigits.contains(digits);
    }

    /** Extended FAB: round main button expanding the 3-option menu above it. */
    private void setupAddMenu() {
        fabAdd = findViewById(R.id.fab_add);
        fabAddMenu = findViewById(R.id.fab_add_menu);
        fabScrim = findViewById(R.id.fab_scrim);
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
        setMenuOption(R.id.fab_option_id, this::openIdCreator);
        setMenuOption(R.id.fab_option_card, this::openBankCreator);
        setMenuOption(R.id.fab_option_account, this::openSocialCreator);
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
        final AppDatabaseHelper db = dbHelper;
        if (db != null) {
            VaultWarmCache.get(this).executeVaultIo(() -> db.touchSocialAccountUpdatedAt(id));
        }
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
        final AppDatabaseHelper db = dbHelper;
        if (db != null) {
            VaultWarmCache.get(this).executeVaultIo(() -> db.touchIdCardUpdatedAt(id));
        }
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
        final AppDatabaseHelper db = dbHelper;
        if (db != null) {
            VaultWarmCache.get(this).executeVaultIo(() -> db.touchBankCardUpdatedAt(id));
        }
        startActivity(BankCardActivity.editIntent(this, item));
    }

    /** Opens the account creation screen directly (dashboard rows open the editor). */
    private void openSocialCreator() {
        openCreator(SocialAccountActivity.class);
    }

    /** Horizontal snap carousel rendering the user's real bank cards. */
    private void setupCardCarousel() {
        recyclerCarousel = findViewById(R.id.recycler_cards_carousel);
        emptyCards = findViewById(R.id.empty_cards);

        cardAdapter = new BankCardAdapter(this::openBankEditor);
        recyclerCarousel.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        recyclerCarousel.setAdapter(cardAdapter);
        recyclerCarousel.addItemDecoration(sharedGap);
        new PagerSnapHelper().attachToRecyclerView(recyclerCarousel);

        if (emptyCards != null) {
            emptyCards.setOnClickListener(v -> openBankCreator());
        }
        View btnEmptyCards = findViewById(R.id.btn_empty_cards);
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
        recyclerIdsCarousel = findViewById(R.id.recycler_ids_carousel);
        emptyIds = findViewById(R.id.empty_ids);

        idAdapter = new GovermentIdAdapter(this::openIdEditor);
        recyclerIdsCarousel.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        recyclerIdsCarousel.setAdapter(idAdapter);
        recyclerIdsCarousel.addItemDecoration(sharedGap);
        new PagerSnapHelper().attachToRecyclerView(recyclerIdsCarousel);

        if (emptyIds != null) {
            emptyIds.setOnClickListener(v -> openIdCreator());
        }
        View btnEmptyIds = findViewById(R.id.btn_empty_ids);
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
        cardSocialAccounts = findViewById(R.id.card_social_accounts);
        recyclerSocialAccounts = findViewById(R.id.recycler_social_accounts);
        emptySocialAccounts = findViewById(R.id.empty_social_accounts);

        // Row taps open the account's edit screen, same as the IDs and cards above.
        socialAdapter = new SocialAccountAdapter(this::openSocialEditor);
        recyclerSocialAccounts.setLayoutManager(new LinearLayoutManager(this));
        recyclerSocialAccounts.setAdapter(socialAdapter);

        if (emptySocialAccounts != null) {
            emptySocialAccounts.setOnClickListener(v -> openSocialCreator());
        }
        View btnEmptySocial = findViewById(R.id.btn_empty_social);
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
    }

    /**
     * Cache-first bind: when the post-auth preload already ran (the common
     * unlock path), the carousels and search masters bind synchronously
     * before first draw — no header-only flash. Falls back to async load
     * on miss; {@link #refreshDashboard()} in {@code onResume} always
     * revalidates afterward.
     */
    private void bindCachedSnapshot() {
        VaultWarmCache.Snapshot cached = VaultWarmCache.get(this).snapshot();
        if (cached == null || !cached.hasActive()) {
            return;
        }
        allIds = new ArrayList<>(cached.activeIds);
        allCards = new ArrayList<>(cached.activeCards);
        allAccounts = new ArrayList<>(cached.activeAccounts);
        refreshIdsCarousel(allIds);
        refreshCardCarousel(allCards);
        refreshSocialAccounts(allAccounts);
    }

    private void refreshDashboard() {
        final AppDatabaseHelper db = dbHelper;
        if (db == null) {
            return;
        }
        // Vault reads ride the shared funnel (ordered with warm-up/preload);
        // only the latest generation binds, so rotation/rapid resume cannot
        // show stale rows or touch a dead activity.
        final int generation = ++loadGeneration;
        VaultWarmCache.get(this).executeVaultIo(() -> {
            final List<GovernmentIDModel> ids = db.getAllIdCards();
            final List<BankCardModel> cards = db.getAllBankCards();
            final List<SocialAccountModel> accounts = db.getAllSocialAccounts();
            VaultWarmCache.get(DashboardActivity.this)
                    .publishActive(ids, cards, accounts);
            runOnUiThread(() -> {
                if (generation != loadGeneration || isFinishing()) {
                    return;
                }
                allIds = ids;
                allCards = cards;
                allAccounts = accounts;

                refreshIdsCarousel(allIds);
                refreshCardCarousel(allCards);
                refreshSocialAccounts(allAccounts);

                // Editors close back here: re-filter open results off fresh masters.
                if (searchShowing) {
                    updateSearchResults();
                }
            });
        });
    }
}
