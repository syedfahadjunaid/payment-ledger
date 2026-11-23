package com.paymentledger.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MoneyTests {

    @Test
    void formatsWithTheCurrencysOwnNumberOfDecimals() {
        assertThat(Money.of(1250, "EUR").formatted()).isEqualTo("12.50");
        assertThat(Money.of(-5, "USD").formatted()).isEqualTo("-0.05");
        assertThat(Money.of(1250, "JPY").formatted()).isEqualTo("1250");   // yen have no minor unit
        assertThat(Money.describe(100_000_000, "GBP")).isEqualTo("1000000.00 GBP");
    }
}
