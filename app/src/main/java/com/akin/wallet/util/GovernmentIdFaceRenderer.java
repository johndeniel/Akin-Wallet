package com.akin.wallet.util;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.akin.wallet.R;
import com.akin.wallet.adapter.BankCardDesignAdapter;
import com.akin.wallet.model.GovernmentIDModel;

import java.util.Map;

/**
 * Single owner of Government ID face painting.
 *
 * <p>There is exactly ONE ID layout: {@code item_government_id_card}. The
 * dashboard carousel and the Government ID picker both inflate it and
 * render it here with identical metrics, so look and feel cannot drift
 * between the two screens.
 */
public final class GovernmentIdFaceRenderer {

    private GovernmentIdFaceRenderer() {
    }

    /** Cached face views — bound once per ViewHolder, never per bind. */
    public static final class FaceViews {
        public final View cardRoot;
        public final TextView title;
        public final TextView subtitle;
        public final View rule;
        public final TextView holderLabel;
        public final TextView holder;
        public final TextView numberLabel;
        public final TextView number;
        public final com.akin.wallet.util.BarcodeView barcode;
        public final TextView dob;
        public final TextView dobLabel;
        public final TextView expiry;
        public final TextView expiryLabel;
        public final View photoBox;
        public final ImageView photoIcon;
        public final Resources res;
        public final float density;

        private FaceViews(@NonNull View root) {
            cardRoot = root.findViewById(R.id.government_id_card_root);
            title = root.findViewById(R.id.government_id_title);
            subtitle = root.findViewById(R.id.government_id_subtitle);
            rule = root.findViewById(R.id.government_id_rule);
            holderLabel = root.findViewById(R.id.government_id_holder_label);
            holder = root.findViewById(R.id.government_id_holder);
            numberLabel = root.findViewById(R.id.government_id_number_label);
            number = root.findViewById(R.id.government_id_number);
            barcode = root.findViewById(R.id.government_id_barcode);
            dob = root.findViewById(R.id.government_id_dob);
            dobLabel = root.findViewById(R.id.government_id_dob_label);
            expiry = root.findViewById(R.id.government_id_expiry);
            expiryLabel = root.findViewById(R.id.government_id_expiry_label);
            photoBox = root.findViewById(R.id.government_id_photo_box);
            photoIcon = root.findViewById(R.id.government_id_photo_icon);
            res = root.getResources();
            density = res.getDisplayMetrics().density;
        }

        public static FaceViews bind(@NonNull View root) {
            return new FaceViews(root);
        }
    }

    /**
     * Paints one ID face with the single unified metrics.
     *
     * @param f       cached face views
     * @param spec    resolved type spec (sizing + field keys)
     * @param typeName raw type name for scheme/subtitle/number lookups
     * @param title   header title (uppercased here)
     * @param fields  field values to render
     */
    public static void render(@NonNull FaceViews f, @NonNull GovernmentIDModel.IdType spec,
                              @NonNull String typeName, @NonNull String title,
                              @NonNull Map<String, String> fields) {
        BankCardDesignAdapter.applyCardOutline(f.cardRoot);
        GovernmentIDModel.FaceScheme scheme = GovernmentIDModel.faceScheme(typeName);
        // Avoid redundant background swaps while scrolling: framework caches
        // drawables but setBackgroundResource still triggers invalidate.
        Object bgTag = f.cardRoot.getTag(com.akin.wallet.R.id.tag_card_face_background);
        if (!(bgTag instanceof Integer) || ((Integer) bgTag) != scheme.backgroundRes) {
            f.cardRoot.setBackgroundResource(scheme.backgroundRes);
            f.cardRoot.setTag(com.akin.wallet.R.id.tag_card_face_background, scheme.backgroundRes);
        }

        // Primary number resolves through the spec so the face stays free of
        // per-type key branches.
        String number = GovernmentIDModel.displayNumber(spec, fields);
        f.title.setText(title.toUpperCase(java.util.Locale.ROOT));
        f.title.setTextColor(colorOf(f, scheme.titleColorRes));
        f.title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        f.subtitle.setText(GovernmentIDModel.previewSubtitle(typeName));
        f.subtitle.setTextColor(colorOf(f, scheme.subtitleColorRes));
        f.subtitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 8);
        f.rule.setBackgroundColor(colorOf(f, scheme.ruleColorRes));
        f.holderLabel.setTextColor(colorOf(f, scheme.numberLabelColorRes));
        f.holder.setText(GovernmentIDModel.displayName(fields));
        f.holder.setTextColor(colorOf(f, scheme.holderColorRes));
        f.holder.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        f.numberLabel.setText(GovernmentIDModel.numberLabel(typeName));
        f.numberLabel.setTextColor(colorOf(f, scheme.numberLabelColorRes));
        f.number.setText(number != null && !number.trim().isEmpty()
                ? number.trim() : f.res.getString(R.string.empty_value));
        f.number.setTextColor(colorOf(f, scheme.numberColorRes));
        f.number.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9);
        if (f.barcode != null) {
            f.barcode.setBarColor(colorOf(f, scheme.numberColorRes));
        }

        // Birth key varies by type ("dateOfBirth" on TIN/PhilHealth,
        // "birth_date" elsewhere); first non-blank wins, always YYYY-MM-DD.
        String birth = GovernmentIDModel.displayDate(
                GovernmentIDModel.firstNonEmpty(fields.get("dateOfBirth"), fields.get("birth_date")));
        if (f.dob != null) {
            f.dob.setText(birth != null && !birth.trim().isEmpty() ? birth.trim() : "—");
            f.dob.setTextColor(colorOf(f, scheme.numberColorRes));
            f.dob.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9);
        }
        if (f.dobLabel != null) {
            f.dobLabel.setTextColor(colorOf(f, scheme.numberLabelColorRes));
        }
        GovernmentIDModel.FaceExtra extra = GovernmentIDModel.faceExtra(typeName, fields);
        if (f.expiryLabel != null) {
            f.expiryLabel.setText(extra.label);
            f.expiryLabel.setTextColor(colorOf(f, scheme.numberLabelColorRes));
        }
        if (f.expiry != null) {
            f.expiry.setText(extra.value);
            f.expiry.setTextColor(colorOf(f, scheme.numberColorRes));
            f.expiry.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9);
        }

        // Square photo well adopts the card: tinted fill + border + icon.
        // instanceof: background is a <shape> today, guard survives a future
        // drawable swap without a mid-layout ClassCastException.
        android.graphics.drawable.Drawable bg = f.photoBox.getBackground();
        if (bg instanceof android.graphics.drawable.GradientDrawable) {
            android.graphics.drawable.GradientDrawable photoBg =
                    (android.graphics.drawable.GradientDrawable) bg.mutate();
            photoBg.setColor(colorOf(f, scheme.photoBgRes));
            photoBg.setStroke((int) (1 * f.density + 0.5f), colorOf(f, scheme.photoBorderRes));
        }
        f.photoIcon.setColorFilter(colorOf(f, scheme.photoIconRes));

        applyFaceMetrics(f, scheme);
    }

    /** Face micro-metrics: smaller type, tighter rhythm, smaller well.
     * Layout-affecting work runs once per holder (tag-guarded); per-bind
     * calls only refresh colors/text to avoid measure/layout during scroll. */
    private static void applyFaceMetrics(@NonNull FaceViews f,
                                            @NonNull GovernmentIDModel.FaceScheme scheme) {
        // Colors are cheap but layout is not: run paddings/margins/sizes once.
        boolean metricsDone = Boolean.TRUE.equals(f.cardRoot.getTag(com.akin.wallet.R.id.tag_card_face_metrics));
        // Micro-label colors still refresh every bind (scheme-dependent).
        microLabelColor(f, f.holderLabel, scheme.numberLabelColorRes, metricsDone);
        microLabelColor(f, f.numberLabel, scheme.numberLabelColorRes, metricsDone);
        microLabelColor(f, f.dobLabel, scheme.numberLabelColorRes, metricsDone);
        microLabelColor(f, f.expiryLabel, scheme.numberLabelColorRes, metricsDone);
        if (metricsDone) {
            return;
        }

        // Less space above the header so the face shifts up.
        View faceContent = (View) f.title.getParent();
        if (faceContent != null) {
            faceContent.setPadding(
                    faceContent.getPaddingStart(),
                    (int) (6 * f.density),
                    faceContent.getPaddingEnd(),
                    (int) (6 * f.density));
        }
        // Tighter title -> description -> rule stack.
        setTopMargin(f.subtitle, 1, f.density);
        setTopMargin(f.rule, 4, f.density);

        android.graphics.drawable.Drawable metricsBg = f.photoBox.getBackground();
        if (metricsBg instanceof android.graphics.drawable.GradientDrawable) {
            ((android.graphics.drawable.GradientDrawable) metricsBg)
                    .setCornerRadius(6 * f.density);
        }

        // Smaller avatar well.
        ViewGroup.LayoutParams photoLp = f.photoBox.getLayoutParams();
        if (photoLp != null) {
            int well = (int) (32 * f.density);
            photoLp.width = well;
            photoLp.height = well;
            f.photoBox.setLayoutParams(photoLp);
        }
        ViewGroup.LayoutParams iconLp = f.photoIcon.getLayoutParams();
        if (iconLp != null) {
            int icon = (int) (24 * f.density);
            iconLp.width = icon;
            iconLp.height = icon;
            f.photoIcon.setLayoutParams(iconLp);
        }
        // Tighter gap above the avatar row; top-align avatar and name column.
        View photoCol = (View) f.photoBox.getParent();
        View photoRow = photoCol != null ? (View) photoCol.getParent() : null;
        if (photoRow != null) {
            ViewGroup.LayoutParams rowLp = photoRow.getLayoutParams();
            if (rowLp instanceof android.view.ViewGroup.MarginLayoutParams) {
                ((android.view.ViewGroup.MarginLayoutParams) rowLp).topMargin =
                        (int) (2 * f.density);
                photoRow.setLayoutParams(rowLp);
            }
            if (photoRow instanceof LinearLayout) {
                ((LinearLayout) photoRow).setGravity(android.view.Gravity.TOP);
            }
        }
        f.cardRoot.setTag(com.akin.wallet.R.id.tag_card_face_metrics, Boolean.TRUE);
    }

    /** Per-bind color refresh; one-time sizing/margins handled by caller. */
    private static void microLabelColor(@NonNull FaceViews f, TextView label,
                                        int colorRes, boolean layoutDone) {
        if (label == null) {
            return;
        }
        if (!layoutDone) {
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 8);
        }
        label.setTextColor(colorOf(f, colorRes));
        if (!layoutDone) {
            android.view.ViewGroup.LayoutParams lp = label.getLayoutParams();
            if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams mlp =
                        (android.view.ViewGroup.MarginLayoutParams) lp;
                if (mlp.topMargin > 0) {
                    mlp.topMargin = (int) (3 * f.density);
                    label.setLayoutParams(lp);
                }
            }
        }
    }

    private static void setTopMargin(View view, int topMarginDp, float density) {
        if (view == null) {
            return;
        }
        android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
            ((android.view.ViewGroup.MarginLayoutParams) lp).topMargin =
                    (int) (topMarginDp * density);
            view.setLayoutParams(lp);
        }
    }

    private static int colorOf(@NonNull FaceViews f, int res) {
        return f.res.getColor(res, null);
    }
}
