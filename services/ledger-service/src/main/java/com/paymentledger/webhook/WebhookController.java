package com.paymentledger.webhook;

import com.paymentledger.provider.WebhookSignature;
import com.paymentledger.webhook.WebhookService.WebhookResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The URL the payment provider calls. No API key: see the signature check in WebhookService. */
@RestController
public class WebhookController {

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    /** The body is taken as raw bytes: the signature covers exactly these bytes. */
    @PostMapping(path = "/webhooks/provider", consumes = MediaType.APPLICATION_JSON_VALUE)
    public WebhookResult receive(@RequestHeader(name = WebhookSignature.HEADER, required = false) String signature,
                                 @RequestBody byte[] body) {
        return webhooks.receive(signature, body);
    }
}
