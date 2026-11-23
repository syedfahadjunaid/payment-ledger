package com.paymentledger.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.paymentledger.FakePaymentProvider;
import com.paymentledger.IntegrationTest;
import com.paymentledger.Superuser;
import com.paymentledger.TestApi;
import com.paymentledger.TestApi.Merchant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class SecurityTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    Superuser superuser;

    private TestApi api;
    private Merchant merchant;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, provider);
        merchant = api.newMerchant();
    }

    @Test
    void merchantEndpointsNeedAValidApiKey() throws Exception {
        UUID wallet = api.newWallet(merchant);

        mvc.perform(get("/api/wallets/" + wallet))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/wallets/" + wallet).with(TestApi.bearer("sk_test_not-a-real-key")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/wallets/" + wallet).with(TestApi.admin()))   // operators aren't merchants
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/wallets/" + wallet).with(merchant.auth())).andExpect(status().isOk());
    }

    @Test
    void adminEndpointsNeedTheAdminToken() throws Exception {
        String body = "{\"name\":\"Sneaky shop\"}";
        mvc.perform(post("/admin/merchants").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/merchants").with(merchant.auth()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiKeysAreStoredOnlyAsHashes() throws Exception {
        String storedHash = superuser.jdbc().sql("SELECT key_hash FROM ledger.api_keys WHERE merchant_id = ?")
                .param(merchant.id()).query(String.class).single();

        assertThat(storedHash).isEqualTo(ApiKeys.sha256Hex(merchant.apiKey())).isNotEqualTo(merchant.apiKey());
        assertThat(superuser.jdbc().sql("SELECT count(*) FROM ledger.api_keys WHERE key_hash = ? OR key_prefix = ?")
                .params(merchant.apiKey(), merchant.apiKey()).query(Long.class).single()).isZero();
    }

    @Test
    void aMerchantCanHaveASecondKeyForRotation() throws Exception {
        String body = mvc.perform(post("/admin/merchants/" + merchant.id() + "/api-keys").with(TestApi.admin()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Merchant sameMerchantNewKey = new Merchant(merchant.id(), JsonPath.read(body, "$.apiKey"));
        UUID wallet = api.newWallet(merchant);

        mvc.perform(get("/api/wallets/" + wallet).with(sameMerchantNewKey.auth())).andExpect(status().isOk());
    }

    @Test
    void unknownPathsAreDenied() throws Exception {
        mvc.perform(get("/internal/anything").with(merchant.auth())).andExpect(status().isForbidden());
    }
}
