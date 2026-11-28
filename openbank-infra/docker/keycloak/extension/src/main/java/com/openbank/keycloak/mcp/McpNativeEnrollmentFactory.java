// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import com.webauthn4j.verifier.attestation.trustworthiness.certpath.CertPathTrustworthinessVerifier;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.requiredactions.WebAuthnPasswordlessRegister;
import org.keycloak.authentication.requiredactions.WebAuthnPasswordlessRegisterFactory;
import org.keycloak.authentication.requiredactions.WebAuthnRegister;
import org.keycloak.models.KeycloakSession;

/** Keep browser passkey enrollment pending while completing an independently proved native MCP session. */
public final class McpNativeEnrollmentFactory extends WebAuthnPasswordlessRegisterFactory {
    @Override
    public int order() {
        return super.order() + 1;
    }

    @Override
    protected WebAuthnRegister createProvider(KeycloakSession session, CertPathTrustworthinessVerifier trustVerifier) {
        return new WebAuthnPasswordlessRegister(session, trustVerifier) {
            @Override
            public void requiredActionChallenge(RequiredActionContext context) {
                if (McpNativeEnrollment.browserEnrollmentDeferred(context.getAuthenticationSession(), context.getUser())) {
                    context.ignore();
                    return;
                }
                super.requiredActionChallenge(context);
            }
        };
    }
}
