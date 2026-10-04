package com.akin.wallet.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.view.*;
import android.widget.*;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;

import org.junit.Test;

import static org.junit.Assert.*;

public class PortraitLayoutTest {
    private final int[][] sizes = {{320, 480}, {320, 568}, {360, 640}, {411, 891}, {480, 960}};
    private final float[] fonts = {1f, 1.15f, 1.3f};

    private Context configured(int width, int height, float scale) {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.fontScale = scale;
        configuration.screenWidthDp = width;
        configuration.screenHeightDp = height;
        return new android.view.ContextThemeWrapper(base.createConfigurationContext(configuration), R.style.Theme_AkinWallet);
    }

    private int px(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private void measure(View root, Context context, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(px(context, width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(px(context, height), View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
    }

    private void within(View view, View root, String description) {
        assertTrue(description + " has no height", view.getHeight() > 0);
        int bottom = view.getBottom();
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof View && parent != root) {
            bottom += ((View) parent).getTop();
            parent = parent.getParent();
        }
        assertTrue(description + " bottom=" + bottom + " root=" + root.getHeight(), bottom <= root.getHeight());
    }

    private void completeText(View view, String label) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            android.text.Layout layout = text.getLayout();
            if (text.length() == 0 || layout == null) return;
            assertEquals(label + " truncated: " + text.getText(), text.length(),
                    layout.getLineEnd(layout.getLineCount() - 1));
            for (int line = 0; line < layout.getLineCount(); line++)
                assertEquals(label + " ellipsis: " + text.getText(), 0, layout.getEllipsisCount(line));
            assertTrue(label + " text clipped: " + text.getText() + " layout=" + layout.getHeight()
                    + " view=" + text.getHeight() + " pad="
                    + (text.getCompoundPaddingTop() + text.getCompoundPaddingBottom()), layout.getHeight()
                    <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 1);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int child = 0; child < group.getChildCount(); child++)
                completeText(group.getChildAt(child), label);
        }
    }

    @Test
    public void settingsControlsFitPortraitAndFontMatrix() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int[] size : sizes)
                for (float font : fonts) {
                    Context context = configured(size[0], size[1], font);
                    View root = LayoutInflater.from(context).inflate(R.layout.activity_settings, null);
                    ((TextView) root.findViewById(R.id.settings_version_label)).setText("Version 1.0");
                    measure(root, context, size[0], size[1] - 80);
                    String label = size[0] + "x" + size[1] + " font=" + font;
                    within(root.findViewById(R.id.settings_about_row), root, label + " About");
                    within(root.findViewById(R.id.settings_version_label), root, label + " Version");
                    completeText(root, label);
                    for (int status : new int[]{R.string.settings_biometric_on,
                            R.string.settings_biometric_off, R.string.settings_biometric_unavailable}) {
                        ((TextView) root.findViewById(R.id.settings_biometric_status)).setText(status);
                        measure(root, context, size[0], size[1] - 80);
                        completeText(root, label + " status=" + status);
                        within(root.findViewById(R.id.settings_about_row), root, label + " About status");
                    }
                    assertFalse(root instanceof ScrollView);
                }
        });
    }

    @Test
    public void populatedDashboardKeepsCarouselAndInternalSocialScrolling() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int[] size : sizes) for (float font : fonts) {
                Context context = configured(size[0], size[1], font);
                View root = LayoutInflater.from(context).inflate(R.layout.activity_dashboard, null);
                for (int id : new int[]{R.id.dashboard_government_id_empty_state,
                        R.id.dashboard_bank_card_empty_state, R.id.dashboard_social_account_empty_state}) {
                    root.findViewById(id).setVisibility(View.GONE);
                }
                root.findViewById(R.id.dashboard_social_account_card).setVisibility(View.VISIBLE);
                RecyclerView ids = root.findViewById(R.id.dashboard_government_id_carousel);
                RecyclerView cards = root.findViewById(R.id.dashboard_bank_card_carousel);
                RecyclerView social = root.findViewById(R.id.dashboard_social_account_list);
                ids.setVisibility(View.VISIBLE);
                cards.setVisibility(View.VISIBLE);
                ids.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(context,
                        RecyclerView.HORIZONTAL, false));
                cards.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(context,
                        RecyclerView.HORIZONTAL, false));
                social.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(context));
                com.akin.wallet.adapter.GovernmentIdAdapter idAdapter = new com.akin.wallet.adapter.GovernmentIdAdapter(row -> {});
                com.akin.wallet.adapter.BankCardAdapter cardAdapter = new com.akin.wallet.adapter.BankCardAdapter(row -> {});
                com.akin.wallet.adapter.SocialAccountAdapter socialAdapter = new com.akin.wallet.adapter.SocialAccountAdapter(row -> {});
                java.util.List<com.akin.wallet.model.GovernmentIDModel> idRows = new java.util.ArrayList<>();
                java.util.List<com.akin.wallet.model.BankCardModel> cardRows = new java.util.ArrayList<>();
                java.util.List<com.akin.wallet.model.SocialAccountModel> accounts = new java.util.ArrayList<>();
                for (int index = 0; index < 5; index++) {
                    idRows.add(new com.akin.wallet.model.GovernmentIDModel(index + 1, "National ID",
                            java.util.Map.of("first_name", "Ada", "last_name", "Fixture"), 1, 1));
                    cardRows.add(new com.akin.wallet.model.BankCardModel(index + 1, "Debit", "Visa",
                            "Fixture Bank", "Ada Fixture", "4111111111111111", "12/30", "", "", index, 1, 1));
                }
                for (int index = 0; index < 20; index++) {
                    accounts.add(new com.akin.wallet.model.SocialAccountModel(index + 1, "Facebook",
                            "fixture-" + index, "", "", 1, 1, 0));
                }
                idAdapter.updateData(idRows); cardAdapter.updateData(cardRows); socialAdapter.updateData(accounts);
                ids.setAdapter(idAdapter); cards.setAdapter(cardAdapter); social.setAdapter(socialAdapter);
                measure(root, context, size[0], size[1] - 80);
                measure(root, context, size[0], size[1] - 80);
                String label = size[0] + "x" + size[1] + " font=" + font;
                within(ids, root, label + " IDs"); within(cards, root, label + " cards");
                within(social, root, label + " social list");
                assertTrue(label + " social list cannot scroll", social.canScrollVertically(1));
                assertEquals(RecyclerView.HORIZONTAL,
                        ((androidx.recyclerview.widget.LinearLayoutManager) ids.getLayoutManager()).getOrientation());
                assertEquals(RecyclerView.HORIZONTAL,
                        ((androidx.recyclerview.widget.LinearLayoutManager) cards.getLayoutManager()).getOrientation());
                assertFalse(root instanceof ScrollView);
            }
        });
    }

    @Test
    public void emptyDashboardActionsFitPortraitAndFontMatrix() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int[] size : sizes)
                for (float font : fonts) {
                    Context context = configured(size[0], size[1], font);
                    View root = LayoutInflater.from(context).inflate(R.layout.activity_dashboard, null);
                    root.findViewById(R.id.dashboard_government_id_carousel).setVisibility(View.GONE);
                    root.findViewById(R.id.dashboard_bank_card_carousel).setVisibility(View.GONE);
                    root.findViewById(R.id.dashboard_social_account_card).setVisibility(View.GONE);
                    root.findViewById(R.id.dashboard_government_id_empty_state).setVisibility(View.VISIBLE);
                    root.findViewById(R.id.dashboard_bank_card_empty_state).setVisibility(View.VISIBLE);
                    root.findViewById(R.id.dashboard_social_account_empty_state).setVisibility(View.VISIBLE);
                    measure(root, context, size[0], size[1] - 80);
                    String label = size[0] + "x" + size[1] + " font=" + font;
                    within(root.findViewById(R.id.dashboard_government_id_empty_action), root, label + " ID action");
                    within(root.findViewById(R.id.dashboard_bank_card_empty_action), root, label + " Card action");
                    within(root.findViewById(R.id.dashboard_social_account_empty_action), root, label + " Social action");
                    completeText(root, label);
                }
        });
    }
}
