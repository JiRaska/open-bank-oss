// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import org.keycloak.authentication.requiredactions.WebAuthnPasswordlessRegisterFactory;
import org.keycloak.models.Constants;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

final class McpNativeEnrollment {
    private McpNativeEnrollment() {
    }

    static boolean appliesTo(AuthenticationSessionModel auth) {
        return "openbank-customers".equals(auth.getRealm().getName())
                && McpConnectPolicy.bankAdapterAllowed(auth.getClient());
    }

    static boolean blocks(AuthenticationSessionModel auth, UserModel user, boolean nativeVerified) {
        boolean browserEnrollmentSeparate = nativeVerified && appliesTo(auth);
        return user.getRequiredActionsStream().anyMatch(action -> blocks(action, browserEnrollmentSeparate))
                || auth.getRequiredActions().stream().anyMatch(action -> blocks(action, browserEnrollmentSeparate));
    }

    private static boolean blocks(String action, boolean browserEnrollmentSeparate) {
        return !browserEnrollmentSeparate || !WebAuthnPasswordlessRegisterFactory.PROVIDER_ID.equals(action);
    }

    static boolean browserEnrollmentDeferred(AuthenticationSessionModel auth, UserModel user) {
        String nativeProof = auth.getAuthNote(McpNotes.NATIVE_PROOF);
        return auth.getClientNote(Constants.KC_ACTION) == null
                && auth.getClientNote(Constants.KC_ACTION_EXECUTING) == null
                && nativeProof != null && nativeProof.equals(auth.getAuthNote(McpNotes.NATIVE_STAGED_CONTEXT))
                && nativeProof.equals(auth.getAuthNote(McpNotes.CONNECT_CONTEXT_SHA256))
                && "1".equals(auth.getAuthNote(McpNotes.CONNECT_V2))
                && "1".equals(auth.getAuthNote(McpNotes.BURNED))
                && auth.getAuthNote(McpNotes.PAR_PROOF_JKT) != null
                && user != null && user.isEnabled() && appliesTo(auth)
                && McpWithheldCodeAuthenticator.partyOf(user)
                        .map(party -> party.equals(auth.getAuthNote(McpNotes.PARTY_ID))).orElse(false)
                && !blocks(auth, user, true);
    }
}
