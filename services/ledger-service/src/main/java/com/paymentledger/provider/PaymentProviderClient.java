package com.paymentledger.provider;

import com.paymentledger.provider.ProviderDtos.ChargeResponse;
import com.paymentledger.provider.ProviderDtos.CreateChargeRequest;
import com.paymentledger.provider.ProviderDtos.CreateRefundRequest;
import com.paymentledger.provider.ProviderDtos.RefundResponse;
import com.paymentledger.provider.ProviderDtos.SettlementReport;
import java.time.LocalDate;

/**
 * The card payment provider. An interface so tests can swap in a fake.
 *
 * <p>Every call either returns, or throws one of two exceptions, and the difference matters:
 * <ul>
 *   <li>{@link ProviderRejectedException}: a definite "no" (4xx). Safe to mark the operation failed.</li>
 *   <li>{@link ProviderUnavailableException}: we don't know (timeout, 5xx). The provider may or may
 *       not have done it. Never assume failure; retry later with the same idempotency key.</li>
 * </ul>
 */
public interface PaymentProviderClient {

    ChargeResponse createCharge(CreateChargeRequest request, String idempotencyKey);

    ChargeResponse getCharge(String chargeId);

    RefundResponse createRefund(CreateRefundRequest request, String idempotencyKey);

    RefundResponse getRefund(String refundId);

    SettlementReport settlementReport(LocalDate date);
}
