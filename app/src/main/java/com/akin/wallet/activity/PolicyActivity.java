package com.akin.wallet.activity;

import android.os.Bundle;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.akin.wallet.AkinWallet;
import com.akin.wallet.R;
import com.akin.wallet.util.Ui;
import com.google.android.material.appbar.MaterialToolbar;

/**
 * In-app legal screen behind Settings. Shows the Privacy Policy, Terms of
 * Service or About page depending on {@link #EXTRA_TYPE}. The bundled text
 * is sectioned (ALL-CAPS heading line, then body, blank line apart) and each
 * section renders as an accent-blue heading over light body copy. The About
 * page stamps the installed version name at the end.
 */
public class PolicyActivity extends BaseVaultActivity {

    public static final String EXTRA_TYPE = "extra_policy_type";
    public static final String TYPE_PRIVACY = "privacy";
    public static final String TYPE_TERMS = "terms";
    public static final String TYPE_ABOUT = "about";

    /** Section separator in the bundled policy text (literal, not regex). */
    private static final java.util.regex.Pattern SECTION_SEPARATOR =
            java.util.regex.Pattern.compile("\n\n", java.util.regex.Pattern.LITERAL);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_policy);
        applyChrome();
        MaterialToolbar toolbar = findViewById(R.id.policy_toolbar);

        LinearLayout sections = findViewById(R.id.policy_sections);
        if (sections == null) {
            finish();
            return;
        }

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

    /** Splits "HEADING\n body\n\n..." into styled heading + body view pairs. */
    private void renderSections(LinearLayout container, String body) {
        if (body == null || body.trim().isEmpty()) {
            return;
        }
        boolean first = true;
        for (String chunk : SECTION_SEPARATOR.split(body)) {
            String section = chunk.trim();
            if (section.isEmpty()) {
                continue;
            }
            int newlineIndex = section.indexOf('\n');
            String heading = newlineIndex == -1 ? section : section.substring(0, newlineIndex).trim();
            String text = newlineIndex == -1 ? "" : section.substring(newlineIndex + 1).trim();

            TextView headingView = new TextView(this);
            headingView.setText(heading);
            headingView.setTextColor(getColor(R.color.dashboard_active));
            headingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            headingView.setTypeface(headingView.getTypeface(), Typeface.BOLD);
            headingView.setLetterSpacing(0.06f);
            LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            headingParams.topMargin = first ? 0 : Ui.dp(this, 20);
            headingView.setLayoutParams(headingParams);
            container.addView(headingView);

            if (!text.isEmpty()) {
                TextView bodyView = new TextView(this);
                bodyView.setText(text);
                bodyView.setTextColor(getColor(R.color.text_subtle_light));
                bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                bodyView.setLineSpacing(0, 1.25f);
                LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bodyParams.topMargin = Ui.dp(this, 6);
                bodyView.setLayoutParams(bodyParams);
                container.addView(bodyView);
            }
            first = false;
        }
    }
}
