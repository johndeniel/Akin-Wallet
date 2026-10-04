package com.akin.wallet.model;

import org.junit.Test;

import static org.junit.Assert.*;

public class BankCardModelTest {
    @Test
    public void onlyExactlySixteenAsciiDigitsAreAccepted() {
        assertTrue(BankCardModel.isValidCardNumber("0001222233334444"));
        assertTrue(BankCardModel.isValidCardNumber("4111111111111111"));
        for (String invalid : new String[]{null, "", "411111111111111", "41111111111111111",
                "4111111111111111111", "411111111111111X", "4111 1111 1111 1111",
                "４１１１１１１１１１１１１１１１"}) {
            assertFalse(String.valueOf(invalid), BankCardModel.isValidCardNumber(invalid));
        }
    }
}
