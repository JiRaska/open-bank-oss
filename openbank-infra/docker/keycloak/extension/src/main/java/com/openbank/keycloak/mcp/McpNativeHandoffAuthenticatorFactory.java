// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;

/** Available to a dedicated hosted browser flow, never selected by the existing realm flows. */
public final class McpNativeHandoffAuthenticatorFactory extends McpWithheldCodeAuthenticatorFactory {
    @Override
    public void init(Config.Scope config) {
        // The handoff uses the existing Connect authority factory at request time.
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return new McpNativeHandoffAuthenticator();
    }

    @Override
    public String getId() {
        return "openbank-mcp-native-handoff";
    }

    @Override
    public String getDisplayType() {
        return "OpenBank MCP native handoff";
    }

    @Override
    public String getHelpText() {
        return "Resolves a guarded hosted OAuth browser to an app-verified native passkey.";
    }
}
