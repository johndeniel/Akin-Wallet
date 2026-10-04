package com.akin.wallet.util;

import com.akin.wallet.model.*;

import java.util.*;

import org.junit.Test;

import static org.junit.Assert.*;

public class DashboardSearchTest {
    private final SocialAccountModel newest = new SocialAccountModel(4L, "Facebook", "Ada", "hidden-secret", "6721", 0, 1, 4);
    private final SocialAccountModel older = new SocialAccountModel(3L, "Instagram", "ada.second", "", "", 0, 1, 3);
    private final BankCardModel card = new BankCardModel(8L, "Debit", "Visa", "Test Bank", "Ada", "0001 2222 3333 4444", "1229", "987", "9999", 0, 1, 2);
    private final GovernmentIDModel document = new GovernmentIDModel(10L, "Passport", Map.of("first_name", "Ada", "passport_number", "P012345"), 1, 2);

    private DashboardSearch.Index index() {
        return DashboardSearch.buildIndex(List.of(document), List.of(card), List.of(newest, older));
    }

    private DashboardSearch.Result find(String query) {
        return DashboardSearch.search(index(), DashboardSearch.normalizeQuery(query));
    }

    @Test
    public void emptyQueryPreservesInputOrder() {
        assertEquals(List.of(newest, older), find("  ").accounts);
    }

    @Test
    public void queryTrimsAndIgnoresCase() {
        assertEquals(List.of(newest), find(" FaCeBoOk ").accounts);
    }

    @Test
    public void usernameSubstringPreservesOrder() {
        assertEquals(List.of(newest, older), find("ada").accounts);
    }

    @Test
    public void mixedTextAndDigitsRetainsNumericAlternative() {
        assertEquals(List.of(card), find("unrelated 2222").cards);
    }

    @Test
    public void fullCardNumberRemainsSearchable() {
        assertEquals(List.of(card), find("00012222").cards);
    }

    @Test
    public void governmentIdFieldIsSearchable() {
        assertEquals(List.of(document), find("p0123").ids);
    }

    @Test
    public void governmentIdTypeIsSearchable() {
        assertEquals(List.of(document), find("passport").ids);
    }

    @Test
    public void secretsAreNotIndexed() {
        assertTrue(find("hidden-secret").accounts.isEmpty());
        assertTrue(find("6721").accounts.isEmpty());
        assertTrue(find("987").cards.isEmpty());
        assertTrue(find("9999").cards.isEmpty());
    }

    @Test
    public void absentQueryReturnsNothing() {
        DashboardSearch.Result r = find("does-not-exist");
        assertTrue(r.ids.isEmpty());
        assertTrue(r.cards.isEmpty());
        assertTrue(r.accounts.isEmpty());
    }

    @Test
    public void nullInputsAreEmpty() {
        DashboardSearch.Result r = DashboardSearch.search(DashboardSearch.buildIndex(null, null, null), DashboardSearch.normalizeQuery(null));
        assertTrue(r.ids.isEmpty());
        assertTrue(r.cards.isEmpty());
        assertTrue(r.accounts.isEmpty());
    }

    @Test
    public void resultMutationDoesNotAlterIndex() {
        DashboardSearch.Index i = index();
        DashboardSearch.search(i, DashboardSearch.normalizeQuery("")).accounts.clear();
        assertEquals(2, DashboardSearch.search(i, DashboardSearch.normalizeQuery("")).accounts.size());
    }

    @Test
    public void longIdentitySurvivesSearch() {
        SocialAccountModel a = new SocialAccountModel(4_294_967_296L, "Facebook", "large", "", "", 0, 1, 1);
        assertEquals(a.getId(), DashboardSearch.search(DashboardSearch.buildIndex(null, null, List.of(a)), DashboardSearch.normalizeQuery("large")).accounts.get(0).getId());
    }

    @Test
    public void incrementalAccountRefreshSharesUnchangedPreparedSections() {
        DashboardSearch.Index original = index();
        SocialAccountModel added = new SocialAccountModel(5L, "Instagram", "New username", "", "", 0, 1, 5);
        DashboardSearch.Index refreshed = DashboardSearch.buildIndex(original,
                original.idRows(), original.cardRows(), List.of(added, newest));
        assertSame(original.ids, refreshed.ids);
        assertSame(original.cards, refreshed.cards);
        assertSame(original.idRows(), refreshed.idRows());
        assertSame(original.cardRows(), refreshed.cardRows());
        assertNotSame(original.accounts, refreshed.accounts);
        assertEquals(List.of(added), DashboardSearch.search(refreshed,
                DashboardSearch.normalizeQuery("new username")).accounts);
        assertEquals(List.of(added, newest), DashboardSearch.search(refreshed,
                DashboardSearch.normalizeQuery("")).accounts);
        assertEquals(List.of(newest, older), DashboardSearch.search(original,
                DashboardSearch.normalizeQuery("")).accounts);
    }

    @Test
    public void unchangedSnapshotsReuseEntireIndexAndRowsCannotBeMutated() {
        DashboardSearch.Index original = index();
        assertSame(original, DashboardSearch.buildIndex(original,
                original.idRows(), original.cardRows(), original.accountRows()));
        assertThrows(UnsupportedOperationException.class, () -> original.accountRows().clear());
        assertThrows(UnsupportedOperationException.class, () -> original.idRows().clear());
        assertThrows(UnsupportedOperationException.class, () -> original.cardRows().clear());
    }

    @Test
    public void deletingAndChangingOtherSectionsCannotReuseStalePreparedFields() {
        DashboardSearch.Index original = index();
        GovernmentIDModel changedId = new GovernmentIDModel(10L, "Passport", Map.of("first_name", "Grace"), 1, 3);
        DashboardSearch.Index refreshed = DashboardSearch.buildIndex(original,
                List.of(changedId), List.of(), original.accountRows());
        assertSame(original.accounts, refreshed.accounts);
        assertTrue(DashboardSearch.search(refreshed, DashboardSearch.normalizeQuery("2222")).cards.isEmpty());
        assertEquals(List.of(changedId), DashboardSearch.search(refreshed, DashboardSearch.normalizeQuery("grace")).ids);
        assertTrue(DashboardSearch.search(refreshed, DashboardSearch.normalizeQuery("p0123")).ids.isEmpty());
    }

    @Test
    public void documentRefreshReusesEqualRowsButReindexesEveryChangedValue() {
        DashboardSearch.Index original = index();
        GovernmentIDModel unchanged = new GovernmentIDModel(10L, "Passport", document.getFieldsRef(), 1, 2);
        DashboardSearch.Index equivalent = DashboardSearch.buildIndex(original, List.of(unchanged),
                original.cardRows(), original.accountRows());
        assertSame(original.ids.get(0), equivalent.ids.get(0));
        GovernmentIDModel changed = new GovernmentIDModel(10L, "Passport", Map.of("first_name", "Grace"), 1, 2);
        DashboardSearch.Index edited = DashboardSearch.buildIndex(equivalent, List.of(changed),
                equivalent.cardRows(), equivalent.accountRows());
        assertNotSame(equivalent.ids.get(0), edited.ids.get(0));
        assertEquals(List.of(changed), DashboardSearch.search(edited, DashboardSearch.normalizeQuery("grace")).ids);
        assertTrue(DashboardSearch.search(edited, DashboardSearch.normalizeQuery("p0123")).ids.isEmpty());
    }
}
