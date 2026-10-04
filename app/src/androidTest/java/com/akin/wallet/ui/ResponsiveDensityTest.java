package com.akin.wallet.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;

import com.akin.wallet.R;
import com.akin.wallet.adapter.BankCardAdapter;
import com.akin.wallet.adapter.GovernmentIdAdapter;
import com.akin.wallet.adapter.SocialAccountAdapter;
import com.akin.wallet.model.BankCardModel;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.model.GovernmentIdTypes;
import com.akin.wallet.model.SocialAccountModel;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** Real XML/adapters at explicit pixel and density configurations; no user records. */
public class ResponsiveDensityTest {
    private static final int[][] SCREENS = {
            {420, 800, 210}, {720, 1280, 320}, {1080, 1920, 480},
            {1080, 2400, 420}, {1440, 2560, 640}, {1440, 3200, 560}
    };

    private Context context(int[] screen, float font) {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.densityDpi = screen[2];
        config.screenWidthDp = screen[0] * 160 / screen[2];
        config.screenHeightDp = screen[1] * 160 / screen[2];
        config.orientation = Configuration.ORIENTATION_PORTRAIT;
        config.fontScale = font;
        return new android.view.ContextThemeWrapper(base.createConfigurationContext(config),
                R.style.Theme_AkinWallet);
    }

    private void measure(View root, int width, int height) {
        for (int pass = 0; pass < 3; pass++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
        }
    }

    private void textFits(View view, ViewGroup face, String label) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.length() == 0) return;
            android.text.Layout layout = text.getLayout();
            assertNotNull(label + " missing text layout " + text.getId(), layout);
            assertTrue(label + " no text lines", layout.getLineCount() > 0);
            assertEquals(label + " truncated text: " + text.getText(), text.length(),
                    layout.getLineEnd(layout.getLineCount() - 1));
            for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals(label + " ellipsized " + text.getText(), 0, layout.getEllipsisCount(line));
                assertTrue(label + " text too wide: " + text.getText(),
                        layout.getLineWidth(line) <= text.getWidth()
                                - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1);
            }
            assertTrue(label + " text vertically clipped: " + text.getText()
                            + " layout=" + layout.getHeight() + " height=" + text.getHeight()
                            + " padding=" + (text.getCompoundPaddingTop() + text.getCompoundPaddingBottom())
                            + " lines=" + layout.getLineCount() + " textSize=" + text.getTextSize(),
                    layout.getHeight() <= text.getHeight()
                            - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 1);
            Rect bounds = new Rect(0, 0, text.getWidth(), text.getHeight());
            face.offsetDescendantRectToMyCoords(text, bounds);
            assertTrue(label + " field outside card: " + text.getText(),
                    bounds.left >= 0 && bounds.top >= 0
                            && bounds.right <= face.getWidth() + 1 && bounds.bottom <= face.getHeight() + 1);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int child = 0; child < group.getChildCount(); child++)
                textFits(group.getChildAt(child), face, label);
        }
    }

    @Test
    public void cardFacesAndFloatingSocialFitPixelDensityAndFontMatrix() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int[] screen : SCREENS) for (float font : new float[]{1f, 1.15f, 1.3f}) {
                Context context = context(screen, font);
                ViewGroup root = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.activity_dashboard, null);
                RecyclerView ids = root.findViewById(R.id.dashboard_government_id_carousel);
                RecyclerView cards = root.findViewById(R.id.dashboard_bank_card_carousel);
                RecyclerView social = root.findViewById(R.id.dashboard_social_account_list);
                ids.setLayoutManager(new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));
                cards.setLayoutManager(new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));
                social.setLayoutManager(new LinearLayoutManager(context));
                GovernmentIdAdapter idAdapter = new GovernmentIdAdapter(row -> {});
                List<GovernmentIDModel> documents = new ArrayList<>();
                for (GovernmentIdTypes.IdType type : GovernmentIdTypes.getAllTypes()) {
                    documents.add(new GovernmentIDModel(documents.size() + 1, type.name,
                            Map.of(type.numberKey, "1234567890123456", "full_name", "W".repeat(80),
                                    "fullName", "W".repeat(80), "birth_date", "19900101",
                                    "dateOfBirth", "19900101", "expiry_date", "20300101"), 1, 1));
                }
                idAdapter.updateData(documents);
                BankCardAdapter cardAdapter = new BankCardAdapter(row -> {});
                List<BankCardModel> bankCards = new ArrayList<>();
                for (int design = 0; design < BankCardAdapter.designCount(); design++)
                    bankCards.add(new BankCardModel(design + 1, "Prepaid", "MasterCard", "W".repeat(50),
                            "W".repeat(50), "4111111111111111", "12/30", "", "", design, 1, 1));
                cardAdapter.updateData(bankCards);
                SocialAccountAdapter socialAdapter = new SocialAccountAdapter(row -> {});
                List<SocialAccountModel> accounts = new ArrayList<>();
                for (int index = 0; index < 20; index++)
                    accounts.add(new SocialAccountModel(index + 1, "Facebook",
                            "long-account-username-for-responsive-screen-validation-" + index, "", "", 1, 1, 0));
                socialAdapter.updateData(accounts);
                ids.setAdapter(idAdapter); cards.setAdapter(cardAdapter); social.setAdapter(socialAdapter);
                int height = screen[1] - Math.round(80 * screen[2] / 160f);
                String label = screen[0] + "x" + screen[1] + " dpi=" + screen[2] + " font=" + font;
                measure(root, screen[0], height);
                View card = root.findViewById(R.id.dashboard_social_account_card);
                ViewGroup content = (ViewGroup) card.getParent();
                assertEquals(label + " bottom edge", content.getHeight() - content.getPaddingBottom(), card.getBottom());
                View fab = root.findViewById(R.id.dashboard_quick_add_button);
                assertTrue(label + " FAB outside social card", fab.getTop() < card.getBottom());
                assertTrue(label + " internal list cannot scroll", social.canScrollVertically(1));
                assertTrue(label + " FAB clearance", social.getPaddingBottom() >= fab.getHeight());
                for (RecyclerView carousel : new RecyclerView[]{ids, cards}) {
                    for (int position = 0; position < carousel.getAdapter().getItemCount(); position++) {
                        carousel.scrollToPosition(position);
                        measure(root, screen[0], height);
                        RecyclerView.ViewHolder holder = carousel.findViewHolderForAdapterPosition(position);
                        assertNotNull(label + " missing carousel page " + position, holder);
                        CardPreviewLayout page = (CardPreviewLayout) holder.itemView;
                        ViewGroup face = (ViewGroup) page.getChildAt(0);
                        assertEquals(label + " nonuniform scaling", face.getScaleX(), face.getScaleY(), 0f);
                        assertTrue(label + " face outside viewport", face.getWidth() * face.getScaleX() <= page.getWidth() + 1
                                && face.getHeight() * face.getScaleY() <= page.getHeight() + 1);
                        textFits(face, face, label + " page=" + position);
                    }
                }
                social.scrollToPosition(19);
                measure(root, screen[0], height);
                View row = social.findViewHolderForAdapterPosition(19).itemView;
                textFits(row, (ViewGroup) row, label + " final Social row");
                assertTrue(label + " final row covered by FAB", row.getBottom() + social.getTop()
                        + card.getTop() + content.getTop() <= fab.getTop());
            }
        });
    }
}
