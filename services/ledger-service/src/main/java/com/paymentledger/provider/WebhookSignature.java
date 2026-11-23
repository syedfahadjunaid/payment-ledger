package com.paymentledger.provider;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies (and, for tests and the simulator, creates) webhook signatures. Same scheme as Stripe:
 *
 * <pre>
 *   Provider-Signature: t=1732300000,v1=5257a869e7ecebeda32affa62cdca3fa51cad7e77a0e56ff536d0ce8e108d8bd
 *   v1 = hex( HMAC-SHA256( secret, t + "." + raw request body ) )
 * </pre>
 *
 * <ul>
 *   <li>HMAC with a shared secret proves the provider sent it and nobody changed the body.</li>
 *   <li>The timestamp is signed too, and must be recent: an attacker who captured a webhook can't
 *       replay it next week. (Replays inside the window are caught by the event-id dedup.)</li>
 *   <li>The raw bytes are signed, so verify <em>before</em> parsing JSON; re-serialising could
 *       change whitespace or field order and break the signature.</li>
 *   <li>Comparison is constant-time ({@link MessageDigest#isEqual}).</li>
 * </ul>
 */
public final class WebhookSignature {

    public static final String HEADER = "Provider-Signature";

    private WebhookSignature() {}

    public static String header(String secret, long timestampSeconds, byte[] body) {
        return "t=" + timestampSeconds + ",v1=" + HexFormat.of().formatHex(hmac(secret, timestampSeconds, body));
    }

    /** @throws InvalidWebhookSignatureException if the header is missing, malformed, stale or wrong */
    public static Instant verify(String secret, String header, byte[] body, Instant now, Duration tolerance) {
        if (header == null || header.isBlank()) {
            throw new InvalidWebhookSignatureException("missing " + HEADER + " header");
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>(); // several v1 values are allowed while rotating secrets
        for (String part : header.split(",")) {
            String[] keyValue = part.trim().split("=", 2);
            if (keyValue.length != 2) {
                continue;
            }
            if (keyValue[0].equals("t")) {
                timestamp = parseLong(keyValue[1]);
            } else if (keyValue[0].equals("v1")) {
                signatures.add(keyValue[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new InvalidWebhookSignatureException("malformed " + HEADER + " header");
        }
        Instant signedAt = Instant.ofEpochSecond(timestamp);
        if (Duration.between(signedAt, now).abs().compareTo(tolerance) > 0) {
            throw new InvalidWebhookSignatureException("signature timestamp outside the " + tolerance + " tolerance");
        }
        byte[] expected = hmac(secret, timestamp, body);
        for (String candidate : signatures) {
            if (MessageDigest.isEqual(expected, parseHex(candidate))) {
                return signedAt;
            }
        }
        throw new InvalidWebhookSignatureException("signature does not match");
    }

    private static byte[] hmac(String secret, long timestampSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestampSeconds + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is always available in the JDK", e);
        }
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] parseHex(String value) {
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }
}
