// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.keycloak.mcp;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.TreeSet;

/**
 * ADR-0385 D3 — the canonical context of one MCP connect, whose SHA-256 is the
 * {@code payloadSha256} the SCA challenge carries and the device signs.
 *
 * <p>The canonical form is UTF-8, seven lines joined by {@code \n}, no trailing newline:
 *
 * <pre>
 * openbank-mcp-connect-context/v1
 * client_id=&lt;client_id&gt;
 * redirect_host=&lt;lower-case host of the redirect URI, IPv6 in brackets&gt;
 * resource=&lt;resource URL&gt;
 * scopes=&lt;distinct scope tokens, sorted by code point, joined by one space&gt;
 * request_uri_sha256=&lt;lower-case hex SHA-256 of the PAR request_uri string&gt;
 * expires_at=&lt;epoch seconds&gt;
 * </pre>
 *
 * A value containing CR or LF is refused, so no field can forge another line. The same bytes are
 * pinned by {@code src/test/resources/mcp-connect-context.fixture.json}, which sca-service's
 * {@code McpConnectContextTest} reads too: the two implementations cannot drift apart silently.
 */
public record McpConnectContext(
        String clientId,
        String redirectHost,
        String resource,
        String scopes,
        String requestUriSha256,
        long expiresAt) {

    public static final String VERSION = "openbank-mcp-connect-context/v1";
    public static final String PAR_REQUEST_URI_PREFIX = "urn:ietf:params:oauth:request_uri:";
    public static final String APPROVAL_REQUEST_PREFIX = "mcp-connect:";

    public McpConnectContext {
        for (String v : new String[] {clientId, redirectHost, resource, scopes, requestUriSha256}) {
            if (v == null || v.isEmpty()) {
                throw new IllegalArgumentException("every MCP connect context field is required");
            }
            if (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("an MCP connect context field may not contain CR or LF");
            }
        }
        if (!requestUriSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("request_uri_sha256 must be 64 lower-case hex characters");
        }
        if (expiresAt <= 0) {
            throw new IllegalArgumentException("expires_at must be positive epoch seconds");
        }
    }

    /** Builds the context from the raw values a PAR-held authorization request carries. */
    public static McpConnectContext of(
            String clientId, String redirectUri, String resource, String scope, String requestUri, long expiresAt) {
        parRequestId(requestUri);
        return new McpConnectContext(
                clientId, redirectHost(redirectUri), resource, canonicalScopes(scope), sha256Hex(requestUri), expiresAt);
    }

    /** The PAR request id: the part of {@code request_uri} after the RFC 9126 URN prefix. */
    public static String parRequestId(String requestUri) {
        if (requestUri == null || !requestUri.startsWith(PAR_REQUEST_URI_PREFIX)
                || requestUri.length() == PAR_REQUEST_URI_PREFIX.length()) {
            throw new IllegalArgumentException("not a PAR request_uri");
        }
        return requestUri.substring(PAR_REQUEST_URI_PREFIX.length());
    }

    /** {@code mcp-connect:<PAR request id>} — sca-service's reserved MCP_CONNECT namespace. */
    public static String approvalRequestId(String requestUri) {
        return APPROVAL_REQUEST_PREFIX + parRequestId(requestUri);
    }

    public static String redirectHost(String redirectUri) {
        String host = redirectUri == null ? null : URI.create(redirectUri).getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("redirect URI has no host");
        }
        return host.toLowerCase(Locale.ROOT);
    }

    public static String canonicalScopes(String scope) {
        TreeSet<String> sorted = new TreeSet<>();
        if (scope != null) {
            Arrays.stream(scope.split(" ")).filter(s -> !s.isEmpty()).forEach(sorted::add);
        }
        return String.join(" ", sorted);
    }

    public String canonical() {
        return String.join("\n",
                VERSION,
                "client_id=" + clientId,
                "redirect_host=" + redirectHost,
                "resource=" + resource,
                "scopes=" + scopes,
                "request_uri_sha256=" + requestUriSha256,
                "expires_at=" + expiresAt);
    }

    public String payloadSha256() {
        return sha256Hex(canonical());
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
