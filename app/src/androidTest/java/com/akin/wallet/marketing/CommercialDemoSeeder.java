package com.akin.wallet.marketing;

import android.content.ContentValues;
import android.database.Cursor;

import com.akin.wallet.AkinWallet;
import com.akin.wallet.FixtureWallet;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.json.JSONObject;

/** Marketing-only fictional records. This class is never packaged in the app. */
public final class CommercialDemoSeeder {
    private static final long TIMESTAMP = 1767225600000L;
    public static final String NAME = "Juan Dela Cruz";

    private CommercialDemoSeeder() { }

    /** Must execute on the vault worker with the invocation-specific fixture unlocked. */
    public static void reseed(AkinWallet wallet) {
        if (!(wallet instanceof FixtureWallet)) {
            throw new SecurityException("Marketing data requires the isolated FixtureWallet application");
        }
        SQLiteDatabase database = wallet.getDbHelper().getWritableDatabase();
        database.beginTransaction();
        try {
            // FixtureWallet prefixes this database with a fresh invocation UUID.
            for (String table : new String[]{"account_links", "social_accounts", "bank_cards", "id_cards"})
                database.delete(table, null, null);
            ContentValues national = row(1, 40);
            national.put("id_type", "National ID");
            national.put("fields_json", fields(
                    "psn", "DEMO-PH-ID-001", "full_name", NAME, "sex", "Male",
                    "birth_date", "19950612", "issue_date", "20250101", "blood_type", "Unknown",
                    "marital_status", "Single", "place_of_birth", "Demo City, Philippines",
                    "present_address", "Fictional demo address — not a real residence"));
            database.insertOrThrow("id_cards", null, national);
            ContentValues passport = row(2, 50);
            passport.put("id_type", "Passport");
            passport.put("fields_json", fields("passport_no", "DEMO0001", "full_name", NAME,
                    "nationality", "Filipino", "sex", "Male", "birth_date", "19950612",
                    "issue_date", "20250101", "expiry_date", "20300101",
                    "place_of_birth", "Demo City, Philippines", "issuing_authority", "DEMO OFFICE"));
            database.insertOrThrow("id_cards", null, passport);
            bank(database, 1, "Demo Bank", "DEMO-CARD-4821", "Visa", 0, 40);
            bank(database, 2, "Sample Bank", "DEMO-CARD-7314", "MasterCard", 1, 30);
            String[] platforms = {"Facebook", "Instagram", "TikTok", "LinkedIn"};
            for (int index = 0; index < platforms.length; index++) {
                ContentValues social = row(index + 1, 40 - index);
                social.put("platform", platforms[index]);
                social.put("username", "juan.demo@example.invalid");
                social.put("password", "");
                social.put("pin", "");
                database.insertOrThrow("social_accounts", null, social);
            }
            ContentValues link = new ContentValues();
            link.put("account_id", 1); link.put("linked_account_id", 2);
            database.insertOrThrow("account_links", null, link);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        wallet.getVaultStore().invalidate();
        verify(database);
    }

    private static ContentValues row(long id, int ordering) {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("created_at", TIMESTAMP);
        values.put("updated_at", TIMESTAMP + ordering);
        values.put("deleted_at", 0);
        return values;
    }

    private static void bank(SQLiteDatabase database, long id, String bank, String placeholder,
                             String network, int design, int ordering) {
        ContentValues values = row(id, ordering);
        values.put("card_type", "Debit");
        values.put("card_network", network);
        values.put("bank_name", bank);
        values.put("holder_name", NAME);
        // Deliberately NOT a payment-card number. Bypasses only the production DAO,
        // within this test fixture; production validation remains exactly 16 digits.
        values.put("card_number", placeholder);
        values.put("expiry", "");
        values.put("cvv", "");
        values.put("pin", "");
        values.put("design", design);
        database.insertOrThrow("bank_cards", null, values);
    }

    private static String fields(String... values) {
        JSONObject fields = new JSONObject();
        try {
            for (int index = 0; index < values.length; index += 2)
                fields.put(values[index], values[index + 1]);
        } catch (org.json.JSONException impossible) {
            throw new AssertionError(impossible);
        }
        return fields.toString();
    }

    private static void verify(SQLiteDatabase database) {
        String[] checks = {
                "SELECT count(*)=2 FROM id_cards",
                "SELECT count(*)=2 FROM bank_cards",
                "SELECT count(*)=4 FROM social_accounts",
                "SELECT count(*)=0 FROM bank_cards WHERE card_number NOT LIKE 'DEMO-CARD-%' OR cvv<>'' OR pin<>'' OR expiry<>''",
                "SELECT count(*)=0 FROM social_accounts WHERE username<>'juan.demo@example.invalid' OR password<>'' OR pin<>''",
                "SELECT count(*)=0 FROM id_cards WHERE fields_json NOT LIKE '%DEMO%'"
        };
        for (String query : checks) {
            try (Cursor cursor = database.rawQuery(query, null)) {
                if (!cursor.moveToFirst() || cursor.getInt(0) != 1)
                    throw new AssertionError("Fictional marketing fixture validation failed");
            }
        }
    }
}
