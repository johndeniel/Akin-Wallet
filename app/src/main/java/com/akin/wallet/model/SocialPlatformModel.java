package com.akin.wallet.model;

import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.akin.wallet.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single source of truth for platform branding.
 *
 * <p>Why this exists: {@code R.drawable} ints are reassigned on every build,
 * (title says Google, icon shows Facebook). Platform <b>names</b> are stable,
 * so every display site resolves the icon from the name here at bind time and
 * only trusts the stored int for unknown/custom platforms. Old rows heal
 * automatically with no DB migration.
 *
 * <p>Each platform is registered exactly once in the static block below; the
 * icon map and the picker catalog are both derived from it.
 */
public final class SocialPlatformModel {

    private SocialPlatformModel() {
    }

    /** Picker row: branding + display url. */
    public static final class Option {
        private final int iconRes;
        private final String name;
        private final String url;

        public Option(int iconRes, String name, String url) {
            this.iconRes = iconRes;
            this.name = name;
            this.url = url;
        }

        public int getIconRes() {
            return iconRes;
        }

        public String getName() {
            return name;
        }

        public String getUrl() {
            return url;
        }
    }

    private static final Map<String, Integer> ICON_BY_PLATFORM = new HashMap<>();
    /** {name, url} pairs in picker order; icons come from {@link #ICON_BY_PLATFORM}. */
    private static final List<String[]> CATALOG_ENTRIES = new ArrayList<>();

    static {
        registerPlatform(R.drawable.binance, "Binance", "binance.com");
        registerPlatform(R.drawable.bdo, "BDO", "bdo.com.ph");
        registerPlatform(R.drawable.bpi, "BPI", "bpi.com.ph");
        registerPlatform(R.drawable.discord, "Discord", "discord.com");
        registerPlatform(R.drawable.dropbox, "Dropbox", "dropbox.com");
        registerPlatform(R.drawable.facebook, "Facebook", "facebook.com");
        registerPlatform(R.drawable.gcash, "GCash", "gcash.com");
        registerPlatform(R.drawable.gitlab, "GitLab", "gitlab.com");
        registerPlatform(R.drawable.github, "GitHub", "github.com");
        registerPlatform(R.drawable.gmail, "Gmail", "gmail.com");
        registerPlatform(R.drawable.gotyme, "GoTyme", "gotyme.com");
        registerPlatform(R.drawable.google, "Google", "google.com");
        registerPlatform(R.drawable.instagram, "Instagram", "instagram.com");
        registerPlatform(R.drawable.itunes, "iTunes", "itunes.com");
        //noinspection SpellCheckingInspection
        registerPlatform(R.drawable.lazada, "Lazada", "lazada.com");
        registerPlatform(R.drawable.line, "LINE", "line.me");
        registerPlatform(R.drawable.linkedin, "LinkedIn", "linkedin.com");
        registerPlatform(R.drawable.maribank, "MariBank", "maribank.com.ph");
        registerPlatform(R.drawable.maya, "Maya", "maya.ph");
        registerPlatform(R.drawable.messenger, "Messenger", "messenger.com");
        registerPlatform(R.drawable.microsoft, "Microsoft", "microsoft.com");
        registerPlatform(R.drawable.netflix, "Netflix", "netflix.com");
        registerPlatform(R.drawable.paypal, "PayPal", "paypal.com");
        registerPlatform(R.drawable.pinterest, "Pinterest", "pinterest.com");
        registerPlatform(R.drawable.rcbc, "RCBC", "rcbc.com.ph");
        registerPlatform(R.drawable.reddit, "Reddit", "reddit.com");
        //noinspection SpellCheckingInspection
        registerPlatform(R.drawable.shoopee, "Shopee", "shopee.com");
        registerPlatform(R.drawable.slack, "Slack", "slack.com");
        registerPlatform(R.drawable.snapchat, "Snapchat", "snapchat.com");
        registerPlatform(R.drawable.soundcloud, "SoundCloud", "soundcloud.com");
        registerPlatform(R.drawable.spotify, "Spotify", "spotify.com");
        registerPlatform(R.drawable.steam, "Steam", "store.steampowered.com");
        registerPlatform(R.drawable.telegram, "Telegram", "telegram.org");
        registerPlatform(R.drawable.tiktok, "TikTok", "tiktok.com");
        registerPlatform(R.drawable.tinder, "Tinder", "tinder.com");
        registerPlatform(R.drawable.unionbank, "UnionBank", "unionbank.com.ph");
        registerPlatform(R.drawable.viber, "Viber", "viber.com");
        registerPlatform(R.drawable.wattpad, "Wattpad", "wattpad.com");
        registerPlatform(R.drawable.whatsapp, "WhatsApp", "whatsapp.com");
        registerPlatform(R.drawable.wise, "Wise", "wise.com");
        registerPlatform(R.drawable.x, "X", "x.com");
        registerPlatform(R.drawable.youtube, "YouTube", "youtube.com");
        registerPlatform(R.drawable.zoom, "Zoom", "zoom.us");
    }

    private static void registerPlatform(int iconRes, String platformName, String displayUrl) {
        ICON_BY_PLATFORM.put(normalizePlatformKey(platformName), iconRes);
        CATALOG_ENTRIES.add(new String[]{platformName, displayUrl});
    }

    private static String normalizePlatformKey(@Nullable String platformName) {
        return platformName == null ? "" : platformName.trim().toLowerCase(Locale.US);
    }

    /**
     * Drawable for a platform name from this build's resources. Never 0:
     * unknown or blank names fall back to the generic social icon.
     */
    public static int iconFor(@Nullable String platformName) {
        return iconFor(platformName, R.drawable.ic_social);
    }

    /**
     * Drawable for a platform name, falling back to a previously stored res id
     * (valid when saved by this same install) and finally the generic icon.
     * Use this when binding rows read from the DB.
     */
    public static int iconFor(@Nullable String platformName, int storedIconRes) {
        Integer mappedIconRes = ICON_BY_PLATFORM.get(normalizePlatformKey(platformName));
        if (mappedIconRes != null) {
            return mappedIconRes;
        }
        return storedIconRes != 0 ? storedIconRes : R.drawable.ic_social;
    }

    /**
     * Binds a resolved icon into a row, degrading to the generic icon if even
     * the fallback res is stale. One line per adapter instead of a repeated
     * try/catch at every bind site.
     */
    public static void bindIcon(ImageView iconView, @Nullable String platformName, int storedIconRes) {
        try {
            iconView.setImageResource(iconFor(platformName, storedIconRes));
        } catch (Exception fallbackToGeneric) {
            iconView.setImageResource(R.drawable.ic_social);
        }
    }

    /**
     * Full picker catalog (icon + name + url). Fresh list per call because the
     * selection adapter filters it in place.
     */
    public static List<Option> catalog() {
        List<Option> platforms = new ArrayList<>(CATALOG_ENTRIES.size());
        for (String[] catalogEntry : CATALOG_ENTRIES) {
            String platformName = catalogEntry[0];
            String displayUrl = catalogEntry[1];
            platforms.add(new Option(iconFor(platformName), platformName, displayUrl));
        }
        return platforms;
    }
}
