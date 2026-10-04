package com.akin.wallet.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;

import com.akin.wallet.model.BankCardModel;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.model.SocialPlatformModel;
import com.akin.wallet.security.DbKeyManager;

import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

/**
 * Authoritative encrypted V1 schema. Access exclusively through VaultStore's I/O thread.
 */
public final class AppDatabaseHelper extends SQLiteOpenHelper {
    private static final String NAME = "akin_wallet.db";
    private static final int VERSION = 1;
    private static final int APPLICATION_ID = 1095456305;
    private static final String SOCIAL = "social_accounts";
    private static final String CARDS = "bank_cards";
    private static final String IDS = "id_cards";
    private static final String LINKS = "account_links";
    private static final String SOCIAL_DETAIL = "id,platform,username,password,pin,created_at,updated_at";
    private static final String SOCIAL_OVERVIEW = "id,platform,username,'' AS password,'' AS pin,created_at,updated_at";
    private static final String CARD_DETAIL = "id,card_type,card_network,bank_name,holder_name,card_number,expiry,cvv,pin,design,created_at,updated_at";
    private static final String CARD_OVERVIEW = "id,card_type,card_network,bank_name,holder_name,card_number,expiry,'' AS cvv,'' AS pin,design,created_at,updated_at";
    private static final String ID_PROJECTION = "id,id_type,fields_json,created_at,updated_at";
    private final byte[] passphrase;
    private final Context context;

    static {
        System.loadLibrary("sqlcipher");
    }

    public AppDatabaseHelper(Context context) {
        this(context.getApplicationContext(), DbKeyManager.getPassphraseBytes(context));
    }

    private AppDatabaseHelper(Context context, byte[] passphrase) {
        this(context, NAME, passphrase);
    }

    AppDatabaseHelper(Context context, String databaseName, byte[] passphrase) {
        super(context, databaseName, passphrase, null, VERSION, 0,
                (database, error) -> {
                    throw new SQLiteException("Vault is unreadable; data was preserved", error);
                },
                null, false);
        this.passphrase = passphrase;
        this.context = context.getApplicationContext();
    }

    @Override
    public synchronized void close() {
        try {
            super.close();
        } finally {
            Arrays.fill(passphrase, (byte) 0);
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
        try (Cursor cursor = db.rawQuery("PRAGMA secure_delete=ON", null)) {
            if (!cursor.moveToFirst() || cursor.getInt(0) != 1) {
                throw new SQLiteException("Secure deletion unavailable");
            }
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(context.getResources().openRawResource(
                        com.akin.wallet.R.raw.vault_schema_v1), java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder schema = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) schema.append(line).append('\n');
            for (String statement : schema.toString().split(";")) {
                if (!statement.trim().isEmpty()) db.execSQL(statement);
            }
        } catch (java.io.IOException failure) {
            throw new SQLiteException("Unable to initialize schema V1", failure);
        }
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("PRAGMA application_id", null)) {
            if (!cursor.moveToFirst() || cursor.getInt(0) != APPLICATION_ID) {
                throw new SQLiteException("Unsupported development schema; data was preserved");
            }
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new SQLiteException("Only schema V1 is supported");
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new SQLiteException("Only schema V1 is supported");
    }

    private static SocialAccountModel social(Cursor c) {
        String platform = c.getString(1);
        return new SocialAccountModel(c.getLong(0), platform, c.getString(2), c.getString(3),
                c.getString(4), SocialPlatformModel.iconFor(platform), c.getLong(5), c.getLong(6));
    }

    private static BankCardModel card(Cursor c) {
        return new BankCardModel(c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8),
                c.getInt(9), c.getLong(10), c.getLong(11));
    }

    private static GovernmentIDModel governmentId(Cursor c) {
        return new GovernmentIDModel(c.getLong(0), c.getString(1),
                GovernmentIDModel.parseFieldsJson(c.getString(2)), c.getLong(3), c.getLong(4));
    }

    private <T> List<T> overview(String table, String projection, boolean trash, Function<Cursor, T> mapper) {
        List<T> rows = new ArrayList<>();
        String where = trash ? "deleted_at>0" : "deleted_at=0";
        String order = trash ? "deleted_at DESC,id DESC" : "updated_at DESC,id DESC";
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + projection + " FROM " + table
                + " WHERE " + where + " ORDER BY " + order, null)) {
            while (c.moveToNext()) rows.add(mapper.apply(c));
        }
        return List.copyOf(rows);
    }

    private <T> T byId(String table, String projection, long id, Function<Cursor, T> mapper) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + projection + " FROM " + table
                + " WHERE id=? AND deleted_at=0", new String[]{Long.toString(id)})) {
            return c.moveToFirst() ? mapper.apply(c) : null;
        }
    }

    public List<SocialAccountModel> getAllSocialAccounts() {
        return overview(SOCIAL, SOCIAL_OVERVIEW, false, AppDatabaseHelper::social);
    }

    public List<SocialAccountModel> getTrashedSocialAccounts() {
        return overview(SOCIAL, SOCIAL_OVERVIEW, true, AppDatabaseHelper::social);
    }

    public List<BankCardModel> getAllBankCards() {
        return overview(CARDS, CARD_OVERVIEW, false, AppDatabaseHelper::card);
    }

    public List<BankCardModel> getTrashedBankCards() {
        return overview(CARDS, CARD_OVERVIEW, true, AppDatabaseHelper::card);
    }

    public List<GovernmentIDModel> getAllIdCards() {
        return overview(IDS, ID_PROJECTION, false, AppDatabaseHelper::governmentId);
    }

    public List<GovernmentIDModel> getTrashedIdCards() {
        return overview(IDS, ID_PROJECTION, true, AppDatabaseHelper::governmentId);
    }

    public SocialAccountModel getSocialAccountById(long id) {
        return byId(SOCIAL, SOCIAL_DETAIL, id, AppDatabaseHelper::social);
    }

    public BankCardModel getBankCardById(long id) {
        return byId(CARDS, CARD_DETAIL, id, AppDatabaseHelper::card);
    }

    public GovernmentIDModel getIdCardById(long id) {
        return byId(IDS, ID_PROJECTION, id, AppDatabaseHelper::governmentId);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static void audit(ContentValues values, long created, boolean fresh) {
        long now = System.currentTimeMillis();
        if (fresh) {
            values.put("created_at", created > 0 ? created : now);
            values.put("deleted_at", 0);
        }
        values.put("updated_at", now);
    }

    private static ContentValues socialValues(SocialAccountModel item, boolean fresh) {
        ContentValues v = new ContentValues();
        v.put("platform", text(item.getPlatform()));
        v.put("username", text(item.getUsername()));
        v.put("password", text(item.getPassword()));
        v.put("pin", text(item.getPin()));
        audit(v, item.getCreatedAt(), fresh);
        return v;
    }

    private static ContentValues cardValues(BankCardModel item, boolean fresh) {
        if (!BankCardModel.isValidCardNumber(item.getCardNumber())) {
            throw new IllegalArgumentException("Card number must contain exactly 16 digits");
        }
        ContentValues v = new ContentValues();
        v.put("card_type", item.getCardType());
        v.put("card_network", item.getCardNetwork());
        v.put("bank_name", item.getBankName());
        v.put("holder_name", item.getHolderName());
        v.put("card_number", item.getCardNumber());
        v.put("expiry", item.getExpiry());
        v.put("cvv", item.getCvv());
        v.put("pin", item.getPin());
        v.put("design", item.getDesign());
        audit(v, item.getCreatedAt(), fresh);
        return v;
    }

    private static ContentValues idValues(GovernmentIDModel item, boolean fresh) {
        ContentValues v = new ContentValues();
        v.put("id_type", item.getIdType());
        v.put("fields_json", item.getFieldsJson());
        audit(v, item.getCreatedAt(), fresh);
        return v;
    }

    private static void one(int count) {
        if (count != 1) throw new SQLiteException("Record not available");
    }

    private static void update(SQLiteDatabase db, String table, long id, ContentValues values, String predicate) {
        one(db.update(table, values, "id=? AND " + predicate, new String[]{Long.toString(id)}));
    }

    public long insertBankCard(BankCardModel item) {
        return getWritableDatabase().insertOrThrow(CARDS, null, cardValues(item, true));
    }

    public long insertIdCard(GovernmentIDModel item) {
        return getWritableDatabase().insertOrThrow(IDS, null, idValues(item, true));
    }

    public void updateBankCard(BankCardModel item) {
        update(getWritableDatabase(), CARDS, item.getId(), cardValues(item, false), "deleted_at=0");
    }

    public void updateIdCard(GovernmentIDModel item) {
        update(getWritableDatabase(), IDS, item.getId(), idValues(item, false), "deleted_at=0");
    }

    public long saveSocialAccountWithLinks(SocialAccountModel item, List<Long> linkedIds) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long id = item.getId();
            if (id > 0) update(db, SOCIAL, id, socialValues(item, false), "deleted_at=0");
            else id = db.insertOrThrow(SOCIAL, null, socialValues(item, true));
            // Invisible trashed relationships remain available after restore.
            db.delete(LINKS, "account_id=? AND linked_account_id IN (SELECT id FROM "
                    + SOCIAL + " WHERE deleted_at=0)", new String[]{Long.toString(id)});
            if (linkedIds != null) for (Long linked : new HashSet<>(linkedIds)) {
                if (linked == null || linked == id)
                    throw new SQLiteException("Invalid account relationship");
                ContentValues v = new ContentValues();
                v.put("account_id", id);
                v.put("linked_account_id", linked);
                db.insertOrThrow(LINKS, null, v);
            }
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public List<Long> getLinkedAccountIds(long id) {
        List<Long> links = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT linked_account_id FROM " + LINKS
                + " WHERE account_id=? ORDER BY linked_account_id", new String[]{Long.toString(id)})) {
            while (c.moveToNext()) links.add(c.getLong(0));
        }
        return links;
    }

    private void stamp(String table, long id, String column, long value) {
        ContentValues v = new ContentValues();
        v.put(column, value);
        update(getWritableDatabase(), table, id, v, "deleted_at=0");
    }

    public void touchSocialAccountUpdatedAt(long id) {
        stamp(SOCIAL, id, "updated_at", System.currentTimeMillis());
    }

    public void touchBankCardUpdatedAt(long id) {
        stamp(CARDS, id, "updated_at", System.currentTimeMillis());
    }

    public void touchIdCardUpdatedAt(long id) {
        stamp(IDS, id, "updated_at", System.currentTimeMillis());
    }

    public void moveSocialAccountToTrash(long id) {
        stamp(SOCIAL, id, "deleted_at", System.currentTimeMillis());
    }

    public void moveBankCardToTrash(long id) {
        stamp(CARDS, id, "deleted_at", System.currentTimeMillis());
    }

    public void moveIdCardToTrash(long id) {
        stamp(IDS, id, "deleted_at", System.currentTimeMillis());
    }

    private static void selected(SQLiteDatabase db, String table, List<Long> ids, boolean delete) {
        ContentValues v = new ContentValues();
        v.put("deleted_at", 0);
        for (Long id : new HashSet<>(ids)) {
            if (id == null) throw new SQLiteException("Invalid selection");
            if (delete)
                one(db.delete(table, "id=? AND deleted_at>0", new String[]{Long.toString(id)}));
            else update(db, table, id, v, "deleted_at>0");
        }
    }

    private void selection(List<Long> ids, List<Long> cards, List<Long> accounts, boolean delete) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            selected(db, IDS, ids, delete);
            selected(db, CARDS, cards, delete);
            selected(db, SOCIAL, accounts, delete);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        // Compaction is maintenance, not part of the committed deletion result.
        if (delete) try {
            db.execSQL("VACUUM");
        } catch (SQLiteException ignored) {
        }
    }

    public void restoreSelection(List<Long> ids, List<Long> cards, List<Long> accounts) {
        selection(ids, cards, accounts, false);
    }

    public void deleteSelection(List<Long> ids, List<Long> cards, List<Long> accounts) {
        selection(ids, cards, accounts, true);
    }
}
