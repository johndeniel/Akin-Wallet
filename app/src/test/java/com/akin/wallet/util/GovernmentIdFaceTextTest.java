package com.akin.wallet.util;

import com.akin.wallet.model.GovernmentIdTypes;

import java.util.*;

import org.junit.Test;

import static org.junit.Assert.*;

public class GovernmentIdFaceTextTest {
    private String number(String type, String key, String value) {
        return GovernmentIdFaceText.displayNumber(GovernmentIdTypes.forName(type), Map.of(key, value));
    }

    @Test
    public void nationalNumberGroupsFourDigits() {
        assertEquals("1234-5678-9012-3456", number("National ID", "psn", "1234567890123456"));
    }

    @Test
    public void tinGroupsThreeDigits() {
        assertEquals("123-456-789-012", number("TIN ID", "tinNumber", "123456789012"));
    }

    @Test
    public void philHealthGroupsThreeDigits() {
        assertEquals("123-456-789-012", number("PhilHealth ID", "philHealthNumber", "123456789012"));
    }

    @Test
    public void sssUsesTwoSevenOneGrouping() {
        assertEquals("34-1234567-8", number("SSS", "ss_number", "3412345678"));
    }

    @Test
    public void licenseUsesThreeTwoSixGrouping() {
        assertEquals("N01-23-456789", number("Driver's License", "license_no", "n0123456789"));
    }

    @Test
    public void passportUppercases() {
        assertEquals("P1234567A", number("Passport", "passport_no", "p1234567a"));
    }

    @Test
    public void eightDigitDateAddsSeparators() {
        assertEquals("2000-01-02", GovernmentIdFaceText.displayDate("20000102"));
    }

    @Test
    public void unknownDateShapePassesThrough() {
        assertEquals("unknown", GovernmentIdFaceText.displayDate("unknown"));
    }

    @Test
    public void blankDateUsesDash() {
        assertEquals("—", GovernmentIdFaceText.displayDate(""));
    }

    @Test
    public void typeOrderIsPreserved() {
        assertArrayEquals(new String[]{"National ID", "Driver's License", "Passport", "SSS", "PhilHealth ID", "TIN ID"}, GovernmentIdTypes.getTypeNames());
    }
}
