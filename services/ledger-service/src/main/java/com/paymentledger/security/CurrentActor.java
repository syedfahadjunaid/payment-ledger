package com.paymentledger.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The name to write into the audit trail for the current thread. */
public final class CurrentActor {

    /** Background work (saga steps, scheduled jobs) has no caller. */
    public static final String SYSTEM = "system";

    private CurrentActor() {}

    public static String name() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Actor actor) {
            return actor.actorName();
        }
        return SYSTEM;
    }
}
