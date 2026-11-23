package com.paymentledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class FakeProviderConfiguration {

    /** {@code @Primary}: wins over the real HTTP client wherever a PaymentProviderClient is injected. */
    @Bean
    @Primary
    FakePaymentProvider fakePaymentProvider() {
        return new FakePaymentProvider();
    }
}
