// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;

import org.keycloak.OAuthErrorException;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.protocol.oidc.TokenManager;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.dpop.DPoP;
import org.keycloak.services.Urls;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.services.clientpolicy.context.TokenRefreshResponseContext;
import org.keycloak.services.clientpolicy.context.TokenResponseContext;
import org.keycloak.services.util.DPoPUtil;
import org.keycloak.util.TokenUtil;

/** Final bank checks before Keycloak serializes the generated access and refresh tokens. */
final class McpConnectTokenPolicy {
    static final String CONNECTION_CLAIM = "openbank_mcp_connection_id";

    private McpConnectTokenPolicy() {
    }

    static void authorizationCode(KeycloakSession session, TokenResponseContext context)
            throws ClientPolicyException {
        ClientModel client = session.getContext().getClient();
        if (!McpClientGuardExecutor.isMcp(client)) {
            return;
        }
        requireHostedClientAuthentication(client);
        var factory = factory(session);
        if (factory.allowsLegacyV1()) {
            if (McpConnectPolicy.bankAdapterReserved(client)) throw deny();
            return;
        }
        try {
            McpConnectAuthorityClient authority = factory.tokenAuthority();
            BoundFamily bound = boundFamily(session, client, context.getAccessTokenResponseBuilder());
            var code = context.getParseResult().getCodeData();
            if (!"S256".equals(code.getCodeChallengeMethod()) || !Objects.equals(bound.jkt(), code.getDpopJkt())
                    || !client.getId().equals(code.getClientUUID()) || !bound.sessionId().equals(code.getUserSessionId())) {
                throw deny();
            }
            String codeRef = McpConnectTokenReferences.codeBinding(session.getContext().getRealm().getId(),
                    bound.party(), client.getId(), bound.jkt(), code.getCodeChallenge());
            var staged = authority.staged(bound.party(), client.getClientId(), bound.jkt(), codeRef);
            if (staged.request().base().expiresAt() <= Instant.now().getEpochSecond()) {
                throw deny();
            }
            var activation = authority.activate(staged, bound.family());
            bindConnection(context.getAccessTokenResponseBuilder().getAccessToken(), activation.connectionId());
        } catch (IOException | RuntimeException e) {
            throw deny();
        }
    }

    static void refresh(KeycloakSession session, TokenRefreshResponseContext context)
            throws ClientPolicyException {
        ClientModel client = session.getContext().getClient();
        if (!McpClientGuardExecutor.isMcp(client)) {
            return;
        }
        requireHostedClientAuthentication(client);
        var factory = factory(session);
        if (factory.allowsLegacyV1()) {
            if (McpConnectPolicy.bankAdapterReserved(client)) throw deny();
            return;
        }
        try {
            var bound = boundFamily(session, client, context.getAccessTokenResponseBuilder());
            var connection = factory.tokenAuthority().activeFamily(
                    bound.party(), client.getClientId(), bound.jkt(), bound.family());
            bindConnection(context.getAccessTokenResponseBuilder().getAccessToken(), connection);
        } catch (IOException | RuntimeException e) {
            throw deny();
        }
    }

    /**
     * Keycloak has already authenticated the client before either response hook. Enforce the
     * bank's allowed authenticator and the runtime exception deadline even on an imported realm.
     */
    private static void requireHostedClientAuthentication(ClientModel client) throws ClientPolicyException {
        if (McpConnectPolicy.bankAdapterReserved(client) && !McpConnectPolicy.bankAdapterAllowed(client)) {
            throw deny();
        }
        if (!"H".equals(client.getAttribute(McpConnectPolicy.TIER_ATTRIBUTE))) {
            return;
        }
        if (!hostedClientAuthenticationAllowed(client)) {
            throw new ClientPolicyException(OAuthErrorException.INVALID_CLIENT,
                    "MCP hosted client authentication is unavailable");
        }
    }

    static boolean hostedClientAuthenticationAllowed(ClientModel client) {
        return !client.isPublicClient() && switch (client.getClientAuthenticatorType()) {
            case "client-jwt" -> true;
            case "client-secret" -> validSecretException(
                    client.getAttribute("openbank.mcp.secret_exception_expires"), LocalDate.now(ZoneOffset.UTC));
            case null, default -> false;
        };
    }

    static boolean validSecretException(String expiry, LocalDate today) {
        if (expiry == null) {
            return false;
        }
        try {
            return !LocalDate.parse(expiry).isBefore(today);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    static void bindConnection(AccessToken accessToken, UUID connection) {
        accessToken.getOtherClaims().put(CONNECTION_CLAIM, connection.toString());
    }

    private static BoundFamily boundFamily(KeycloakSession session, ClientModel client,
                                            TokenManager.AccessTokenResponseBuilder builder)
            throws ClientPolicyException {
        var realm = session.getContext().getRealm();
        var access = builder.getAccessToken();
        var refresh = builder.getRefreshToken();
        var clientSession = builder.getClientSessionCtx().getClientSession();
        var userSession = clientSession.getUserSession();
        var user = userSession.getUser();
        var proof = session.getAttribute(DPoPUtil.DPOP_SESSION_ATTRIBUTE, DPoP.class);
        String issuer = Urls.realmIssuer(session.getContext().getUri().getBaseUri(), realm.getName());
        String party = McpWithheldCodeAuthenticator.partyOf(user).orElseThrow(McpConnectTokenPolicy::deny);
        String sid = userSession.getId();
        String tier = client.getAttribute(McpConnectPolicy.TIER_ATTRIBUTE);
        boolean bankAdapter = McpConnectPolicy.bankAdapterReserved(client);
        if (bankAdapter && (!McpConnectPolicy.bankAdapterAllowed(client) || proof == null)) {
            throw deny();
        }
        if (!"openbank-customers".equals(realm.getName()) || !McpConnectPolicy.isMcpClient(tier)
                || !client.getId().equals(clientSession.getClient().getId()) || userSession.isOffline()
                || !realm.isRevokeRefreshToken() || realm.getRefreshTokenMaxReuse() != 0
                || access == null || refresh == null
                || !TokenUtil.TOKEN_TYPE_REFRESH.equals(refresh.getType()) || !issuer.equals(access.getIssuer())
                || !issuer.equals(refresh.getIssuer()) || !user.getId().equals(access.getSubject())
                || !user.getId().equals(refresh.getSubject()) || !sid.equals(access.getSessionId())
                || !sid.equals(refresh.getSessionId()) || !client.getClientId().equals(access.getIssuedFor())
                || !client.getClientId().equals(refresh.getIssuedFor()) || !Objects.equals(party, access.getOtherClaims().get("party_id"))
                || !(refresh.getOtherClaims().get(Constants.REUSE_ID) instanceof String reuseId)) {
            throw deny();
        }
        String jkt = null;
        if (proof != null) {
            jkt = proof.getThumbprint();
            if (jkt == null || !DPoPUtil.DPOP_TOKEN_TYPE.equals(access.getType())
                    || access.getConfirmation() == null
                    || !jkt.equals(access.getConfirmation().getKeyThumbprint())) {
                throw deny();
            }
            // Bind the confidential online family before serialization using the already verified proof.
            if (bankAdapter && refresh.getConfirmation() == null) {
                var confirmation = new AccessToken.Confirmation();
                confirmation.setKeyThumbprint(jkt);
                confirmation.setJktType(DPoPUtil.DPOP_TOKEN_TYPE);
                refresh.setConfirmation(confirmation);
            }
            if (refresh.getConfirmation() == null || !jkt.equals(refresh.getConfirmation().getKeyThumbprint())) {
                throw deny();
            }
        } else if (!"H".equals(tier) || !"Bearer".equals(access.getType())
                || access.getConfirmation() != null || refresh.getConfirmation() != null) {
            throw deny();
        }
        return new BoundFamily(party, sid, jkt, McpConnectTokenReferences.family(realm.getId(), sid, client.getId(), reuseId));
    }

    private static McpWithheldCodeAuthenticatorFactory factory(KeycloakSession session) throws ClientPolicyException {
        var factory = session.getKeycloakSessionFactory().getProviderFactory(Authenticator.class,
                McpWithheldCodeAuthenticatorFactory.PROVIDER_ID);
        if (factory instanceof McpWithheldCodeAuthenticatorFactory mcp) {
            return mcp;
        }
        throw deny();
    }

    private static ClientPolicyException deny() {
        return new ClientPolicyException(OAuthErrorException.INVALID_GRANT, "MCP connection authority did not authorize token issuance");
    }

    private record BoundFamily(String party, String sessionId, String jkt,
                               McpConnectAuthorityClient.FamilyReference family) {
    }
}
