// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.keycloak.mcp;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

/**
 * Configured once per server (never per realm, so a realm admin cannot repoint it):
 * {@code --spi-authenticator--openbank-mcp-withheld-code--sca-url=...} and siblings
 * {@code token-url}, {@code client-id}, {@code client-secret}, {@code session-lifespan-seconds}.
 * Unconfigured, the authenticator refuses every connect (fail closed).
 */
public class McpWithheldCodeAuthenticatorFactory implements AuthenticatorFactory {
    public static final String PROVIDER_ID = "openbank-mcp-withheld-code";
    private static final Logger LOG = Logger.getLogger(McpWithheldCodeAuthenticatorFactory.class);

    private ScaClient.Settings settings = new ScaClient.Settings(null, null, null, null);
    private long lifespanSeconds = McpConnectPolicy.MAX_SESSION_SECONDS;

    @Override
    public Authenticator create(KeycloakSession session) {
        return new McpWithheldCodeAuthenticator(settings, lifespanSeconds);
    }

    @Override
    public void init(Config.Scope config) {
        settings = new ScaClient.Settings(
                get(config, "sca-url", "scaUrl"),
                get(config, "token-url", "tokenUrl"),
                get(config, "client-id", "clientId"),
                get(config, "client-secret", "clientSecret"));
        String lifespan = get(config, "session-lifespan-seconds", "sessionLifespanSeconds");
        lifespanSeconds = McpConnectPolicy.sessionLifespan(lifespan == null ? 0 : Long.parseLong(lifespan.trim()));
        // Eager, at boot: the deployment supplies the client secret through an OPTIONAL secret ref (a
        // missing OpenBao key must never stop the bank's only IdP from starting), so an unconfigured
        // authenticator would otherwise be silent until the first refused connect.
        if (!settings.complete()) {
            LOG.warn("openbank-mcp-withheld-code is NOT configured (sca-url/token-url/client-id/client-secret);"
                    + " every MCP connect will be refused until it is");
        }
    }

    private static String get(Config.Scope config, String kebab, String camel) {
        String v = config.get(kebab);
        return v != null ? v : config.get(camel);
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
    public String getDisplayType() {
        return "OpenBank MCP withheld code (SCA connect)";
    }

    @Override
    public String getReferenceCategory() {
        return "openbank-mcp";
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return new AuthenticationExecutionModel.Requirement[] {AuthenticationExecutionModel.Requirement.REQUIRED};
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "ADR-0385 D3: issues the authorization code only after sca-service consumed the context-linked "
                + "MCP_CLIENT_CONNECT challenge; refuses passkeys younger than 72 h.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }
}
