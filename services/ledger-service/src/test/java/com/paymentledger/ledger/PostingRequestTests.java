package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingRequestTests {

    private final UUID merchant = UUID.randomUUID();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void debitsMustEqualCredits() {
        assertThatThrownBy(() -> posting(Leg.debit(a, 500), Leg.credit(b, 499)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must balance");
    }

    @Test
    void needsTwoLegsAndNoZeroOrNegativeAmounts() {
        assertThatThrownBy(() -> posting(Leg.debit(a, 500))).hasMessageContaining("at least two legs");
        assertThatThrownBy(() -> Leg.debit(a, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Leg.credit(a, -5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOverflowIsAnErrorNotAWrongSum() {
        assertThatThrownBy(() -> posting(Leg.debit(a, Long.MAX_VALUE), Leg.debit(a, 1), Leg.credit(b, 1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void accountsAreLockedInASortedOrder() {
        PostingRequest request = posting(Leg.debit(b, 300), Leg.credit(a, 100), Leg.credit(a, 200));

        // Every posting locks accounts in this order, so two postings can never deadlock
        assertThat(List.copyOf(request.netAmountByAccount().keySet())).isSorted();
        assertThat(request.netAmountByAccount()).containsOnly(entry(a, -300L), entry(b, 300L));
    }

    @Test
    void normalBalanceDecidesWhatADebitMeans() {
        assertThat(NormalBalance.DEBIT.balanceChange(500)).isEqualTo(500);    // asset grows
        assertThat(NormalBalance.CREDIT.balanceChange(500)).isEqualTo(-500);  // liability shrinks
        assertThat(NormalBalance.CREDIT.balanceChange(-500)).isEqualTo(500);  // a credit grows a wallet
    }

    private PostingRequest posting(Leg... legs) {
        return new PostingRequest(merchant, TransactionType.TRANSFER, UUID.randomUUID(), "EUR", null, List.of(legs));
    }
}
