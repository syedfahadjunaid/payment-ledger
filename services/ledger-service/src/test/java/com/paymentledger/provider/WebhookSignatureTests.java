package com.paymentledger.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookSignatureTests {

    private static final String SECRET = "test-webhook-secret-0123456789abcdef0123";
    private static final byte[] BODY = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
    private static final Instant SIGNED_AT = Instant.ofEpochSecond(1732300000L);
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    /** The same vector as the simulator's SignatureTests (computed with openssl), so both sides agree. */
    @Test
    void matchesTheSharedTestVector() {
        assertThat(WebhookSignature.header(SECRET, SIGNED_AT.getEpochSecond(), BODY))
                .isEqualTo("t=1732300000,v1=89e100bfa4766858c1aa34603a9549b830dc5e71a9c49e01467d3c36d5290eb2");
    }

    @Test
    void acceptsAFreshCorrectSignature() {
        String header = WebhookSignature.header(SECRET, SIGNED_AT.getEpochSecond(), BODY);

        assertThat(WebhookSignature.verify(SECRET, header, BODY, SIGNED_AT.plusSeconds(60), TOLERANCE)).isEqualTo(SIGNED_AT);
    }

    @Test
    void acceptsAnyOfSeveralSignaturesWhileSecretsRotate() {
        String header = "t=1732300000,v1=00ff,v1=" + WebhookSignature.header(SECRET, 1732300000L, BODY).split("v1=")[1];

        assertThat(WebhookSignature.verify(SECRET, header, BODY, SIGNED_AT, TOLERANCE)).isEqualTo(SIGNED_AT);
    }

    @Test
    void rejectsEverythingElse() {
        String good = WebhookSignature.header(SECRET, SIGNED_AT.getEpochSecond(), BODY);

        assertRejected(null, BODY, SIGNED_AT, "missing");
        assertRejected("v1=abc", BODY, SIGNED_AT, "malformed");
        assertRejected("t=notanumber,v1=abc", BODY, SIGNED_AT, "malformed");
        assertRejected(good, BODY, SIGNED_AT.plus(Duration.ofMinutes(6)), "tolerance");          // replayed later
        assertRejected(good, "{\"id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8), SIGNED_AT, "does not match");
        assertRejected(WebhookSignature.header("another-secret-0123456789abcdef0123456789", SIGNED_AT.getEpochSecond(), BODY),
                BODY, SIGNED_AT, "does not match");
        assertRejected("t=1732300000,v1=zz-not-hex", BODY, SIGNED_AT, "does not match");
    }

    private static void assertRejected(String header, byte[] body, Instant now, String reason) {
        assertThatThrownBy(() -> WebhookSignature.verify(SECRET, header, body, now, TOLERANCE))
                .isInstanceOf(InvalidWebhookSignatureException.class)
                .hasMessageContaining(reason);
    }
}
