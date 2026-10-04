package com.akin.wallet.db;

import android.content.Context;
import android.database.Cursor;

import androidx.test.platform.app.InstrumentationRegistry;

import com.akin.wallet.model.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

import org.junit.*;

import static org.junit.Assert.*;

/**
 * Every test owns a uniquely named synthetic encrypted database; never opens the user's vault.
 */
public class AppDatabaseHelperTest {
    private Context context;
    private String name;
    private AppDatabaseHelper helper;

    @Before
    public void create() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "test-v1-" + UUID.randomUUID() + ".db";
        helper = new AppDatabaseHelper(context, name, "synthetic-test-key".getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void cleanup() {
        if (helper != null) helper.close();
        if (name != null) assertTrue(context.deleteDatabase(name));
    }

    private SocialAccountModel account(long id, String username) {
        return new SocialAccountModel(id, "Facebook", username, "fixture-password", "0123", 0, 0, 0);
    }

    private long add(String username) {
        return helper.saveSocialAccountWithLinks(account(-1, username), List.of());
    }

    @Test
    public void projectionsExcludeSecretsButDetailsRetainThem() {
        long id = add("Ada");
        assertEquals("", helper.getAllSocialAccounts().get(0).getPassword());
        assertEquals("", helper.getAllSocialAccounts().get(0).getPin());
        assertEquals("fixture-password", helper.getSocialAccountById(id).getPassword());
    }

    @Test
    public void failingLinkRollsBackRecord() {
        try {
            helper.saveSocialAccountWithLinks(account(-1, "Ada"), List.of(99L));
            fail("Expected foreign key failure");
        } catch (android.database.sqlite.SQLiteException expected) {
        }
        assertTrue(helper.getAllSocialAccounts().isEmpty());
    }

    @Test
    public void softDeleteAndRestoreKeepDirectedLinksAndRecency() {
        long owner = add("Owner"), target = add("Target");
        helper.saveSocialAccountWithLinks(account(owner, "Owner"), List.of(target));
        long recency = helper.getSocialAccountById(target).getUpdatedAt();
        helper.moveSocialAccountToTrash(target);
        helper.saveSocialAccountWithLinks(account(owner, "Owner changed"), List.of());
        assertEquals(List.of(target), helper.getLinkedAccountIds(owner));
        helper.restoreSelection(List.of(), List.of(), List.of(target));
        assertEquals(recency, helper.getSocialAccountById(target).getUpdatedAt());
        assertEquals(List.of(target), helper.getLinkedAccountIds(owner));
        assertTrue(helper.getLinkedAccountIds(target).isEmpty());
    }

    @Test
    public void invalidMixedRestoreRollsBackAllChanges() {
        long id = add("Ada");
        helper.moveSocialAccountToTrash(id);
        try {
            helper.restoreSelection(List.of(), List.of(), List.of(id, 99L));
            fail("Expected missing row");
        } catch (android.database.sqlite.SQLiteException expected) {
        }
        assertNull(helper.getSocialAccountById(id));
        assertEquals(1, helper.getTrashedSocialAccounts().size());
    }

    @Test
    public void permanentDeleteCascadesLinks() {
        long owner = add("Owner"), target = add("Target");
        helper.saveSocialAccountWithLinks(account(owner, "Owner"), List.of(target));
        helper.moveSocialAccountToTrash(target);
        helper.deleteSelection(List.of(), List.of(), List.of(target));
        assertTrue(helper.getLinkedAccountIds(owner).isEmpty());
    }

    @Test
    public void bankAndGovernmentCrudRoundTrip() {
        long bank = helper.insertBankCard(new BankCardModel("Debit", "Visa", "Fixture Bank", "Ada", "0001222233334444", "1229", "123", "0123", 2));
        assertEquals("", helper.getAllBankCards().get(0).getCvv());
        assertEquals("123", helper.getBankCardById(bank).getCvv());
        long id = helper.insertIdCard(new GovernmentIDModel("Passport", Map.of("passport_no", "P1234567A")));
        assertEquals("P1234567A", helper.getIdCardById(id).getFieldsRef().get("passport_no"));
        helper.moveBankCardToTrash(bank);
        helper.moveIdCardToTrash(id);
        helper.restoreSelection(List.of(id), List.of(bank), List.of());
        assertNotNull(helper.getBankCardById(bank));
        assertNotNull(helper.getIdCardById(id));
    }

    @Test
    public void cardWritesRejectInvalidLengthsWithoutChangingStoredRecords() {
        String valid = "0001222233334444";
        long id = helper.insertBankCard(new BankCardModel("Debit", "Visa", "Fixture Bank",
                "Ada", valid, "1229", "123", "0123", 0));
        for (String invalid : new String[]{"411111111111111", "41111111111111111", "411111111111111X"}) {
            assertThrows(IllegalArgumentException.class, () -> helper.insertBankCard(new BankCardModel(
                    "Debit", "Visa", "Other Bank", "Ada", invalid, "1229", "123", "0123", 0)));
            assertThrows(IllegalArgumentException.class, () -> helper.updateBankCard(new BankCardModel(
                    id, "Debit", "Visa", "Other Bank", "Ada", invalid, "1229", "123", "0123", 0, 0, 0)));
            assertEquals(1, helper.getAllBankCards().size());
            assertEquals(valid, helper.getBankCardById(id).getCardNumber());
            assertEquals("Fixture Bank", helper.getBankCardById(id).getBankName());
        }
    }

    @Test
    public void identityAndSecurityPragmasAreConfigured() {
        try (Cursor c = helper.getReadableDatabase().rawQuery("PRAGMA foreign_keys", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(1, c.getInt(0));
        }
        try (Cursor c = helper.getReadableDatabase().rawQuery("PRAGMA secure_delete", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(1, c.getInt(0));
        }
        try (Cursor c = helper.getReadableDatabase().rawQuery("PRAGMA application_id", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(1095456305, c.getInt(0));
        }
    }
}
