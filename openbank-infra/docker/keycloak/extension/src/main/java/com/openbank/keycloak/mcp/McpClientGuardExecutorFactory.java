// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.keycloak.mcp;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProvider;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProviderFactory;

import java.util.List;

public class McpClientGuardExecutorFactory implements ClientPolicyExecutorProviderFactory {
    public static final String PROVIDER_ID = "openbank-mcp-client-guard";

    @Override
    public ClientPolicyExecutorProvider<?> create(KeycloakSession session) {
        return new McpClientGuardExecutor(session);
    }

    @Override
    public void init(Config.Scope config) {
        // no configuration: every decision is keyed on the client's own openbank.mcp.* attributes
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // nothing to wire
    }

    @Override
    public void close() {
        // stateless
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "ADR-0385: refuses token exchange for and of MCP clients, enforces the bound RFC 8707 resource at PAR, "
                + "and records the PAR-held request for the withheld-code authenticator.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }
}
