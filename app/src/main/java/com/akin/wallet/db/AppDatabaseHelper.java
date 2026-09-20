package com.akin.wallet.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;

import com.akin.wallet.model.BankCardModel;
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.security.DbKeyManager;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * Process-shared vault connection, owned by {@code VaultWarmCache}: exactly
 * one instance lives with the process, so no method here may close the
 * {@code SQLiteDatabase} — only {@code Cursor}s close per call. All vault
 * I/O is funneled through one serialized executor (see
 * {@code VaultWarmCache.executeVaultIo}), so reads and write transactions
 * never interleave on the shared connection.
 */
@SuppressWarnings("SpellCheckingInspection")
public final class AppDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "akin_wallet.db";
    // v1 schema: SQLCipher-encrypted vault (AES-256) with a Keystore-wrapped
    // random key, audit timestamp columns on every table, and indexes for
    // the dashboard/trash ordering plus the account-links join.
    // Encrypted-only: plaintext databases are not opened or converted, and
    // no older schema version is supported (development stage).
    private static final int DATABASE_VERSION = 1;

    private static final String TABLE_SOCIAL_ACCOUNTS = "social_accounts";
    private static final String COL_ID = "id";
    private static final String COL_PLATFORM = "platform";
    private static final String COL_USERNAME = "username";
    private static final String COL_PASSWORD = "password";
    private static final String COL_PIN = "pin";
    private static final String COL_ICON_RES = "icon_res";

    private static final String TABLE_ACCOUNT_LINKS = "account_links";
    private static final String COL_ACCOUNT_ID = "account_id";
    private static final String COL_LINKED_ACCOUNT_ID = "linked_account_id";

    private static final String TABLE_BANK_CARDS = "bank_cards";
    private static final String COL_CARD_ID = "id";
    private static final String COL_CARD_TYPE = "card_type";
    private static final String COL_CARD_NETWORK = "card_network";
    private static final String COL_BANK_NAME = "bank_name";
    private static final String COL_HOLDER_NAME = "holder_name";
    private static final String COL_CARD_NUMBER = "card_number";
    private static final String COL_EXPIRY = "expiry";
    private static final String COL_CVV = "cvv";
    private static final String COL_CARD_PIN = "pin";
    private static final String COL_DESIGN = "design";
    // Epoch millis (INTEGER): recency ordering, newest-first.
    private static final String COL_CREATED_AT = "created_at";
    private static final String COL_UPDATED_AT = "updated_at";
    // Soft-delete stamp (INTEGER epoch millis). 0/NULL = active, >0 = in Trash.
    private static final String COL_DELETED_AT = "deleted_at";

    private static final String TABLE_ID_CARDS = "id_cards";
    private static final String COL_ID_CARD_ID = "id";
    private static final String COL_ID_TYPE = "id_type";
    private static final String COL_ID_FIELDS_JSON = "fields_json";

    public AppDatabaseHelper(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME,
                toPassword(context.getApplicationContext()),
                null, DATABASE_VERSION, 0, null, null, false);
    }

    static {
        System.loadLibrary("sqlcipher");
    }

    /** Vault passphrase as a String; the char[] copy is zeroed immediately. */
    private static String toPassword(Context appContext) {
        char[] passphrase = DbKeyManager.getPassphrase(appContext);
        try {
            return new String(passphrase);
        } finally {
            Arrays.fill(passphrase, '\0');
        }
    }

    // No-arg getters are overrides here: the vault key is fixed at
    // construction, so every open below reuses it.
    @NonNull
    @Override
    public SQLiteDatabase getWritableDatabase() {
        return super.getWritableDatabase();
    }

    @NonNull
    @Override
    public SQLiteDatabase getReadableDatabase() {
        return super.getReadableDatabase();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createAllTables(db);
        createIndexes(db);
    }

    /** Full v1 schema. Shared by onCreate and onUpgrade so both land identical. */
    private static void createAllTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_SOCIAL_ACCOUNTS + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_PLATFORM + " TEXT, "
                + COL_USERNAME + " TEXT, "
                + COL_PASSWORD + " TEXT, "
                + COL_PIN + " TEXT, "
                + COL_ICON_RES + " INTEGER, "
                + COL_CREATED_AT + " INTEGER DEFAULT 0, "
                + COL_UPDATED_AT + " INTEGER DEFAULT 0, "
                + COL_DELETED_AT + " INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_ACCOUNT_LINKS + " ("
                + COL_ACCOUNT_ID + " INTEGER, "
                + COL_LINKED_ACCOUNT_ID + " INTEGER, "
                + "PRIMARY KEY (" + COL_ACCOUNT_ID + ", " + COL_LINKED_ACCOUNT_ID + "))");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_BANK_CARDS + " ("
                + COL_CARD_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_CARD_TYPE + " TEXT, "
                + COL_CARD_NETWORK + " TEXT, "
                + COL_BANK_NAME + " TEXT, "
                + COL_HOLDER_NAME + " TEXT, "
                + COL_CARD_NUMBER + " TEXT, "
                + COL_EXPIRY + " TEXT, "
                + COL_CVV + " TEXT, "
                + COL_CARD_PIN + " TEXT, "
                + COL_DESIGN + " INTEGER, "
                + COL_CREATED_AT + " INTEGER DEFAULT 0, "
                + COL_UPDATED_AT + " INTEGER DEFAULT 0, "
                + COL_DELETED_AT + " INTEGER DEFAULT 0)");
        // Audit columns live beside the row identity (id), never inside
        // fields_json-- — the JSON blob carries only per-type document data.
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_ID_CARDS + " ("
                + COL_ID_CARD_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_ID_TYPE + " TEXT, "
                + COL_ID_FIELDS_JSON + " TEXT, "
                + COL_CREATED_AT + " INTEGER DEFAULT 0, "
                + COL_UPDATED_AT + " INTEGER DEFAULT 0, "
                + COL_DELETED_AT + " INTEGER DEFAULT 0)");
    }

    /** v1: indexes for the dashboard/trash ordering and account-links joins. */
    private static void createIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_social_accounts_active ON " + TABLE_SOCIAL_ACCOUNTS
                + " (" + COL_DELETED_AT + ", " + COL_UPDATED_AT + " DESC, " + COL_ID + " DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bank_cards_active ON " + TABLE_BANK_CARDS
                + " (" + COL_DELETED_AT + ", " + COL_UPDATED_AT + " DESC, " + COL_CARD_ID + " DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_id_cards_active ON " + TABLE_ID_CARDS
                + " (" + COL_DELETED_AT + ", " + COL_UPDATED_AT + " DESC, " + COL_ID_CARD_ID + " DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_account_links_account ON " + TABLE_ACCOUNT_LINKS
                + " (" + COL_ACCOUNT_ID + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_account_links_linked ON " + TABLE_ACCOUNT_LINKS
                + " (" + COL_LINKED_ACCOUNT_ID + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 only: no per-version migrations. Any older database is brought
        // to the exact current schema (missing tables created, indexes
        // ensured), never patched column-by-column.
        createAllTables(db);
        createIndexes(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Development stage, v1 only: no versioned migrations are supported.
        // The current schema is ensured unconditionally.
        createAllTables(db);
        createIndexes(db);
    }

    // ---- Shared row mapping (single definition per table) ----

    private static SocialAccountModel mapSocialAccount(Cursor cursor) {
        return new SocialAccountModel(
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_PLATFORM)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_USERNAME)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_PASSWORD)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_PIN)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_ICON_RES)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT)));
    }

    private static BankCardModel mapBankCard(Cursor cursor) {
        return new BankCardModel(
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_CARD_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_CARD_TYPE)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_CARD_NETWORK)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_BANK_NAME)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_HOLDER_NAME)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_CARD_NUMBER)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_EXPIRY)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_CVV)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_CARD_PIN)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_DESIGN)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT)));
    }

    private static GovernmentIDModel mapIdCard(Cursor cursor) {
        return new GovernmentIDModel(
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_ID_CARD_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_ID_TYPE)),
                GovernmentIDModel.parseFieldsJson(
                        cursor.getString(cursor.getColumnIndexOrThrow(COL_ID_FIELDS_JSON))),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT)));
    }

    private static final String ACTIVE_SOCIAL_ACCOUNTS_WHERE =
            "(" + COL_DELETED_AT + " IS NULL OR " + COL_DELETED_AT + "=0)";
    private static final String TRASHED_WHERE =
            COL_DELETED_AT + " IS NOT NULL AND " + COL_DELETED_AT + " != 0";

    /** Honors a caller-supplied audit stamp when present, otherwise stamps now. */
    private static long stampOrNow(long stamp, long now) {
        return stamp > 0 ? stamp : now;
    }

    // ---- Social accounts ----

    private static ContentValues socialAccountValues(SocialAccountModel item, long now, boolean fresh) {
        ContentValues cv = new ContentValues();
        cv.put(COL_PLATFORM, item.getPlatform());
        cv.put(COL_USERNAME, item.getUsername());
        cv.put(COL_PASSWORD, item.getPassword());
        cv.put(COL_PIN, item.getPin());
        cv.put(COL_ICON_RES, item.getIconRes());
        if (fresh) {
            // Fresh rows are both created and updated now; honor
            // caller-supplied values when present.
            cv.put(COL_CREATED_AT, stampOrNow(item.getCreatedAt(), now));
            cv.put(COL_UPDATED_AT, stampOrNow(item.getUpdatedAt(), now));
            cv.put(COL_DELETED_AT, 0);
        } else {
            // created_at is immutable; every edit bumps updated_at.
            cv.put(COL_UPDATED_AT,
                    stampOrNow(item.getUpdatedAt(), now));
        }
        return cv;
    }

    private static long insertSocialAccount(SQLiteDatabase db, SocialAccountModel item) {
        return db.insert(TABLE_SOCIAL_ACCOUNTS, null,
                socialAccountValues(item, System.currentTimeMillis(), true));
    }

    /**
     * In-place update: preserves row id, created_at and every reverse link
     * pointing at this account.
     */
    private static void updateSocialAccount(SQLiteDatabase db, SocialAccountModel item) {
        db.update(TABLE_SOCIAL_ACCOUNTS,
                socialAccountValues(item, System.currentTimeMillis(), false),
                COL_ID + "=?", new String[]{String.valueOf(item.getId())});
    }

    /**
     * Atomic save: insert-or-update the row plus its forward links in one
     * transaction on one connection. Reverse links from other accounts are
     * untouched (only this row's outgoing edges are replaced).
     *
     * @return the row id (existing id for edits, new id for adds).
     */
    public long saveSocialAccountWithLinks(SocialAccountModel item, List<Integer> linkedIds) {
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            db.beginTransaction();
            try {
                long id;
                if (item.getId() > 0) {
                    updateSocialAccount(db, item);
                    id = item.getId();
                } else {
                    id = insertSocialAccount(db, item);
                    if (id < 0) {
                        return -1;
                    }
                }
                String[] args = {String.valueOf(id)};
                db.delete(TABLE_ACCOUNT_LINKS, COL_ACCOUNT_ID + "=?", args);
                if (linkedIds != null) {
                    for (Integer linkedId : linkedIds) {
                        if (linkedId == null || linkedId == id) {
                            continue;
                        }
                        ContentValues cv = new ContentValues();
                        cv.put(COL_ACCOUNT_ID, id);
                        cv.put(COL_LINKED_ACCOUNT_ID, linkedId);
                        db.insert(TABLE_ACCOUNT_LINKS, null, cv);
                    }
                }
                db.setTransactionSuccessful();
                return id;
            } finally {
                db.endTransaction();
            }
        }
    }

    public SocialAccountModel getSocialAccountById(int id) {
        // Shared process connection (VaultWarmCache): the Cursor closes per
        // query, the database itself never closes.
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_SOCIAL_ACCOUNTS
                + " WHERE " + COL_ID + "=?", new String[]{String.valueOf(id)})) {
            if (cursor.moveToFirst()) {
                return mapSocialAccount(cursor);
            }
            return null;
        }
    }

    public List<SocialAccountModel> getAllSocialAccounts() {
        List<SocialAccountModel> list = new ArrayList<>();
        // Active rows only: trashed rows live in Trash (Settings).
        // Newest-first by recency; id breaks ties on equal stamps.
        // Shared process connection (VaultWarmCache): the Cursor closes per
        // query, the database itself never closes.
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_SOCIAL_ACCOUNTS
                + " WHERE " + ACTIVE_SOCIAL_ACCOUNTS_WHERE
                + " ORDER BY " + COL_UPDATED_AT + " DESC, " + COL_ID + " DESC", null)) {
            if (cursor.moveToFirst()) {
                do {
                    list.add(mapSocialAccount(cursor));
                } while (cursor.moveToNext());
            }
            return list;
        }
    }

    /** Permanent delete for a set of ids in one transaction. */
    public void deleteSocialAccounts(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            db.beginTransaction();
            try {
                for (Integer id : ids) {
                    if (id == null) {
                        continue;
                    }
                    String[] both = {String.valueOf(id), String.valueOf(id)};
                    db.delete(TABLE_ACCOUNT_LINKS,
                            COL_ACCOUNT_ID + "=? OR " + COL_LINKED_ACCOUNT_ID + "=?", both);
                    db.delete(TABLE_SOCIAL_ACCOUNTS, COL_ID + "=?",
                            new String[]{String.valueOf(id)});
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            // Reclaim ciphertext pages so purged secrets don't linger on flash.
            try {
                db.execSQL("VACUUM");
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Soft-delete: stamps deleted_at so the account disappears from the
     * dashboard and appears in Settings > Trash. Links are kept so a
     * restore brings links back; permanent delete cleans them up.
     */
    public void moveSocialAccountToTrash(int id) {
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            ContentValues cv = new ContentValues();
            cv.put(COL_DELETED_AT, System.currentTimeMillis());
            db.update(TABLE_SOCIAL_ACCOUNTS, cv, COL_ID + "=?",
                    new String[]{String.valueOf(id)});
        }
    }

    /** Restores trashed accounts back to the dashboard (deleted_at = 0). */
    public void restoreSocialAccounts(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                cv.put(COL_DELETED_AT, 0);
                for (Integer id : ids) {
                    if (id == null) {
                        continue;
                    }
                    db.update(TABLE_SOCIAL_ACCOUNTS, cv, COL_ID + "=?",
                            new String[]{String.valueOf(id)});
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    /** Trashed social accounts, newest-deleted first. */
    public List<SocialAccountModel> getTrashedSocialAccounts() {
        List<SocialAccountModel> list = new ArrayList<>();
        // Shared process connection (VaultWarmCache): the Cursor closes per
        // query, the database itself never closes.
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_SOCIAL_ACCOUNTS
                + " WHERE " + TRASHED_WHERE
                + " ORDER BY " + COL_DELETED_AT + " DESC, " + COL_ID + " DESC", null)) {
            if (cursor.moveToFirst()) {
                do {
                    list.add(mapSocialAccount(cursor));
                } while (cursor.moveToNext());
            }
            return list;
        }
    }

    /**
     * Marks an account as recently used without touching its data: bumps only
     * updated_at to now so newest-first ordering picks it up. Called when the
     * account is opened for editing; the list re-sorts on the next refresh.
     */
    public void touchSocialAccountUpdatedAt(int id) {
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            ContentValues cv = new ContentValues();
            cv.put(COL_UPDATED_AT, System.currentTimeMillis());
            db.update(TABLE_SOCIAL_ACCOUNTS, cv, COL_ID + "=?",
                    new String[]{String.valueOf(id)});
        }
    }

    // ---- Bank cards ----

    private static ContentValues bankCardValues(BankCardModel item, long now, boolean fresh) {
        ContentValues cv = new ContentValues();
        cv.put(COL_CARD_TYPE, item.getCardType());
        cv.put(COL_CARD_NETWORK, item.getCardNetwork());
        cv.put(COL_BANK_NAME, item.getBankName());
        cv.put(COL_HOLDER_NAME, item.getHolderName());
        cv.put(COL_CARD_NUMBER, item.getCardNumber());
        cv.put(COL_EXPIRY, item.getExpiry());
        cv.put(COL_CVV, item.getCvv());
        cv.put(COL_CARD_PIN, item.getPin());
        cv.put(COL_DESIGN, item.getDesign());
        if (fresh) {
            // Fresh rows are both created and updated now; honor
            // caller-supplied values (e.g. imports) when present.
            cv.put(COL_CREATED_AT, stampOrNow(item.getCreatedAt(), now));
            cv.put(COL_UPDATED_AT, stampOrNow(item.getUpdatedAt(), now));
            cv.put(COL_DELETED_AT, 0);
        } else {
            // created_at is immutable: never overwritten.
            cv.put(COL_UPDATED_AT,
                    stampOrNow(item.getUpdatedAt(), now));
        }
        return cv;
    }

    public void insertBankCard(BankCardModel item) {
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            db.insert(TABLE_BANK_CARDS, null,
                    bankCardValues(item, System.currentTimeMillis(), true));
        }
    }

    public BankCardModel getBankCardById(int id) {
        // Shared process connection (VaultWarmCache): the Cursor closes per
        // query, the database itself never closes.
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_BANK_CARDS
                + " WHERE " + COL_CARD_ID + "=?", new String[]{String.valueOf(id)})) {
            if (cursor.moveToFirst()) {
                return mapBankCard(cursor);
            }
            return null;
        }
    }

    public List<BankCardModel> getAllBankCards() {
        List<BankCardModel> list = new ArrayList<>();
        // Active rows only; trashed cards live in Trash.
        // Newest-first by recency; id breaks ties on equal stamps.
        // Shared process connection (VaultWarmCache): the Cursor closes per
        // query, the database itself never closes.
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_BANK_CARDS
                + " WHERE " + ACTIVE_SOCIAL_ACCOUNTS_WHERE
                + " ORDER BY " + COL_UPDATED_AT + " DESC, " + COL_CARD_ID + " DESC", null)) {
            if (cursor.moveToFirst()) {
                do {
                    list.add(mapBankCard(cursor));
                } while (cursor.moveToNext());
            }
            return list;
        }
    }

    public void updateBankCard(BankCardModel item) {
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            db.update(TABLE_BANK_CARDS,
                    bankCardValues(item, System.currentTimeMillis(), false),
                    COL_CARD_ID + "=?", new String[]{String.valueOf(item.getId())});
        }
    }

    /** Permanent delete for a set of ids in one transaction. */
    public void deleteBankCards(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            db.beginTransaction();
            try {
                for (Integer id : ids) {
                    if (id == null) {
                        continue;
                    }
                    db.delete(TABLE_BANK_CARDS, COL_CARD_ID + "=?",
                            new String[]{String.valueOf(id)});
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            try {
                db.execSQL("VACUUM");
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Soft-delete: moves the card to Trash (Settings). The dashboard hides it
     * until restored or permanently deleted.
     */
    public void moveBankCardToTrash(int id) {
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            ContentValues cv = new ContentValues();
            cv.put(COL_DELETED_AT, System.currentTimeMillis());
            db.update(TABLE_BANK_CARDS, cv, COL_CARD_ID + "=?",
                    new String[]{String.valueOf(id)});
        }
    }

    /** Restores trashed cards back to the dashboard. */
    public void restoreBankCards(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                cv.put(COL_DELETED_AT, 0);
                for (Integer id : ids) {
                    if (id == null) {
                        continue;
                    }
                    db.update(TABLE_BANK_CARDS, cv, COL_CARD_ID + "=?",
                            new String[]{String.valueOf(id)});
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    /** Trashed bank cards, newest-deleted first. */
    public List<BankCardModel> getTrashedBankCards() {
        List<BankCardModel> list = new ArrayList<>();
        // Shared process connection (VaultWarmCache): the Cursor closes per
        // query, the database itself never closes.
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_BANK_CARDS
                + " WHERE " + TRASHED_WHERE
                + " ORDER BY " + COL_DELETED_AT + " DESC, " + COL_CARD_ID + " DESC", null)) {
            if (cursor.moveToFirst()) {
                do {
                    list.add(mapBankCard(cursor));
                } while (cursor.moveToNext());
            }
            return list;
        }
    }

    /**
     * Marks a card as recently used without touching its data: bumps only
     * updated_at to now so newest-first ordering picks it up. Called on
     * dashboard tap; the list re-sorts on the next refresh.
     */
    public void touchBankCardUpdatedAt(int id) {
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            ContentValues cv = new ContentValues();
            cv.put(COL_UPDATED_AT, System.currentTimeMillis());
            db.update(TABLE_BANK_CARDS, cv, COL_CARD_ID + "=?",
                    new String[]{String.valueOf(id)});
        }
    }

    // ---- ID cards ----

    private static ContentValues idCardValues(GovernmentIDModel item, long now, boolean fresh) {
        ContentValues cv = new ContentValues();
        cv.put(COL_ID_TYPE, item.getIdType());
        cv.put(COL_ID_FIELDS_JSON, item.getFieldsJson());
        if (fresh) {
            // Honor caller-supplied values (e.g. imports) when present.
            // The JSON blob is untouched — stamps live in their own columns.
            cv.put(COL_CREATED_AT, stampOrNow(item.getCreatedAt(), now));
            cv.put(COL_UPDATED_AT, stampOrNow(item.getUpdatedAt(), now));
            cv.put(COL_DELETED_AT, 0);
        } else {
            // created_at is immutable: never overwritten.
            cv.put(COL_UPDATED_AT,
                    stampOrNow(item.getUpdatedAt(), now));
        }
        return cv;
    }

    public void insertIdCard(GovernmentIDModel item) {
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            db.insert(TABLE_ID_CARDS, null,
                    idCardValues(item, System.currentTimeMillis(), true));
        }
    }

    public GovernmentIDModel getIdCardById(int id) {
        // Shared process connection (VaultWarmCache): the Cursor closes per
        // query, the database itself never closes.
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_ID_CARDS
                + " WHERE " + COL_ID_CARD_ID + "=?", new String[]{String.valueOf(id)})) {
            if (cursor.moveToFirst()) {
                return mapIdCard(cursor);
            }
            return null;
        }
    }

    public List<GovernmentIDModel> getAllIdCards() {
        List<GovernmentIDModel> list = new ArrayList<>();
        // Active rows only; trashed IDs live in Trash.
        // Newest-first by recency, matching bank cards and social accounts;
        // id breaks ties on equal stamps.
        // Shared process connection (VaultWarmCache): the Cursor closes per
        // query, the database itself never closes.
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_ID_CARDS
                + " WHERE " + ACTIVE_SOCIAL_ACCOUNTS_WHERE
                + " ORDER BY " + COL_UPDATED_AT + " DESC, " + COL_ID_CARD_ID + " DESC", null)) {
            if (cursor.moveToFirst()) {
                do {
                    list.add(mapIdCard(cursor));
                } while (cursor.moveToNext());
            }
            return list;
        }
    }

    public void updateIdCard(GovernmentIDModel item) {
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            db.update(TABLE_ID_CARDS,
                    idCardValues(item, System.currentTimeMillis(), false),
                    COL_ID_CARD_ID + "=?", new String[]{String.valueOf(item.getId())});
        }
    }

    /**
     * Marks an ID as recently used without touching its data: bumps only
     * updated_at to now so newest-first ordering picks it up. Called on
     * dashboard tap, mirroring the bank-card and social-account touch helpers;
     * list re-sorts on the next refresh.
     */
    public void touchIdCardUpdatedAt(int id) {
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            ContentValues cv = new ContentValues();
            cv.put(COL_UPDATED_AT, System.currentTimeMillis());
            db.update(TABLE_ID_CARDS, cv, COL_ID_CARD_ID + "=?",
                    new String[]{String.valueOf(id)});
        }
    }

    /** Permanent delete for a set of ids in one transaction. */
    public void deleteIdCards(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            db.beginTransaction();
            try {
                for (Integer id : ids) {
                    if (id == null) {
                        continue;
                    }
                    db.delete(TABLE_ID_CARDS, COL_ID_CARD_ID + "=?",
                            new String[]{String.valueOf(id)});
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            try {
                db.execSQL("VACUUM");
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Soft-delete: moves the ID to Trash (Settings). The dashboard hides it
     * until restored or permanently deleted.
     */
    public void moveIdCardToTrash(int id) {
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            ContentValues cv = new ContentValues();
            cv.put(COL_DELETED_AT, System.currentTimeMillis());
            db.update(TABLE_ID_CARDS, cv, COL_ID_CARD_ID + "=?",
                    new String[]{String.valueOf(id)});
        }
    }

    /** Restores trashed IDs back to the dashboard. */
    public void restoreIdCards(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // Shared process connection (VaultWarmCache): never closed per call.
        // The block scopes the write; transactions still end in finally.
        SQLiteDatabase db = this.getWritableDatabase();
        {
            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                cv.put(COL_DELETED_AT, 0);
                for (Integer id : ids) {
                    if (id == null) {
                        continue;
                    }
                    db.update(TABLE_ID_CARDS, cv, COL_ID_CARD_ID + "=?",
                            new String[]{String.valueOf(id)});
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    /** Trashed government IDs, newest-deleted first. */
    public List<GovernmentIDModel> getTrashedIdCards() {
        List<GovernmentIDModel> list = new ArrayList<>();
        // Shared process connection (VaultWarmCache): the Cursor closes per
        // query, the database itself never closes.
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_ID_CARDS
                + " WHERE " + TRASHED_WHERE
                + " ORDER BY " + COL_DELETED_AT + " DESC, " + COL_ID_CARD_ID + " DESC", null)) {
            if (cursor.moveToFirst()) {
                do {
                    list.add(mapIdCard(cursor));
                } while (cursor.moveToNext());
            }
            return list;
        }
    }

    public List<Integer> getLinkedAccountIds(long accountId) {
        List<Integer> list = new ArrayList<>();
        // Shared process connection (VaultWarmCache): the Cursor closes per
        // query, the database itself never closes.
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT " + COL_LINKED_ACCOUNT_ID + " FROM " + TABLE_ACCOUNT_LINKS
                + " WHERE " + COL_ACCOUNT_ID + "=?", new String[]{String.valueOf(accountId)})) {
            if (cursor.moveToFirst()) {
                do {
                    list.add(cursor.getInt(0));
                } while (cursor.moveToNext());
            }
            return list;
        }
    }
}
