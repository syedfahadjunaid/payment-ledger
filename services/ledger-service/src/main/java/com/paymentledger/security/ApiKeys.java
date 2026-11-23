package com.paymentledger.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Stripe-style secret API keys: {@code sk_test_} + 32 random bytes.
 *
 * <p>Only a SHA-256 hash is stored. A fast hash is fine here (unlike passwords, which need a slow
 * one such as bcrypt) because the key has 256 bits of randomness: there is nothing to guess.
 * Lookup is by hash, so a database leak doesn't leak usable keys.
 */
public final class ApiKeys {

    public static final String PREFIX = "sk_test_";
    private static final int VISIBLE_CHARS = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {}

    public static String generate() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    /** "sk_test_AbCd": enough to recognise a key in logs and the audit trail, useless to an attacker. */
    public static String displayPrefix(String key) {
        return key.substring(0, Math.min(key.length(), PREFIX.length() + VISIBLE_CHARS));
    }

    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
