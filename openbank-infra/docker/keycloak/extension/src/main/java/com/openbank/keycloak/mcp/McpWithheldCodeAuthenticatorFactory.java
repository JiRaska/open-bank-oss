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
    private boolean connectV2;
    private boolean allowLegacyV1;
    private String connectAuthorityUrl;
    private long lifespanSeconds = McpConnectPolicy.MAX_SESSION_SECONDS;

    @Override
    public Authenticator create(KeycloakSession session) {
        return new McpWithheldCodeAuthenticator(settings, lifespanSeconds, connectV2, allowLegacyV1, connectAuthorityUrl);
    }

    @Override
    public void init(Config.Scope config) {
        settings = new ScaClient.Settings(
                get(config, "sca-url", "scaUrl"),
                get(config, "token-url", "tokenUrl"),
                get(config, "client-id", "clientId"),
                get(config, "client-secret", "clientSecret"));
        // Keycloak persists SPI options ending in "-enabled" at build time for optimized images.
        String versionFlag = System.getenv("MCP_CONNECT_V2_ENABLED");
        if (versionFlag == null) {
            versionFlag = get(config, "connect-v2-enabled", "connectV2Enabled");
        }
        if (versionFlag != null && !"true".equalsIgnoreCase(versionFlag) && !"false".equalsIgnoreCase(versionFlag)) {
            throw new IllegalArgumentException("connect-v2-enabled must be true or false");
        }
        connectV2 = Boolean.parseBoolean(versionFlag);
        allowLegacyV1 = Boolean.parseBoolean(get(config, "allow-legacy-v1", "allowLegacyV1"));
        if (connectV2 && allowLegacyV1) {
            throw new IllegalArgumentException("MCP connect V2 and isolated V1 fixtures cannot both be enabled");
        }
        connectAuthorityUrl = get(config, "connect-authority-url", "connectAuthorityUrl");
        if (connectV2 && connectAuthorityUrl == null) {
            LOG.warn("MCP connect V2 has no connect authority; all V2 connects will be refused");
        }
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

    long connectLifespanSeconds() {
        return lifespanSeconds;
    }

    McpConnectAuthorityClient tokenAuthority() {
        if (!connectV2 || !settings.complete()) {
            throw new IllegalStateException("MCP connect V2 is unavailable");
        }
        return new McpConnectAuthorityClient(connectAuthorityUrl, new ScaClient(settings));
    }

    boolean allowsLegacyV1() {
        return allowLegacyV1;
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
