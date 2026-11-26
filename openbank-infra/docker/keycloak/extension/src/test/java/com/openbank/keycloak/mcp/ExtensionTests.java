// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.keycloak.mcp;

import org.keycloak.models.ClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;
import org.keycloak.util.JsonSerialization;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Unit tests for the extension's pure logic. A plain runner, not JUnit: the extension compiles
 * against the pinned Keycloak image's own jars and nothing else, and so do its tests (no
 * dependency resolution in the image build). Exits non-zero on the first failing assertion set.
 *
 * <p>Usage: {@code java -cp <classes>:<keycloak libs> com.openbank.keycloak.mcp.ExtensionTests <fixture.json>}
 */
public final class ExtensionTests {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    private ExtensionTests() {
    }

    public static void main(String[] args) throws Exception {
        LocalDate today = LocalDate.of(2026, 11, 24);
        check("hosted secret valid through expiry date", McpConnectTokenPolicy.validSecretException("2026-11-24", today));
        check("expired hosted secret refused", !McpConnectTokenPolicy.validSecretException("2026-11-23", today));
        check("missing hosted secret exception refused", !McpConnectTokenPolicy.validSecretException(null, today));
        check("malformed hosted secret exception refused", !McpConnectTokenPolicy.validSecretException("never", today));

        Map<?, ?> fixture = JsonSerialization.readValue(Files.readString(Path.of(args[0])), Map.class);
        eq("fixture version", McpConnectContext.VERSION, fixture.get("version"));
        List<?> cases = (List<?>) fixture.get("cases");
        check("fixture has cases", cases != null && !cases.isEmpty());
        for (Object o : cases) {
            Map<?, ?> c = (Map<?, ?>) o;
            String name = (String) c.get("name");
            Map<?, ?> in = (Map<?, ?>) c.get("input");
            Map<?, ?> ex = (Map<?, ?>) c.get("expect");
            McpConnectContext ctx = McpConnectContext.of((String) in.get("client_id"), (String) in.get("redirect_uri"),
                    (String) in.get("resource"), (String) in.get("scope"), (String) in.get("request_uri"),
                    ((Number) in.get("expires_at")).longValue());
            eq(name + ": redirect_host", ex.get("redirect_host"), ctx.redirectHost());
            eq(name + ": scopes", ex.get("scopes"), ctx.scopes());
            eq(name + ": request_uri_sha256", ex.get("request_uri_sha256"), ctx.requestUriSha256());
            eq(name + ": canonical bytes", ex.get("canonical"), ctx.canonical());
            eq(name + ": payload_sha256", ex.get("payload_sha256"), ctx.payloadSha256());
            eq(name + ": approval_request_id", ex.get("approval_request_id"),
                    McpConnectContext.approvalRequestId((String) in.get("request_uri")));
        }
        for (Object o : (List<?>) fixture.get("invalid")) {
            Map<?, ?> c = (Map<?, ?>) o;
            Map<?, ?> in = (Map<?, ?>) c.get("input");
            boolean refused;
            try {
                McpConnectContext.of((String) in.get("client_id"), (String) in.get("redirect_uri"),
                        (String) in.get("resource"), (String) in.get("scope"), (String) in.get("request_uri"),
                        ((Number) in.get("expires_at")).longValue());
                refused = false;
            } catch (IllegalArgumentException e) {
                refused = true;
            }
            check("invalid refused: " + c.get("name"), refused);
        }
        policy();
        nativeHandoff();
        if (!FAILURES.isEmpty()) {
            FAILURES.forEach(f -> System.err.println("FAIL " + f));
            System.err.printf("%d of %d checks failed%n", FAILURES.size(), checks);
            System.exit(1);
        }
        System.out.printf("extension tests: %d checks, 0 failed%n", checks);
    }

    private static void nativeHandoff() {
        var first = McpNativeHandoff.issue();
        URI uri = URI.create(first.appLink());
        String token = uri.getRawFragment().substring("handoff=".length());
        eq("handoff host", "open-bank.tech", uri.getHost());
        eq("handoff path", "/app/mcp/connect", uri.getPath());
        check("handoff has no query", uri.getRawQuery() == null);
        check("handoff has exactly 32 opaque bytes", Base64.getUrlDecoder().decode(token).length == 32);
        eq("handoff state stores the presented token digest", McpConnectContext.sha256Hex(token),
                first.tokenSha256());
        check("a new browser handoff gets a distinct token",
                !first.tokenSha256().equals(McpNativeHandoff.issue().tokenSha256()));
        check("handoff's diagnostic form does not print the token", !first.toString().contains(token));

        var notes = new HashMap<>(Map.of(
                McpNotes.PKCE_CHALLENGE, "A".repeat(43), McpNotes.STARTED_AT, "1000",
                McpNotes.REDIRECT_URI, "https://assistant.example/oauth/callback",
                McpNotes.RESOURCE, "https://bank.example/customer/mcp",
                McpNotes.SCOPE, "read", McpNotes.REQUEST_URI, McpConnectContext.PAR_REQUEST_URI_PREFIX + "request"));
        var binding = McpNativeHandoff.binding(handoffSession(notes, "tab-a"), "https://bank.example/realms/test",
                1001, 120);
        eq("handoff expires with its configured OAuth session", 1120L, binding.expiresAtEpochSecond());
        eq("handoff binds hosted confidential PAR", "HOSTED", binding.kind());
        var otherTab = McpNativeHandoff.binding(handoffSession(notes, "tab-b"), "https://bank.example/realms/test",
                1001, 120);
        check("handoff cannot move between browser tabs", !binding.sessionRef().equals(otherTab.sessionRef()));
        notes.put(McpNotes.PAR_PROOF_JKT, "A".repeat(43));
        eq("handoff preserves verified DPoP PAR", "DPOP",
                McpNativeHandoff.binding(handoffSession(notes, "tab-a"), "https://bank.example/realms/test",
                        1001, 120).kind());
        expectThrow("handoff expiry is exclusive",
                () -> McpNativeHandoff.binding(handoffSession(notes, "tab-a"), "https://bank.example/realms/test",
                        1120, 120));
        notes.remove(McpNotes.PKCE_CHALLENGE);
        expectThrow("handoff refuses a missing guarded PKCE challenge",
                () -> McpNativeHandoff.binding(handoffSession(notes, "tab-a"), "https://bank.example/realms/test",
                        1001, 120));

    }

    private static AuthenticationSessionModel handoffSession(Map<String, String> notes, String tab) {
        var client = testInterface(ClientModel.class, (method, args) -> switch (method) {
            case "getAttribute" -> "H";
            case "isPublicClient" -> false;
            case "getClientAuthenticatorType" -> "client-jwt";
            case "getId" -> "client-internal";
            case "getClientId" -> "hosted-fixture";
            default -> throw new UnsupportedOperationException(method);
        });
        var root = testInterface(RootAuthenticationSessionModel.class, (method, args) -> "browser-root");
        var realm = testInterface(RealmModel.class, (method, args) -> "realm-id");
        return testInterface(AuthenticationSessionModel.class, (method, args) -> switch (method) {
            case "getClient" -> client;
            case "getAuthNote" -> notes.get(args[0]);
            case "getParentSession" -> root;
            case "getRealm" -> realm;
            case "getTabId" -> tab;
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private static <T> T testInterface(Class<T> type, BiFunction<String, Object[], Object> behavior) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> behavior.apply(method.getName(), args)));
    }

    private static void policy() {
        long h72 = Duration.ofHours(72).toMillis();
        long now = 1_800_000_000_000L;
        check("passkey exactly 72 h old is accepted", McpConnectPolicy.passkeyOldEnough(now - h72, now));
        check("passkey 71 h 59 m old is refused", !McpConnectPolicy.passkeyOldEnough(now - h72 + 60_000, now));
        check("passkey without a creation time is refused", !McpConnectPolicy.passkeyOldEnough(null, now));
        check("passkey created in the future is refused", !McpConnectPolicy.passkeyOldEnough(now + 1, now));

        check("lifespan cannot exceed 300 s", McpConnectPolicy.sessionLifespan(1800) == 300);
        check("lifespan may be shortened", McpConnectPolicy.sessionLifespan(120) == 120);
        check("unset lifespan is 300 s", McpConnectPolicy.sessionLifespan(0) == 300);
        check("session at 299 s is live", !McpConnectPolicy.sessionExpired(1000, 1299, 300));
        check("session at 300 s is expired", McpConnectPolicy.sessionExpired(1000, 1300, 300));
        check("session with no start is expired", McpConnectPolicy.sessionExpired(0, 1, 300));
        check("session started in the future is expired", McpConnectPolicy.sessionExpired(1000, 999, 300));
        check("configured 1800 s still expires at 300 s", McpConnectPolicy.sessionExpired(1000, 1300, 1800));

        check("first poll is never too soon", !McpConnectPolicy.pollTooSoon(0, now));
        check("poll 1 s after the last is too soon", McpConnectPolicy.pollTooSoon(now - 1000, now));
        check("poll 2 s after the last is allowed", !McpConnectPolicy.pollTooSoon(now - 2000, now));

        check("absent resource allowed", McpConnectPolicy.resourceAllowed(List.of(), "https://a/mcp"));
        check("bound resource allowed", McpConnectPolicy.resourceAllowed(List.of("https://a/mcp"), "https://a/mcp"));
        check("foreign resource refused", !McpConnectPolicy.resourceAllowed(List.of("https://evil/mcp"), "https://a/mcp"));
        check("one foreign among several refused",
                !McpConnectPolicy.resourceAllowed(List.of("https://a/mcp", "https://evil/mcp"), "https://a/mcp"));
        check("resource refused when the client is bound to none",
                !McpConnectPolicy.resourceAllowed(List.of("https://a/mcp"), null));

        java.util.Set<String> t = java.util.Set.of("https://a/mcp", "openbank-mcp-service");
        check("exchange for the MCP resource refused", McpConnectPolicy.targetsAny(List.of("https://a/mcp"), t));
        check("exchange for the MCP audience refused",
                McpConnectPolicy.targetsAny(List.of("openbank-edge", "openbank-mcp-service"), t));
        check("exchange for another audience allowed", !McpConnectPolicy.targetsAny(List.of("openbank-edge"), t));
        check("exchange with no audience allowed", !McpConnectPolicy.targetsAny(null, t));
        check("tier attribute marks an MCP client", McpConnectPolicy.isMcpClient("H"));
        check("no tier attribute: not an MCP client", !McpConnectPolicy.isMcpClient(null));
        String ok = "{\"id\":\"c1\",\"partyId\":\"p1\",\"purpose\":\"MCP_CLIENT_CONNECT\","
                + "\"status\":\"COMPLETED\",\"consumedAt\":\"2026-11-17T10:00:00Z\"}";
        eq("consume 200 attested", ScaClient.Outcome.CONSUMED, ScaClient.outcomeOf(200, ok, "c1", "p1"));
        eq("consume 200 for another challenge", ScaClient.Outcome.REFUSED, ScaClient.outcomeOf(200, ok, "c2", "p1"));
        eq("consume 200 for another party", ScaClient.Outcome.REFUSED, ScaClient.outcomeOf(200, ok, "c1", "p2"));
        eq("consume 200 without consumedAt", ScaClient.Outcome.REFUSED,
                ScaClient.outcomeOf(200, ok.replace("\"2026-11-17T10:00:00Z\"", "null"), "c1", "p1"));
        eq("400 SCA_CHALLENGE_PENDING keeps waiting", ScaClient.Outcome.PENDING,
                ScaClient.outcomeOf(400, "{\"code\":\"SCA_CHALLENGE_PENDING\"}", "c1", "p1"));
        eq("400 VALIDATION_ERROR (denied/malformed) is terminal", ScaClient.Outcome.REFUSED,
                ScaClient.outcomeOf(400, "{\"code\":\"VALIDATION_ERROR\"}", "c1", "p1"));
        eq("400 without a body is terminal", ScaClient.Outcome.REFUSED, ScaClient.outcomeOf(400, "", "c1", "p1"));
        eq("400 with garbage is terminal", ScaClient.Outcome.REFUSED, ScaClient.outcomeOf(400, "<html>", "c1", "p1"));
        for (int st : new int[] {401, 403, 404, 409, 410, 500, 503}) {
            eq("status " + st + " is terminal", ScaClient.Outcome.REFUSED,
                    ScaClient.outcomeOf(st, "{\"code\":\"SCA_CHALLENGE_PENDING\"}", "c1", "p1"));
        }
        eq("waiting page escapes markup", "&lt;script&gt;&amp;&quot;&#39;",
                McpWithheldCodeAuthenticator.escape("<script>&\"'"));
    }

    private static void expectThrow(String what, Runnable action) {
        try {
            action.run();
            check(what, false);
        } catch (IllegalArgumentException expected) {
            check(what, true);
        }
    }

    private static void eq(String what, Object expected, Object actual) {
        check(what + " (expected " + expected + ", got " + actual + ")",
                expected != null && expected.equals(actual));
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            FAILURES.add(what);
        }
    }
}
