// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.keycloak.sessions.AuthenticationSessionModel;

/** Bank-generated handoff for one guarded hosted OAuth browser session. */
final class McpNativeHandoff {
    private static final String APP_LINK = "https://open-bank.tech/app/mcp/connect#handoff=";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    record Binding(String requestRef, String issuer, String clientId, String resource, String redirectUri,
                   String sessionRef, String kind, String parJkt, List<String> scopes, long expiresAtEpochSecond) {
    }

    /** The opaque token is presented once. Only its digest is stored in bank authority state. */
    static final class Issued {
        private final String token;

        private Issued(String token) {
            this.token = token;
        }

        String tokenSha256() {
            return digest(token);
        }

        String appLink() {
            return APP_LINK + token;
        }

        String formValue() {
            return token;
        }
    }

    private McpNativeHandoff() {
    }

    static Issued issue() {
        var entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        return new Issued(BASE64_URL.encodeToString(entropy));
    }

    static Issued recover(String token, String expectedDigest) {
        return canonicalSha256(token) && digest(token).equals(expectedDigest) ? new Issued(token) : null;
    }

    private static String digest(String token) {
        return McpConnectContext.sha256Hex(Base64.getUrlDecoder().decode(token));
    }

    static Binding binding(AuthenticationSessionModel auth, String issuer, long nowEpochSecond, long lifespanSeconds) {
        var client = auth.getClient();
        if (!"H".equals(client.getAttribute(McpConnectPolicy.TIER_ATTRIBUTE))
                || !McpConnectTokenPolicy.hostedClientAuthenticationAllowed(client)) {
            throw new IllegalArgumentException("native handoff requires an authenticated hosted client");
        }
        String pkce = auth.getAuthNote(McpNotes.PKCE_CHALLENGE);
        if (!canonicalSha256(pkce)) {
            throw new IllegalArgumentException("native handoff requires S256");
        }
        String redirectUri = requireHttps(auth.getAuthNote(McpNotes.REDIRECT_URI));
        String resource = requireHttps(auth.getAuthNote(McpNotes.RESOURCE));
        String start = auth.getAuthNote(McpNotes.STARTED_AT);
        if (start == null || !start.matches("[0-9]{1,12}")) {
            throw new IllegalArgumentException("native handoff has no guarded start");
        }
        long started = Long.parseLong(start);
        long expires = started + McpConnectPolicy.sessionLifespan(lifespanSeconds);
        if (started > nowEpochSecond || expires <= nowEpochSecond) {
            throw new IllegalArgumentException("native handoff is outside its session lifetime");
        }
        var context = McpConnectContext.of(client.getClientId(), redirectUri, resource,
                auth.getAuthNote(McpNotes.SCOPE), auth.getAuthNote(McpNotes.REQUEST_URI), expires);
        List<String> scopes = List.of(context.scopes().split(" "));
        if (scopes.contains("offline_access")) {
            throw new IllegalArgumentException("native handoff only supports online connections");
        }
        String parJkt = auth.getAuthNote(McpNotes.PAR_PROOF_JKT);
        if (McpConnectPolicy.bankAdapterReserved(client)
                && (!McpConnectPolicy.bankAdapterAllowed(client) || parJkt == null)) {
            throw new IllegalArgumentException("bank adapter requires independently verified PAR proof");
        }
        if (parJkt != null && !canonicalSha256(parJkt)) {
            throw new IllegalArgumentException("invalid guarded PAR proof binding");
        }
        String sessionRef = McpConnectContext.sha256Hex(String.join("\n", "openbank-mcp-native-browser/v1",
                auth.getRealm().getId(), auth.getParentSession().getId(), auth.getTabId(), client.getId(), pkce));
        return new Binding(context.requestUriSha256(), requireHttps(issuer), context.clientId(), resource,
                redirectUri, sessionRef, parJkt == null ? "HOSTED" : "DPOP", parJkt, scopes, expires);
    }

    private static boolean canonicalSha256(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{43}")
                && BASE64_URL.encodeToString(Base64.getUrlDecoder().decode(value)).equals(value);
    }

    private static String requireHttps(String value) {
        if (value == null) {
            throw new IllegalArgumentException("missing guarded HTTPS URI");
        }
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("invalid guarded HTTPS URI");
        }
        return value;
    }
}
