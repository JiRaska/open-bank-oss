// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.
package com.openbank.keycloak.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.keycloak.Config;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.protocol.oauth2.OAuth2WellKnownProviderFactory;
import org.keycloak.protocol.oidc.OIDCWellKnownProviderFactory;
import org.keycloak.protocol.oidc.representations.OIDCConfigurationRepresentation;
import org.keycloak.wellknown.WellKnownProvider;
import org.keycloak.wellknown.WellKnownProviderFactory;

/** Advertises the managed, statically pre-registered URL identifier without dynamic onboarding. */
public abstract class McpChatGptCimdDiscoveryFactory implements WellKnownProviderFactory {
    private static final String REALM = "openbank-customers";
    private static final String ISSUER = "https://kc.open-bank.tech/realms/openbank-customers";
    private static final String CLIENT_ID = "https://chatgpt.com/oauth/client.json";
    private static final String CALLBACK = "https://chatgpt.com/connector_platform_oauth_redirect";
    private static final String RESOURCE = "https://mcp.open-bank.tech/customer/mcp";
    private static final String FLOW = "efd9751b-94d8-4ae5-80dc-3f078a671b2d";
    private static final String PAR = ISSUER + "/protocol/openid-connect/ext/par/request";
    private static final int PRIORITY = 90;
    private String approvedJwksSha256;

    @Override
    public final void init(Config.Scope config) {
        approvedJwksSha256 = System.getenv("MCP_CHATGPT_CIMD_STATIC_JWKS_SHA256");
    }

    @Override
    public final WellKnownProvider create(KeycloakSession session) {
        // Resolve the original by provider ID, avoiding another alias lookup and retaining
        // all Keycloak settings including provider-specific metadata overrides.
        WellKnownProvider original = session.getProvider(WellKnownProvider.class, getAlias());
        return new WellKnownProvider() {
            @Override
            public Object getConfig() {
                Object config = original.getConfig();
                if (config instanceof OIDCConfigurationRepresentation oidc) {
                    advertise(oidc, session.getContext().getRealm(), approvedJwksSha256);
                }
                return config;
            }

            @Override
            public void close() {
                // The owning Keycloak session closes the original provider.
            }
        };
    }

    static void advertise(OIDCConfigurationRepresentation config, RealmModel realm, String approvedDigest) {
        if (realm == null || !REALM.equals(realm.getName())) {
            return;
        }
        // Do not inherit a realm-wide advertisement from the stock provider or an override.
        config.setClientIdMetadataDocumentSupported(null);
        if (approvedDigest == null || !approvedDigest.matches("[0-9a-f]{64}")
                || !ISSUER.equals(config.getIssuer()) || !PAR.equals(config.getPushedAuthorizationRequestEndpoint())) {
            return;
        }
        ClientModel client = realm.getClientByClientId(CLIENT_ID);
        if (matches(client, approvedDigest)) {
            config.setClientIdMetadataDocumentSupported(true);
        }
    }

    private static boolean matches(ClientModel client, String approvedDigest) {
        return client != null && CLIENT_ID.equals(client.getClientId()) && client.isEnabled()
                && !client.isPublicClient() && client.isStandardFlowEnabled()
                && !client.isImplicitFlowEnabled() && !client.isDirectAccessGrantsEnabled()
                && !client.isServiceAccountsEnabled() && "client-jwt".equals(client.getClientAuthenticatorType())
                && Set.of(CALLBACK).equals(client.getRedirectUris())
                && FLOW.equals(client.getAuthenticationFlowBindingOverride("browser"))
                && "H".equals(client.getAttribute("openbank.mcp.tier"))
                && RESOURCE.equals(client.getAttribute("openbank.mcp.resource"))
                && "S256".equals(client.getAttribute("pkce.code.challenge.method"))
                && "true".equals(client.getAttribute("require.pushed.authorization.requests"))
                && "RS256".equals(client.getAttribute("token.endpoint.auth.signing.alg"))
                && "true".equals(client.getAttribute("use.jwks.string"))
                && "false".equals(client.getAttribute("use.jwks.url"))
                && approvedDigest.equals(sha256(client.getAttribute("jwks.string")));
    }

    private static String sha256(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public final int getPriority() {
        // Keycloak chooses the lowest priority for a well-known alias; stock is 100.
        return PRIORITY;
    }

    @Override
    public final void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public final void close() {
    }

    public static final class Oidc extends McpChatGptCimdDiscoveryFactory {
        @Override
        public String getId() {
            return "openbank-mcp-cimd-openid-configuration";
        }

        @Override
        public String getAlias() {
            return OIDCWellKnownProviderFactory.PROVIDER_ID;
        }
    }

    public static final class OAuth2 extends McpChatGptCimdDiscoveryFactory {
        @Override
        public String getId() {
            return "openbank-mcp-cimd-oauth-authorization-server";
        }

        @Override
        public String getAlias() {
            return OAuth2WellKnownProviderFactory.PROVIDER_ID;
        }

        @Override
        public boolean isAvailableViaServerMetadata() {
            return true;
        }
    }
}
