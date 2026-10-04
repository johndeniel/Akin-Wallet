package com.akin.wallet.model;

import java.util.Objects;

/**
 * Social account entry (platform account: username, password, PIN).
 *
 * <p>Immutable value object shared by the dashboard, the social-account form,
 * and {@code social_accounts} persistence. Unsaved drafts carry {@code id = -1}
 * and {@code 0} timestamps; the database stamps real values on insert.
 */
public final class SocialAccountModel {

    /**
     * Row id for drafts that have never been persisted.
     */
    public static final int UNSET_ID = -1;

    private final long id;
    private final String platform;
    private final String username;
    private final String password;
    private final String pin;
    private final int iconRes;
    private final long createdAt;
    private final long updatedAt;

    /**
     * Unsaved draft; the database assigns the id and timestamps on insert.
     */
    public SocialAccountModel(String platform, String username, String password, String pin,
                              int iconRes, long createdAt, long updatedAt) {
        this(UNSET_ID, platform, username, password, pin, iconRes, createdAt, updatedAt);
    }

    /**
     * Stored row with its database identity and audit timestamps.
     */
    public SocialAccountModel(long id, String platform, String username, String password, String pin,
                              int iconRes, long createdAt, long updatedAt) {
        this.id = id;
        this.platform = platform;
        this.username = username;
        this.password = password;
        this.pin = pin;
        this.iconRes = iconRes;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long getId() {
        return id;
    }

    public String getPlatform() {
        return platform;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getPin() {
        return pin;
    }

    public int getIconRes() {
        return iconRes;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Value equality across every column (backs DiffUtil content checks).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SocialAccountModel)) {
            return false;
        }
        SocialAccountModel that = (SocialAccountModel) o;
        return id == that.id
                && iconRes == that.iconRes
                && createdAt == that.createdAt
                && updatedAt == that.updatedAt
                && Objects.equals(platform, that.platform)
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password)
                && Objects.equals(pin, that.pin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, platform, username, password, pin, iconRes,
                createdAt, updatedAt);
    }
}
