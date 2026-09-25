package com.akin.wallet.activity;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.akin.wallet.AkinWallet;
import com.akin.wallet.R;
import com.akin.wallet.util.Ui;
import com.google.android.material.appbar.MaterialToolbar;

/**
 * In-app legal screen behind Settings: Privacy, Terms, or About.
 */
public class PolicyActivity extends BaseVaultActivity {

    public static final String EXTRA_TYPE = "extra_policy_type";
    public static final String TYPE_PRIVACY = "privacy";
    public static final String TYPE_TERMS = "terms";
    public static final String TYPE_ABOUT = "about";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_policy);
        applyChrome();
        MaterialToolbar toolbar = findViewById(R.id.toolbar);

        LinearLayout sections = findViewById(R.id.policy_sections);

        String type = getIntent().getStringExtra(EXTRA_TYPE);
        String body;
        if (TYPE_TERMS.equals(type)) {
            toolbar.setTitle(R.string.settings_terms);
            body = getString(R.string.policy_terms_body);
        } else if (TYPE_ABOUT.equals(type)) {
            toolbar.setTitle(R.string.settings_about);
            body = getString(R.string.policy_about_body)
                    .replace("{version}", AkinWallet.versionName());
        } else {
            toolbar.setTitle(R.string.settings_privacy);
            body = getString(R.string.policy_privacy_body);
        }
        renderSections(sections, body);
    }

    /** Splits "HEADING\nbody\n\n..." into heading + body pairs. */
    private void renderSections(LinearLayout container, String body) {
        if (body == null || body.trim().isEmpty()) {
            return;
        }
        boolean first = true;
        for (String chunk : body.split("\n\n")) {
            String section = chunk.trim();
            if (section.isEmpty()) {
                continue;
            }
            int newlineIndex = section.indexOf('\n');
            String heading = newlineIndex == -1 ? section : section.substring(0, newlineIndex).trim();
            String text = newlineIndex == -1 ? "" : section.substring(newlineIndex + 1).trim();

            container.addView(makeHeading(heading, first));
            if (!text.isEmpty()) {
                container.addView(makeBody(text));
            }
            first = false;
        }
    }

    private TextView makeHeading(String heading, boolean first) {
        TextView headingView = makeText(heading, R.color.dashboard_active, 13, Typeface.BOLD);
        headingView.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = first ? 0 : Ui.dp(this, 20);
        headingView.setLayoutParams(params);
        return headingView;
    }

    private TextView makeBody(String text) {
        TextView bodyView = makeText(text, R.color.text_subtle_light, 14, Typeface.NORMAL);
        bodyView.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, 6);
        bodyView.setLayoutParams(params);
        return bodyView;
    }

    private TextView makeText(String text, int colorRes, float sp, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(colorRes));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(view.getTypeface(), style);
        return view;
    }
}
