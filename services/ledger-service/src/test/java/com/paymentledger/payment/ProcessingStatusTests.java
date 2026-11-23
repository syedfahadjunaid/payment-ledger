package com.paymentledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentledger.payment.Transition.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Plain JUnit: the state machine is pure logic, no Spring needed. */
class ProcessingStatusTests {

    @ParameterizedTest(name = "{0} + {1} -> {2} {3}")
    @CsvSource({
            "CREATED,    ACCEPTED,   MOVE,     PROCESSING",
            "CREATED,    PROCESSING, MOVE,     PROCESSING",
            "CREATED,    SUCCEEDED,  MOVE,     SUCCEEDED",   // webhook overtook the API response
            "CREATED,    FAILED,     MOVE,     FAILED",
            "PROCESSING, ACCEPTED,   IGNORE,",
            "PROCESSING, PROCESSING, IGNORE,",
            "PROCESSING, SUCCEEDED,  MOVE,     SUCCEEDED",
            "PROCESSING, FAILED,     MOVE,     FAILED",
            "SUCCEEDED,  ACCEPTED,   IGNORE,",                // stale, out of order
            "SUCCEEDED,  PROCESSING, IGNORE,",
            "SUCCEEDED,  SUCCEEDED,  IGNORE,",                // duplicate
            "SUCCEEDED,  FAILED,     CONFLICT,",              // contradiction: a human must look
            "FAILED,     ACCEPTED,   IGNORE,",
            "FAILED,     PROCESSING, IGNORE,",
            "FAILED,     FAILED,     IGNORE,",
            "FAILED,     SUCCEEDED,  CONFLICT,"
    })
    void transitions(ProcessingStatus from, ProviderEvent event, Kind kind, ProcessingStatus to) {
        Transition transition = from.on(event);

        assertThat(transition.kind()).isEqualTo(kind);
        assertThat(transition.target()).isEqualTo(to);
    }

    @ParameterizedTest
    @EnumSource(value = ProcessingStatus.class, names = {"SUCCEEDED", "FAILED"})
    void finalStatusesNeverMove(ProcessingStatus status) {
        for (ProviderEvent event : ProviderEvent.values()) {
            assertThat(status.on(event).kind()).isNotEqualTo(Kind.MOVE);
        }
    }

    @Test
    void providerStatusesAreMappedAndUnknownOnesIgnored() {
        assertThat(ProviderEvent.fromProviderStatus("succeeded")).contains(ProviderEvent.SUCCEEDED);
        assertThat(ProviderEvent.fromProviderStatus("refunded")).isEmpty();
        assertThat(ProviderEvent.fromProviderStatus(null)).isEmpty();
    }
}
