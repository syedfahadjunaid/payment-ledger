package com.paymentledger.config;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Small infrastructure beans. */
@Configuration(proxyBeanMethods = false)
public class AppConfig {

    /**
     * Code asks this Clock for "now" instead of calling Instant.now() directly, so tests can
     * control time. UTC everywhere: a business day is a UTC day.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Runs saga steps that call the payment provider after the HTTP request's transaction has
     * committed. One cheap virtual thread per task; a crash loses nothing because the recovery
     * job finds unfinished work in the database.
     */
    @Bean(destroyMethod = "close")
    ExecutorService sagaExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Scheduled jobs (reconciliation, saga recovery, key expiry). Tests switch this off and call the jobs directly. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "ledger.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
