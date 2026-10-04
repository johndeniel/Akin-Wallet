package com.akin.wallet.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;

import com.akin.wallet.R;
import com.akin.wallet.adapter.SocialAccountAdapter;
import com.akin.wallet.model.SocialAccountModel;

import org.junit.Test;
import org.junit.Before;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Measures production XML and adapters using synthetic records only. */
public class SocialContainerTest {
    private final int[][] sizes = {{320, 480}, {320, 568}, {360, 640}, {411, 891}, {480, 960}};

    @Before
    public void loadPublicArtworkBeforeRendering() {
        com.akin.wallet.AkinWallet wallet = (com.akin.wallet.AkinWallet)
                InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        android.widget.ImageView[] icon = new android.widget.ImageView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            icon[0] = new android.widget.ImageView(wallet);
            wallet.platformIcons().bind(icon[0], R.drawable.facebook);
        });
        java.util.concurrent.atomic.AtomicBoolean loaded = new java.util.concurrent.atomic.AtomicBoolean();
        for (int attempt = 0; attempt < 200 && !loaded.get(); attempt++) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    loaded.set(icon[0].getDrawable() instanceof android.graphics.drawable.BitmapDrawable));
            if (!loaded.get()) android.os.SystemClock.sleep(25);
        }
        assertTrue("Public artwork did not load", loaded.get());
    }

    private Context context(int width, int height, float font) {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.screenWidthDp = width;
        config.screenHeightDp = height;
        config.fontScale = font;
        return new android.view.ContextThemeWrapper(base.createConfigurationContext(config),
                R.style.Theme_AkinWallet);
    }

    private List<SocialAccountModel> records(int count) {
        List<SocialAccountModel> records = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            records.add(new SocialAccountModel(index + 1, "Facebook", "example-" + (index + 1),
                    "", "", 1, 1, 0));
        }
        return records;
    }

    private void measure(View root, int width, int height) {
        float density = root.getResources().getDisplayMetrics().density;
        int widthSpec = View.MeasureSpec.makeMeasureSpec(Math.round(width * density), View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(Math.round(height * density), View.MeasureSpec.EXACTLY);
        // A second pass also verifies stability after RecyclerView has laid out its rows.
        for (int pass = 0; pass < 2; pass++) {
            root.measure(widthSpec, heightSpec);
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        }
    }

    private View socialCard(View root, boolean search) {
        return root.findViewById(search ? R.id.dashboard_search_social_account_card
                : R.id.dashboard_social_account_card);
    }

    private RecyclerView socialRows(View card, boolean search) {
        return card.findViewById(search ? R.id.dashboard_search_social_account_list
                : R.id.dashboard_social_account_list);
    }

    private View prepare(View root, boolean search, SocialAccountAdapter adapter) {
        root.findViewById(R.id.dashboard_content).setVisibility(View.VISIBLE);
        View card = socialCard(root, search);
        card.setVisibility(View.VISIBLE);
        RecyclerView rows = socialRows(card, search);
        rows.setLayoutManager(new LinearLayoutManager(root.getContext()));
        rows.setAdapter(adapter);
        if (search) {
            root.findViewById(R.id.dashboard_search_social_account_header).setVisibility(View.VISIBLE);
            return (View) card.getParent();
        }
        root.findViewById(R.id.dashboard_social_account_empty_state).setVisibility(View.GONE);
        RecyclerView ids = root.findViewById(R.id.dashboard_government_id_carousel);
        RecyclerView cards = root.findViewById(R.id.dashboard_bank_card_carousel);
        ids.setLayoutManager(new LinearLayoutManager(root.getContext(), RecyclerView.HORIZONTAL, false));
        cards.setLayoutManager(new LinearLayoutManager(root.getContext(), RecyclerView.HORIZONTAL, false));
        com.akin.wallet.adapter.GovernmentIdAdapter idAdapter =
                new com.akin.wallet.adapter.GovernmentIdAdapter(record -> {});
        com.akin.wallet.adapter.BankCardAdapter cardAdapter =
                new com.akin.wallet.adapter.BankCardAdapter(record -> {});
        idAdapter.updateData(java.util.Collections.singletonList(new com.akin.wallet.model.GovernmentIDModel(
                1, "National ID", java.util.Map.of("full_name", "Ada Example", "psn", "0000000000000000"), 1, 1)));
        cardAdapter.updateData(java.util.Collections.singletonList(new com.akin.wallet.model.BankCardModel(
                1, "Debit", "Visa", "Example Bank", "Ada Example", "4111111111111111", "12/30",
                "", "", 0, 1, 1)));
        ids.setAdapter(idAdapter);
        cards.setAdapter(cardAdapter);
        return root;
    }

    @Test
    public void emptyDashboardAndSettingsHaveFreshSyntheticPreviews() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context(411, 891, 1f);
            View root = LayoutInflater.from(context).inflate(R.layout.activity_dashboard, null);
            root.findViewById(R.id.dashboard_content).setVisibility(View.VISIBLE);
            for (int id : new int[]{R.id.dashboard_government_id_carousel,
                    R.id.dashboard_bank_card_carousel, R.id.dashboard_social_account_card}) {
                root.findViewById(id).setVisibility(View.GONE);
            }
            for (int id : new int[]{R.id.dashboard_government_id_empty_state,
                    R.id.dashboard_bank_card_empty_state, R.id.dashboard_social_account_empty_state}) {
                root.findViewById(id).setVisibility(View.VISIBLE);
            }
            measure(root, 411, 811);
            preview(root, "dashboard-empty");
            View settings = LayoutInflater.from(context).inflate(R.layout.activity_settings, null);
            ((android.widget.TextView) settings.findViewById(R.id.settings_version_label))
                    .setText(com.akin.wallet.AkinWallet.versionName());
            measure(settings, 411, 811);
            preview(settings, "settings");
        });
    }

    private int contentHeight(View card, RecyclerView rows) {
        int height = card.getPaddingTop() + card.getPaddingBottom()
                + rows.getPaddingTop() + rows.getPaddingBottom();
        // LayoutManager excludes disappearing rows temporarily retained by item animations.
        for (int index = 0; index < rows.getLayoutManager().getChildCount(); index++) {
            View child = rows.getLayoutManager().getChildAt(index);
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
            height += rows.getLayoutManager().getDecoratedMeasuredHeight(child)
                    + params.topMargin + params.bottomMargin;
        }
        return height;
    }

    private int maximumHeight(View root, View viewport, boolean search, int width, int height) {
        View card = socialCard(root, search);
        if (search) {
            measure(viewport, width, height);
            View parent = (View) card.getParent();
            return parent.getHeight() - parent.getPaddingBottom() - card.getTop();
        }
        View empty = root.findViewById(R.id.dashboard_social_account_empty_state);
        card.setVisibility(View.GONE);
        empty.setVisibility(View.VISIBLE);
        measure(viewport, width, height);
        int maximum = empty.getHeight();
        empty.setVisibility(View.GONE);
        card.setVisibility(View.VISIBLE);
        return maximum;
    }

    private int naturalHeight(View card, RecyclerView rows) {
        SocialAccountAdapter adapter = (SocialAccountAdapter) rows.getAdapter();
        int height = card.getPaddingTop() + card.getPaddingBottom()
                + rows.getPaddingTop() + rows.getPaddingBottom();
        for (int index = 0; index < adapter.getItemCount(); index++) {
            SocialAccountAdapter.AccountViewHolder holder = adapter.onCreateViewHolder(rows, 0);
            adapter.onBindViewHolder(holder, index);
            View row = holder.itemView;
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
            row.measure(View.MeasureSpec.makeMeasureSpec(rows.getWidth() - rows.getPaddingLeft()
                            - rows.getPaddingRight() - params.leftMargin - params.rightMargin, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            height += row.getMeasuredHeight() + params.topMargin + params.bottomMargin;
        }
        return height;
    }

    private void preview(View root, String name) {
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir(), "social-policy-previews");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            root.draw(new Canvas(bitmap));
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        } finally {
            bitmap.recycle();
        }
    }

    @Test
    public void shortListsWrapAndLongListsScrollOnDashboardAndSearch() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int[] size : sizes) for (float font : new float[]{1f, 1.3f}) {
                for (boolean search : new boolean[]{false, true}) {
                    int previousHeight = 0;
                    for (int count : new int[]{1, 2, 3, 4, 5, 20}) {
                        Context context = context(size[0], size[1], font);
                        View root = LayoutInflater.from(context).inflate(R.layout.activity_dashboard, null);
                        SocialAccountAdapter adapter = new SocialAccountAdapter(record -> {});
                        adapter.updateData(records(count));
                        View viewport = prepare(root, search, adapter);
                        int maximum = maximumHeight(root, viewport, search, size[0], size[1] - (search ? 152 : 80));
                        measure(viewport, size[0], size[1] - (search ? 152 : 80));
                        View card = socialCard(root, search);
                        RecyclerView rows = socialRows(card, search);
                        String label = (search ? "Search " : "Dashboard ") + size[0] + "x" + size[1]
                                + " font=" + font + " count=" + count;
                        assertTrue(label + " empty height", card.getHeight() > 0);
                        assertTrue(label + " height decreased", card.getHeight() >= previousHeight);
                        assertTrue(label + " exceeds viewport", card.getBottom() <= ((View) card.getParent()).getHeight());
                        int natural = naturalHeight(card, rows);
                        assertEquals(label + " should fit rows up to the empty-state limit",
                                Math.min(natural, maximum), card.getHeight());
                        assertEquals(label + " scrolling does not match content overflow",
                                natural > maximum, rows.canScrollVertically(1));
                        assertEquals(label + " extra bottom padding", 0, rows.getPaddingBottom());
                        if (count == 20) assertEquals(label + " maximum differs from empty state", maximum, card.getHeight());
                        if (count == 20) assertTrue(label + " long list cannot scroll", rows.canScrollVertically(1));
                        if (!search) {
                            View add = root.findViewById(R.id.dashboard_quick_add_button);
                            View parent = (View) card.getParent();
                            assertEquals(label + " row width changed for FAB clearance", 0, rows.getPaddingEnd());
                            if (count == 20) {
                                rows.scrollBy(0, Integer.MAX_VALUE);
                                measure(viewport, size[0], size[1] - 80);
                                assertEquals(label + " height changed at end of scroll", maximum, card.getHeight());
                                View last = rows.getLayoutManager().findViewByPosition(count - 1);
                                assertNotNull(label + " last row missing", last);
                                assertEquals(label + " bottom gap after scrolling", rows.getHeight(),
                                        rows.getLayoutManager().getDecoratedBottom(last));
                                assertFalse(label + " scrolls beyond last row", rows.canScrollVertically(1));
                            }
                            for (int carouselId : new int[]{R.id.dashboard_government_id_carousel,
                                    R.id.dashboard_bank_card_carousel}) {
                                RecyclerView carousel = root.findViewById(carouselId);
                                CardPreviewLayout page = (CardPreviewLayout) carousel.getChildAt(0);
                                View face = page.getChildAt(0);
                                assertTrue(label + " face exceeds page width",
                                        face.getWidth() * face.getScaleX() <= page.getWidth() + 1);
                                assertTrue(label + " face exceeds page height",
                                        face.getHeight() * face.getScaleY() <= page.getHeight() + 1);
                                int bottomField = carouselId == R.id.dashboard_government_id_carousel
                                        ? R.id.government_id_number : R.id.bank_card_preview_expiry;
                                View field = face.findViewById(bottomField);
                                android.graphics.Rect bounds = new android.graphics.Rect(0, 0,
                                        field.getWidth(), field.getHeight());
                                ((ViewGroup) face).offsetDescendantRectToMyCoords(field, bounds);
                                assertTrue(label + " face field clipped", bounds.bottom <= face.getHeight());
                            }
                        }
                        previousHeight = card.getHeight();
                        if (size[0] == 411 && font == 1f) {
                            preview(viewport, (search ? "search" : "dashboard") + "-" + count + "-records");
                        }
                        if (size[0] == 320 && size[1] == 480 && font == 1.3f && count == 2) {
                            preview(viewport, (search ? "search" : "dashboard") + "-compact-large-font");
                        }
                    }
                }
            }
        });
    }

    @Test
    public void committedAdapterChangesShrinkAndGrowTheSameContainer() throws Exception {
        for (boolean search : new boolean[]{false, true}) {
            View[] root = new View[1];
            View[] viewport = new View[1];
            SocialAccountAdapter adapter = new SocialAccountAdapter(record -> {});
            int[] shortHeight = new int[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                root[0] = LayoutInflater.from(context(411, 891, 1f)).inflate(R.layout.activity_dashboard, null);
                viewport[0] = prepare(root[0], search, adapter);
            });
            int[] rowWidth = new int[1];
            for (int count : new int[]{1, 2, 3, 4, 5, 6, 20, 5, 4, 3, 2, 1}) {
                CountDownLatch committed = new CountDownLatch(1);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> adapter.updateData(records(count), committed::countDown));
                assertTrue("Adapter commit timed out", committed.await(10, TimeUnit.SECONDS));
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    int maximum = maximumHeight(root[0], viewport[0], search, 411, search ? 739 : 811);
                    measure(viewport[0], 411, search ? 739 : 811);
                    View card = socialCard(root[0], search);
                    RecyclerView rows = socialRows(card, search);
                    if (count == 1) {
                        if (shortHeight[0] != 0) assertEquals(shortHeight[0], card.getHeight());
                        shortHeight[0] = card.getHeight();
                    }
                    int natural = naturalHeight(card, rows);
                    assertEquals(Math.min(natural, maximum), card.getHeight());
                    assertEquals("Overflow at count=" + count, natural > maximum,
                            rows.canScrollVertically(1) || rows.canScrollVertically(-1));
                    assertEquals("Account count added side padding", 0, rows.getPaddingEnd());
                    View first = rows.getLayoutManager().getChildAt(0);
                    assertNotNull(first);
                    if (rowWidth[0] == 0) rowWidth[0] = first.getWidth();
                    assertEquals("Account width changed at count=" + count, rowWidth[0], first.getWidth());
                    if (count == 20) {
                        rows.scrollBy(0, Integer.MAX_VALUE);
                        measure(viewport[0], 411, search ? 739 : 811);
                        assertEquals(maximum, card.getHeight());
                    }
                });
            }
        }
    }

    @Test
    public void firstAccountAndOverflowUseTheEmptyHeightWithEveryIdAndBankState() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int[] size : new int[][]{{320, 480}, {411, 891}}) {
                for (int sections = 0; sections < 4; sections++) {
                    for (int count : new int[]{1, 3, 20}) {
                        View root = LayoutInflater.from(context(size[0], size[1], 1f))
                                .inflate(R.layout.activity_dashboard, null);
                        SocialAccountAdapter adapter = new SocialAccountAdapter(record -> {});
                        adapter.updateData(records(count));
                        View viewport = prepare(root, false, adapter);
                        root.findViewById(R.id.dashboard_government_id_carousel)
                                .setVisibility((sections & 1) == 0 ? View.GONE : View.VISIBLE);
                        root.findViewById(R.id.dashboard_government_id_empty_state)
                                .setVisibility((sections & 1) == 0 ? View.VISIBLE : View.GONE);
                        root.findViewById(R.id.dashboard_bank_card_carousel)
                                .setVisibility((sections & 2) == 0 ? View.GONE : View.VISIBLE);
                        root.findViewById(R.id.dashboard_bank_card_empty_state)
                                .setVisibility((sections & 2) == 0 ? View.VISIBLE : View.GONE);
                        int maximum = maximumHeight(root, viewport, false, size[0], size[1] - 80);
                        measure(viewport, size[0], size[1] - 80);
                        View card = socialCard(root, false);
                        RecyclerView rows = socialRows(card, false);
                        String label = size[0] + "x" + size[1] + " sections=" + sections + " count=" + count;
                        assertEquals(label, Math.min(naturalHeight(card, rows), maximum), card.getHeight());
                        if (count == 20) assertEquals(label + " maximum", maximum, card.getHeight());
                        assertEquals(label + " bottom padding", 0, rows.getPaddingBottom());
                    }
                }
            }
        });
    }

    @Test
    public void longUsernamesWrapToTheirMeasuredHeight() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (boolean search : new boolean[]{false, true}) {
                View root = LayoutInflater.from(context(411, 891, 1.3f))
                        .inflate(R.layout.activity_dashboard, null);
                SocialAccountAdapter adapter = new SocialAccountAdapter(record -> {});
                adapter.updateData(java.util.Collections.singletonList(new SocialAccountModel(
                        1, "Facebook", "long-example-username-with-extra-text-that-wraps-over-several-lines",
                        "", "", 1, 1, 0)));
                View viewport = prepare(root, search, adapter);
                int maximum = maximumHeight(root, viewport, search, 411, search ? 739 : 811);
                measure(viewport, 411, search ? 739 : 811);
                View card = socialCard(root, search);
                assertEquals(Math.min(naturalHeight(card, socialRows(card, search)), maximum), card.getHeight());
            }
        });
    }
}
