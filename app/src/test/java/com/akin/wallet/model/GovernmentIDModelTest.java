package com.akin.wallet.model;

import java.util.*;

import org.junit.Test;

import static org.junit.Assert.*;

public class GovernmentIDModelTest {
    @Test
    public void constructionCopiesFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", "Ada");
        GovernmentIDModel record = new GovernmentIDModel("Passport", fields);
        fields.put("name", "Changed");
        assertEquals("Ada", record.getFieldsRef().get("name"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void fieldsReferenceIsReadOnly() {
        new GovernmentIDModel("Passport", Map.of("name", "Ada")).getFieldsRef().put("name", "Changed");
    }

    @Test
    public void readOnlyViewIsReused() {
        GovernmentIDModel record = new GovernmentIDModel("Passport", Map.of("name", "Ada"));
        assertSame(record.getFieldsRef(), record.getFieldsRef());
    }

    @Test
    public void mutableCopyDoesNotChangeRecord() {
        GovernmentIDModel record = new GovernmentIDModel("Passport", Map.of("name", "Ada"));
        record.getFields().clear();
        assertEquals(1, record.getFieldsRef().size());
    }
}
