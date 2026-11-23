package com.paymentledger.idempotency;

import com.paymentledger.config.LedgerProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Keys are kept for a retry window (24 h by default), then deleted so the table stays small. */
@Component
public class IdempotencyKeyExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyExpiryJob.class);

    private final JdbcClient jdbc;
    private final Duration retention;
    private final Clock clock;

    public IdempotencyKeyExpiryJob(JdbcClient jdbc, LedgerProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.retention = props.idempotency().retention();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public int deleteExpiredKeys() {
        int deleted = jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < :cutoff")
                .param("cutoff", clock.instant().minus(retention).atOffset(ZoneOffset.UTC))
                .update();
        if (deleted > 0) {
            log.info("Deleted {} expired idempotency keys", deleted);
        }
        return deleted;
    }
}
