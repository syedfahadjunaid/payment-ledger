package com.paymentledger.provider;

import com.paymentledger.config.LedgerProperties;
import com.paymentledger.provider.ProviderDtos.ChargeResponse;
import com.paymentledger.provider.ProviderDtos.CreateChargeRequest;
import com.paymentledger.provider.ProviderDtos.CreateRefundRequest;
import com.paymentledger.provider.ProviderDtos.ProviderError;
import com.paymentledger.provider.ProviderDtos.RefundResponse;
import com.paymentledger.provider.ProviderDtos.SettlementReport;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.function.Supplier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP client for the payment provider (the simulator in docker-compose).
 *
 * <p>Timeouts are explicit: an HTTP call without one can hang a thread forever. Every
 * money-moving call sends an {@code Idempotency-Key}, so retrying after a timeout can never
 * charge the card twice.
 */
@Component
public class HttpPaymentProviderClient implements PaymentProviderClient {

    private final RestClient rest;
    private final JsonMapper json;

    public HttpPaymentProviderClient(RestClient.Builder builder, LedgerProperties props, JsonMapper json) {
        this.json = json;
        LedgerProperties.Provider provider = props.provider();
        HttpClient httpClient = HttpClient.newBuilder()
                // Plain http:// with HTTP/2 makes the JDK client send an "h2c" upgrade some servers reject
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(provider.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(provider.readTimeout());

        this.rest = builder
                .baseUrl(provider.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> headers.setBearerAuth(provider.apiKey()))
                .defaultStatusHandler(HttpStatusCode::is4xxClientError, (request, response) -> {
                    throw new ProviderRejectedException(errorCode(response));
                })
                .defaultStatusHandler(HttpStatusCode::is5xxServerError, (request, response) -> {
                    throw new ProviderUnavailableException("provider returned " + response.getStatusCode(), null);
                })
                .build();
    }

    @Override
    public ChargeResponse createCharge(CreateChargeRequest request, String idempotencyKey) {
        return call(() -> rest.post().uri("/v1/charges")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ChargeResponse.class));
    }

    @Override
    public ChargeResponse getCharge(String chargeId) {
        return call(() -> rest.get().uri("/v1/charges/{id}", chargeId).retrieve().body(ChargeResponse.class));
    }

    @Override
    public RefundResponse createRefund(CreateRefundRequest request, String idempotencyKey) {
        return call(() -> rest.post().uri("/v1/refunds")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(RefundResponse.class));
    }

    @Override
    public RefundResponse getRefund(String refundId) {
        return call(() -> rest.get().uri("/v1/refunds/{id}", refundId).retrieve().body(RefundResponse.class));
    }

    @Override
    public SettlementReport settlementReport(LocalDate date) {
        return call(() -> rest.get().uri("/v1/settlements?date={date}", date).retrieve().body(SettlementReport.class));
    }

    /** Network problems (connection refused, timeout) mean "outcome unknown", like a 5xx. */
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException e) {
            throw new ProviderUnavailableException("provider unreachable: " + e.getMessage(), e);
        }
    }

    /** {"error": "card_declined"} -> "card_declined"; anything unparseable -> the HTTP status. */
    private String errorCode(ClientHttpResponse response) throws IOException {
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            ProviderError error = json.readValue(body, ProviderError.class);
            if (error != null && error.error() != null) {
                return error.error();
            }
        } catch (JacksonException e) {
            // fall through: not JSON
        }
        return "provider_http_" + response.getStatusCode().value();
    }
}
