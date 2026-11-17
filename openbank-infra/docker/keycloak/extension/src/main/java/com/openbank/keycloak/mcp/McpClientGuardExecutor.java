// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.keycloak.mcp;

import org.jboss.logging.Logger;
import org.keycloak.OAuthErrorException;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.protocol.oidc.par.clientpolicy.context.PushedAuthorizationRequestContext;
import org.keycloak.representations.JsonWebToken;
import org.keycloak.representations.idm.ClientPolicyExecutorConfigurationRepresentation;
import org.keycloak.services.clientpolicy.ClientPolicyContext;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.services.clientpolicy.context.AuthorizationRequestContext;
import org.keycloak.services.clientpolicy.context.TokenExchangeRequestContext;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProvider;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.time.Instant;
import java.util.List;

/**
 * Realm client-policy executor for every client carrying {@code openbank.mcp.tier}. It is bound by
 * an {@code any-client} condition and is a no-op for every other client.
 *
 * <ul>
 *   <li>#16667 / ADR-0385 D1: refuses token exchange — legacy v1 and standard v2 alike, both of
 *       which raise {@code TOKEN_EXCHANGE_REQUEST} from the token-exchange grant — when the
 *       requesting client is an MCP client, and when the subject token was issued to one. An MCP
 *       access token can therefore never mint a refresh chain outside its rotated family.</li>
 *   <li>ADR-0385 D1 / D7.4 (RFC 8707): at PAR, a {@code resource} other than the one the client
 *       is bound to is {@code invalid_target}. Keycloak 26.8.1 does not enforce it itself.</li>
 *   <li>ADR-0385 D3: at the authorization request, records the PAR-held request facts the
 *       withheld-code authenticator recomputes the connect context from. They are written here,
 *       server-side, so nothing the browser sends later can change them.</li>
 * </ul>
 */
public class McpClientGuardExecutor implements ClientPolicyExecutorProvider<ClientPolicyExecutorConfigurationRepresentation> {
    private static final Logger LOG = Logger.getLogger(McpClientGuardExecutor.class);

    private final KeycloakSession session;

    public McpClientGuardExecutor(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public String getProviderId() {
        return McpClientGuardExecutorFactory.PROVIDER_ID;
    }

    @Override
    public void executeOnEvent(ClientPolicyContext context) throws ClientPolicyException {
        switch (context.getEvent()) {
            case TOKEN_EXCHANGE_REQUEST -> refuseExchange((TokenExchangeRequestContext) context);
            case PUSHED_AUTHORIZATION_REQUEST -> checkResource((PushedAuthorizationRequestContext) context);
            case AUTHORIZATION_REQUEST -> recordRequest((AuthorizationRequestContext) context);
            default -> {
                // not an MCP-relevant event
            }
        }
    }

    static boolean isMcp(ClientModel client) {
        return client != null && McpConnectPolicy.isMcpClient(client.getAttribute(McpConnectPolicy.TIER_ATTRIBUTE));
    }

    private void refuseExchange(TokenExchangeRequestContext context) throws ClientPolicyException {
        ClientModel requester = context.getClient();
        if (isMcp(requester)) {
            LOG.warnf("refused token exchange requested by MCP client %s", requester.getClientId());
            throw new ClientPolicyException(OAuthErrorException.UNAUTHORIZED_CLIENT,
                    "token exchange is not available to MCP clients");
        }
        String subjectToken = context.getTokenExchangeContext().getParams().getSubjectToken();
        String issuedFor = issuedFor(subjectToken);
        if (issuedFor != null && isMcp(session.getContext().getRealm().getClientByClientId(issuedFor))) {
            LOG.warnf("refused token exchange of a token issued to MCP client %s", issuedFor);
            throw new ClientPolicyException(OAuthErrorException.INVALID_REQUEST,
                    "a token issued to an MCP client cannot be exchanged");
        }
    }

    /** The unverified {@code azp}, used only to REFUSE more; Keycloak verifies the token itself. */
    private static String issuedFor(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return new JWSInput(token).readJsonContent(JsonWebToken.class).getIssuedFor();
        } catch (Exception e) {
            return null;
        }
    }

    private void checkResource(PushedAuthorizationRequestContext context) throws ClientPolicyException {
        ClientModel client = context.getClient();
        if (!isMcp(client)) {
            return;
        }
        List<String> requested = context.getRequestParameters() == null ? null
                : context.getRequestParameters().get("resource");
        if (!McpConnectPolicy.resourceAllowed(requested, client.getAttribute(McpConnectPolicy.RESOURCE_ATTRIBUTE))) {
            throw new ClientPolicyException("invalid_target", "resource is not the one this client is bound to");
        }
    }

    private void recordRequest(AuthorizationRequestContext context) throws ClientPolicyException {
        ClientModel client = context.getClient();
        if (!isMcp(client)) {
            return;
        }
        String requestUri = context.getRequestParameters() == null ? null
                : context.getRequestParameters().getFirst("request_uri");
        if (!context.isParRequest() || requestUri == null) {
            throw new ClientPolicyException(OAuthErrorException.INVALID_REQUEST, "MCP clients must use PAR");
        }
        try {
            McpConnectContext.parRequestId(requestUri);
        } catch (IllegalArgumentException e) {
            throw new ClientPolicyException(OAuthErrorException.INVALID_REQUEST, "malformed request_uri");
        }
        String resource = client.getAttribute(McpConnectPolicy.RESOURCE_ATTRIBUTE);
        if (resource == null || resource.isBlank()) {
            throw new ClientPolicyException(OAuthErrorException.INVALID_REQUEST, "MCP client has no bound resource");
        }
        AuthenticationSessionModel auth = context.getAuthenticationSession();
        auth.setAuthNote(McpNotes.STARTED_AT, Long.toString(Instant.now().getEpochSecond()));
        auth.setAuthNote(McpNotes.REQUEST_URI, requestUri);
        auth.setAuthNote(McpNotes.REDIRECT_URI, context.getRedirectUri());
        auth.setAuthNote(McpNotes.SCOPE, context.getAuthorizationEndpointRequest().getScope());
        auth.setAuthNote(McpNotes.RESOURCE, resource);
    }
}
