package com.paymentledger.idempotency;

import com.paymentledger.common.BadRequestException;
import com.paymentledger.common.BusinessRuleException;
import com.paymentledger.common.ConflictException;
import com.paymentledger.security.ApiKeys;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes money-moving requests safe to retry. A client that timed out doesn't know whether its
 * transfer happened; it simply sends the same request again with the same {@code Idempotency-Key}
 * header and gets the original answer, while the money moves only once.
 *
 * <p>How it works, all inside <em>one</em> database transaction:
 * <ol>
 *   <li>{@code INSERT} the key first. If another request with the same key is still running,
 *       Postgres makes this insert wait on the primary key until that request commits or rolls
 *       back. No polling, no "in progress" state that could get stuck.</li>
 *   <li>If the insert succeeded, run the operation (e.g. post the transfer to the ledger) and store
 *       its response in the key row before COMMIT. Money moved + response recorded, or neither.</li>
 *   <li>If the key already existed: same request (fingerprint) -> replay the stored response;
 *       different request -> 422, because reusing a key for something else is a client bug.</li>
 * </ol>
 * A request that fails (e.g. 422 insufficient funds) rolls back the key too, so the client may
 * fix the problem and retry with the same key.
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,255}");

    /** The operation's response, and whether it came from an earlier request with the same key. */
    public record Result<T>(T body, boolean replayed) {

        public ResponseEntity<T> toResponse(HttpStatus status) {
            return ResponseEntity.status(status).header(REPLAYED_HEADER, String.valueOf(replayed)).body(body);
        }
    }

    private record StoredKey(String fingerprint, String responseBody) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public IdempotencyService(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** SHA-256 of method, path and the parsed body. Field order in the JSON doesn't matter. */
    public String fingerprint(HttpServletRequest http, Object requestBody) {
        return ApiKeys.sha256Hex(http.getMethod() + " " + http.getRequestURI() + "\n" + json.writeValueAsString(requestBody));
    }

    /**
     * Runs {@code operation} at most once per (merchant, key). The operation joins this transaction,
     * so it must not commit on its own.
     */
    @Transactional
    public <T> Result<T> execute(UUID merchantId, String key, String fingerprint, Class<T> responseType,
                                 Supplier<T> operation) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new BadRequestException(HEADER + " must be 1-255 characters: letters, digits, '.', '_', ':' or '-'");
        }
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys (merchant_id, idempotency_key, request_fingerprint, created_at)
                        VALUES (:merchantId, :key, :fingerprint, :createdAt)
                        ON CONFLICT (merchant_id, idempotency_key) DO NOTHING""")
                .param("merchantId", merchantId)
                .param("key", key)
                .param("fingerprint", fingerprint)
                .param("createdAt", clock.instant().atOffset(ZoneOffset.UTC))
                .update();

        if (inserted == 1) {
            T body = operation.get();
            jdbc.sql("""
                            UPDATE idempotency_keys SET response_body = :body
                             WHERE merchant_id = :merchantId AND idempotency_key = :key""")
                    .param("body", json.writeValueAsString(body))
                    .param("merchantId", merchantId)
                    .param("key", key)
                    .update();
            return new Result<>(body, false);
        }

        StoredKey stored = load(merchantId, key).orElseThrow(() -> new ConflictException("idempotency_key_busy",
                "A request with this " + HEADER + " is being processed. Retry shortly."));
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new BusinessRuleException("idempotency_key_reused",
                    "This " + HEADER + " was already used for a different request.", null);
        }
        return new Result<>(json.readValue(stored.responseBody(), responseType), true);
    }

    private Optional<StoredKey> load(UUID merchantId, String key) {
        return jdbc.sql("""
                        SELECT request_fingerprint, response_body FROM idempotency_keys
                         WHERE merchant_id = :merchantId AND idempotency_key = :key AND response_body IS NOT NULL""")
                .param("merchantId", merchantId)
                .param("key", key)
                .query((rs, rowNum) -> new StoredKey(rs.getString(1), rs.getString(2)))
                .optional();
    }
}
