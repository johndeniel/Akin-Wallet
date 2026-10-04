package com.akin.wallet.model;


import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable stored government ID; field contracts and rendering have separate owners.
 */
public final class GovernmentIDModel {

    /**
     * Row id for drafts that have never been persisted.
     */
    public static final int UNSET_ID = -1;

    private final long id;
    private final String idType;
    private final Map<String, String> fields;
    private final long createdAt;
    private final long updatedAt;

    /**
     * Unsaved draft; the database assigns the id and timestamps on insert.
     */
    public GovernmentIDModel(String idType, Map<String, String> fields) {
        this(UNSET_ID, idType, fields, 0, 0);
    }

    /**
     * Full constructor carrying audit timestamps alongside the row identity.
     * New entries pass {@code 0, 0} and let the DB stamp {@code now};
     * updates preserve {@code createdAt} and pass {@code 0} for
     * {@code updatedAt} so the DB bumps recency.
     */
    public GovernmentIDModel(long id, String idType, Map<String, String> fields,
                             long createdAt, long updatedAt) {
        this.id = id;
        this.idType = idType != null ? idType : "";
        this.fields = java.util.Collections.unmodifiableMap(fields != null
                ? new LinkedHashMap<>(fields) : new LinkedHashMap<>());
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long getId() {
        return id;
    }

    @NonNull
    public String getIdType() {
        return idType;
    }

    /**
     * Defensive copy — callers cannot mutate internal state.
     */
    @NonNull
    public Map<String, String> getFields() {
        return new LinkedHashMap<>(fields);
    }

    /**
     * Read-only view for hot paths (bind/search). No copy — caller must not
     * mutate. Use {@link #getFields()} when a mutable snapshot is needed.
     */
    @NonNull
    public Map<String, String> getFieldsRef() {
        return fields;
    }

    /**
     * Row creation time, epoch millis. 0 when unset.
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * Last recency bump, epoch millis. Drives newest-first ordering.
     */
    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Serialize document fields to JSON for SQLite storage.
     * Timestamps are deliberately excluded: they live in their own columns.
     */
    public String getFieldsJson() {
        JSONObject jsonDocument = new JSONObject();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            putField(jsonDocument, field.getKey(), field.getValue());
        }
        return jsonDocument.toString();
    }

    /**
     * JSONObject.put only throws for null keys or non-finite numbers; our keys
     * are non-null strings and values are normalized to "", so this is
     * provably non-throwing and intentionally silent.
     */
    private static void putField(JSONObject jsonDocument, String key, String value) {
        try {
            jsonDocument.put(key, value != null ? value : "");
        } catch (JSONException impossible) {
            throw new AssertionError("String keys never fail JSONObject.put", impossible);
        }
    }

    /**
     * Parse JSON fields; malformed stored objects fail closed to prevent silent overwrites.
     */
    public static Map<String, String> parseFieldsJson(String jsonPayload) {
        Map<String, String> parsedFields = new LinkedHashMap<>();
        if (jsonPayload == null || jsonPayload.trim().isEmpty()) {
            return parsedFields;
        }
        try {
            JSONObject jsonDocument = new JSONObject(jsonPayload);
            Iterator<String> keys = jsonDocument.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                parsedFields.put(key, jsonDocument.optString(key, ""));
            }
        } catch (JSONException malformed) {
            throw new IllegalStateException("Malformed government ID data", malformed);
        }
        return parsedFields;
    }

    /**
     * Value equality across every column (backs DiffUtil content checks).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GovernmentIDModel)) {
            return false;
        }
        GovernmentIDModel that = (GovernmentIDModel) o;
        return id == that.id
                && createdAt == that.createdAt
                && updatedAt == that.updatedAt
                && Objects.equals(idType, that.idType)
                && Objects.equals(fields, that.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, idType, fields, createdAt, updatedAt);
    }

}
