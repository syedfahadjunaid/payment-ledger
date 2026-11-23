package com.paymentledger.common;

import com.paymentledger.config.LedgerProperties;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Business rules every amount and currency in a request must satisfy. */
@Component
public class Amounts {

    private final Set<String> supportedCurrencies;
    private final long maxAmountMinor;

    public Amounts(LedgerProperties props) {
        this.supportedCurrencies = Set.copyOf(props.supportedCurrencies());
        this.maxAmountMinor = props.maxAmountMinor();
    }

    public void requireSupportedCurrency(String currency, String field) {
        if (!supportedCurrencies.contains(currency)) {
            throw new BusinessRuleException("unsupported_currency",
                    "currency must be one of " + supportedCurrencies.stream().sorted().toList(), field);
        }
    }

    /** Positive is checked by bean validation; this is the per-operation upper limit. */
    public void requireWithinLimit(long amountMinor, String currency, String field) {
        if (amountMinor > maxAmountMinor) {
            throw new BusinessRuleException("amount_too_large",
                    "the largest amount allowed is " + Money.describe(maxAmountMinor, currency), field);
        }
    }
}
