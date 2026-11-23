package com.paymentledger.providersim;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SignatureTests {

    /**
     * A fixed vector, computed independently with
     * {@code printf '%s' '1732300000.{"id":"evt_1"}' | openssl dgst -sha256 -hmac <secret>}.
     * The ledger's WebhookSignatureTests checks the same vector, so signer and verifier agree.
     */
    @Test
    void matchesTheSharedTestVector() {
        String header = Signature.header("test-webhook-secret-0123456789abcdef0123", 1732300000L,
                "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(header).isEqualTo("t=1732300000,v1=89e100bfa4766858c1aa34603a9549b830dc5e71a9c49e01467d3c36d5290eb2");
    }
}
