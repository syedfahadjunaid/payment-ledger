package com.paymentledger.transfer;

import static com.paymentledger.TestApi.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.paymentledger.FakePaymentProvider;
import com.paymentledger.IntegrationTest;
import com.paymentledger.TestApi;
import com.paymentledger.TestApi.Merchant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class TransferApiTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakePaymentProvider provider;

    private TestApi api;
    private Merchant merchant;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, provider);
        merchant = api.newMerchant();
        alice = api.newWallet(merchant);
        bob = api.newWallet(merchant);
    }

    @Test
    void movesMoneyAndRecordsBothSides() throws Exception {
        api.fund(merchant, alice, 10_000);

        api.transfer(merchant, key(), alice, bob, 2_550)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.amount.amountMinor").value(2550))
                .andExpect(jsonPath("$.amount.formatted").value("25.50"));

        assertThat(api.balance(merchant, alice)).isEqualTo(7_450);
        assertThat(api.balance(merchant, bob)).isEqualTo(2_550);
        mvc.perform(get("/api/wallets/" + alice + "/entries").with(merchant.auth()))
                .andExpect(jsonPath("$[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$[0].amount.amountMinor").value(-2550))
                .andExpect(jsonPath("$[1].type").value("TOP_UP"))
                .andExpect(jsonPath("$[1].amount.amountMinor").value(10000));
        mvc.perform(get("/api/wallets/" + bob + "/entries").with(merchant.auth()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount.amountMinor").value(2550));
    }

    @Test
    void refusesToOverdraw() throws Exception {
        api.fund(merchant, alice, 1_000);

        api.transfer(merchant, key(), alice, bob, 1_001)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("insufficient_funds"))
                .andExpect(jsonPath("$.errors[0].field").value("amountMinor"));

        assertThat(api.balance(merchant, alice)).isEqualTo(1_000);
        assertThat(api.balance(merchant, bob)).isZero();
    }

    @Test
    void validatesTheRequest() throws Exception {
        api.fund(merchant, alice, 1_000);
        UUID dollars = api.newWallet(merchant, "USD");

        api.transfer(merchant, key(), alice, dollars, 100).andExpect(jsonPath("$.code").value("currency_mismatch"));
        api.transfer(merchant, key(), alice, bob, 100, "USD").andExpect(jsonPath("$.code").value("currency_mismatch"));
        api.transfer(merchant, key(), alice, alice, 100).andExpect(jsonPath("$.code").value("same_wallet"));
        api.transfer(merchant, key(), alice, bob, 100_000_001).andExpect(jsonPath("$.code").value("amount_too_large"));
        api.transfer(merchant, key(), alice, bob, 0).andExpect(status().isBadRequest());
        api.transfer(merchant, key(), alice, bob, -5).andExpect(status().isBadRequest());

        // Fractional minor units are an error, never silently truncated to 12
        mvc.perform(post("/api/transfers").with(merchant.auth()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromWalletId":"%s","toWalletId":"%s","amountMinor":12.5,"currency":"EUR"}"""
                                .formatted(alice, bob)))
                .andExpect(status().isBadRequest());

        // Every money-moving request needs a well-formed Idempotency-Key
        api.transfer(merchant, null, alice, bob, 100).andExpect(status().isBadRequest());
        api.transfer(merchant, "not a valid key!", alice, bob, 100).andExpect(status().isBadRequest());

        assertThat(api.balance(merchant, alice)).isEqualTo(1_000);
    }

    @Test
    void walletsOfOtherMerchantsAreInvisible() throws Exception {
        Merchant other = api.newMerchant();
        UUID theirs = api.newWallet(other);
        api.fund(merchant, alice, 1_000);

        api.transfer(merchant, key(), alice, theirs, 100).andExpect(status().isNotFound());
        api.transfer(other, key(), theirs, alice, 100).andExpect(status().isNotFound());
        mvc.perform(get("/api/wallets/" + alice).with(other.auth())).andExpect(status().isNotFound());

        assertThat(api.balance(merchant, alice)).isEqualTo(1_000);
    }

    @Test
    void retryingWithTheSameKeyMovesMoneyOnce() throws Exception {
        api.fund(merchant, alice, 1_000);
        String key = key();

        String first = api.transfer(merchant, key, alice, bob, 300)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        api.transfer(merchant, key, alice, bob, 300)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(first, "$.id")));
        assertThat(api.balance(merchant, alice)).isEqualTo(700);

        // The same key for a different request is a client bug: refused, nothing moves
        api.transfer(merchant, key, alice, bob, 400)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
        assertThat(api.balance(merchant, alice)).isEqualTo(700);

        mvc.perform(get("/api/transfers/" + JsonPath.read(first, "$.id")).with(merchant.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromWalletId").value(alice.toString()));
    }

    @Test
    void aFailedRequestDoesNotUseUpItsKey() throws Exception {
        String key = key();
        api.transfer(merchant, key, alice, bob, 500).andExpect(jsonPath("$.code").value("insufficient_funds"));

        api.fund(merchant, alice, 500);
        api.transfer(merchant, key, alice, bob, 500)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));
        assertThat(api.balance(merchant, bob)).isEqualTo(500);
    }
}
