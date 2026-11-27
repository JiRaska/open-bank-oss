// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.keycloak.mcp;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import javax.imageio.ImageIO;

import org.keycloak.models.ClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.protocol.oidc.representations.OIDCConfigurationRepresentation;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;
import org.keycloak.util.JsonSerialization;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

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
        nativeHandoffPage();
        scopedCimdMetadata();
        if (!FAILURES.isEmpty()) {
            FAILURES.forEach(f -> System.err.println("FAIL " + f));
            System.err.printf("%d of %d checks failed%n", FAILURES.size(), checks);
            System.exit(1);
        }
        System.out.printf("extension tests: %d checks, 0 failed%n", checks);
    }

    private static void nativeHandoffPage() throws Exception {
        var issued = McpNativeHandoff.issue();
        var response = McpNativeHandoffPage.render("/login-actions/authenticate?one=1&two=2", "<assistant>", issued);
        String html = response.getEntity().toString();
        check("page contains both Czech and English instructions",
                html.contains("Propojení AI asistenta") && html.contains("Connect your AI assistant")
                        && html.contains("schvalte") && html.contains("approve"));
        check("page escapes dynamic client name and action URL",
                html.contains("&lt;assistant&gt;") && !html.contains("<assistant>")
                        && html.contains("one=1&amp;two=2"));
        check("direct link and form keep exact bank handoff",
                html.contains("href=\"" + issued.appLink() + "\"")
                        && html.contains("name=\"handoff\" value=\"" + issued.formValue() + "\""));
        check("page retains automatic 3-second polling and cancel",
                html.contains("requestSubmit();") && html.contains("},3000)")
                        && html.contains("name=\"cancel\" value=\"1\""));
        var nonce = Pattern.compile("<style nonce=\"([0-9a-f-]+)\">").matcher(html);
        check("style nonce exists", nonce.find());
        String nonceValue = nonce.group(1);
        String csp = response.getHeaderString("Content-Security-Policy");
        check("CSP permits only nonce styles/scripts and embedded artwork",
                csp.contains("default-src 'none'") && csp.contains("img-src data:")
                        && csp.contains("style-src 'nonce-" + nonceValue + "'")
                        && csp.contains("script-src 'nonce-" + nonceValue + "'")
                        && !csp.contains("unsafe-inline") && html.contains("<script nonce=\"" + nonceValue + "\">"));
        eq("handoff page is not cached", "no-store", response.getHeaderString("Cache-Control"));
        eq("handoff page referrer omitted", "no-referrer", response.getHeaderString("Referrer-Policy"));

        var qr = Pattern.compile("data:image/png;base64,([A-Za-z0-9+/=]+)").matcher(html);
        check("handoff QR is served as local PNG", qr.find());
        var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(qr.group(1))));
        var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        eq("QR encodes exactly the direct universal link", issued.appLink(), new MultiFormatReader().decode(bitmap).getText());

        var art = Pattern.compile("data:image/webp;base64,([A-Za-z0-9+/=]+)").matcher(html);
        check("site artwork is bundled as a local WebP", art.find());
        try (var source = McpNativeHandoffPage.class.getResourceAsStream("explorers-lipa.webp")) {
            check("bundled website artwork bytes match the page", source != null
                    && Arrays.equals(source.readAllBytes(), Base64.getDecoder().decode(art.group(1))));
        }

        String waiting = McpNativeHandoffPage.render("/login-actions/authenticate", "Assistant", null).getEntity().toString();
        check("waiting page introduces no handoff material",
                waiting.contains("Continue in the app") && !waiting.contains("name=\"handoff\"")
                        && !waiting.contains("data:image/png") && waiting.contains("requestSubmit();"));
    }

    private static void nativeHandoff() {
        var first = McpNativeHandoff.issue();
        URI uri = URI.create(first.appLink());
        String token = uri.getRawFragment().substring("handoff=".length());
        eq("handoff host", "open-bank.tech", uri.getHost());
        eq("handoff path", "/app/mcp/connect", uri.getPath());
        check("handoff has no query", uri.getRawQuery() == null);
        check("early browser poll can keep the original QR with the expected digest",
                first.appLink().equals(McpNativeHandoff.recover(token, first.tokenSha256()).appLink()));
        check("a different browser handoff cannot be recovered",
                McpNativeHandoff.recover(token, McpNativeHandoff.issue().tokenSha256()) == null);
        check("malformed retry token cannot be reflected",
                McpNativeHandoff.recover("<script>", first.tokenSha256()) == null);
        check("handoff has exactly 32 opaque bytes", Base64.getUrlDecoder().decode(token).length == 32);
        eq("handoff state stores the raw 32-byte token digest",
                McpConnectContext.sha256Hex(Base64.getUrlDecoder().decode(token)), first.tokenSha256());
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
        var attributes = Map.of(
                "openbank.mcp.tier", "H", "openbank.mcp.profile", "bank-hosted-v2",
                "use.jwks.string", "true", "use.jwks.url", "false",
                "token.endpoint.auth.signing.alg", "ES256", "dpop.bound.access.tokens", "true",
                "require.pushed.authorization.requests", "true", "pkce.code.challenge.method", "S256");
        String callback = "https://mcp.open-bank.tech/oauth/hosted/callback";
        notes.put(McpNotes.REDIRECT_URI, callback);
        var chatgpt = adapterClient("mcp-bank-hosted-chatgpt", attributes, callback, true);
        var claude = adapterClient("mcp-bank-hosted-claude", attributes, callback, true);
        for (ClientModel client : List.of(chatgpt, claude)) {
            var host = McpNativeHandoff.binding(handoffSession(notes, "tab-a", client),
                    "https://bank.example/realms/test", 1001, 120);
            eq("adapter handoff binds vendor identity", client.getClientId(), host.clientId());
            eq("adapter handoff preserves verified DPoP", "DPOP", host.kind());
        }
        notes.remove(McpNotes.PAR_PROOF_JKT);
        expectThrow("bank adapter cannot fall back to bearer PAR",
                () -> McpNativeHandoff.binding(handoffSession(notes, "tab-a", chatgpt),
                        "https://bank.example/realms/test", 1001, 120));
        check("unregistered vendor cannot copy the adapter profile", !McpConnectPolicy.bankAdapterAllowed(
                adapterClient("mcp-unknown-vendor", attributes, callback, true)));
        check("disabled adapter stays unavailable", !McpConnectPolicy.bankAdapterAllowed(
                adapterClient(chatgpt.getClientId(), attributes, callback, false)));
        check("adapter callback must match exactly", !McpConnectPolicy.bankAdapterAllowed(
                adapterClient(claude.getClientId(), attributes, callback + "/other", true)));
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
        return handoffSession(notes, tab, client);
    }

    private static ClientModel adapterClient(String clientId, Map<String, String> attributes, String callback,
                                             boolean enabled) {
        return testInterface(ClientModel.class, (method, args) -> switch (method) {
            case "getAttribute" -> attributes.get(args[0]);
            case "isEnabled" -> enabled;
            case "isPublicClient" -> false;
            case "getClientAuthenticatorType" -> "client-jwt";
            case "getId", "getClientId" -> clientId;
            case "getRedirectUris" -> Set.of(callback);
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private static AuthenticationSessionModel handoffSession(Map<String, String> notes, String tab, ClientModel client) {
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

    private static void scopedCimdMetadata() throws Exception {
        String clientId = "https://chatgpt.com/oauth/client.json";
        String callback = "https://chatgpt.com/connector_platform_oauth_redirect";
        String issuer = "https://kc.open-bank.tech/realms/openbank-customers";
        String jwks = "{\"keys\":[{\"kid\":\"synthetic-test-key\"}]}";
        String pin = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(jwks.getBytes(StandardCharsets.UTF_8)));
        Map<String, String> attributes = new HashMap<>(Map.of(
                "openbank.mcp.tier", "H",
                "openbank.mcp.resource", "https://mcp.open-bank.tech/customer/mcp",
                "pkce.code.challenge.method", "S256",
                "require.pushed.authorization.requests", "true",
                "token.endpoint.auth.signing.alg", "RS256",
                "use.jwks.string", "true",
                "use.jwks.url", "false",
                "jwks.string", jwks));
        ClientModel client = testInterface(ClientModel.class, (method, args) -> switch (method) {
            case "getClientId" -> clientId;
            case "isEnabled", "isStandardFlowEnabled" -> true;
            case "isPublicClient", "isImplicitFlowEnabled", "isDirectAccessGrantsEnabled",
                    "isServiceAccountsEnabled" -> false;
            case "getClientAuthenticatorType" -> "client-jwt";
            case "getRedirectUris" -> Set.of(callback);
            case "getAuthenticationFlowBindingOverride" -> "efd9751b-94d8-4ae5-80dc-3f078a671b2d";
            case "getAttribute" -> attributes.get(args[0]);
            default -> throw new UnsupportedOperationException(method);
        });
        RealmModel realm = testInterface(RealmModel.class, (method, args) -> switch (method) {
            case "getName" -> "openbank-customers";
            case "getClientByClientId" -> clientId.equals(args[0]) ? client : null;
            default -> throw new UnsupportedOperationException(method);
        });
        RealmModel unrelated = testInterface(RealmModel.class, (method, args) -> "master");
        check("scoped CIMD without deployment pin", advertisedCimd(realm, issuer, null) == null);
        check("scoped CIMD with differing pin", advertisedCimd(realm, issuer, "f".repeat(64)) == null);
        check("scoped CIMD does not claim another issuer", advertisedCimd(realm, "https://other.invalid", pin) == null);
        check("scoped CIMD does not claim another realm", advertisedCimd(unrelated, issuer, pin) == null);
        eq("exact static CIMD advertisement", true, advertisedCimd(realm, issuer, pin));
        var inherited = new OIDCConfigurationRepresentation();
        inherited.setClientIdMetadataDocumentSupported(true);
        McpChatGptCimdDiscoveryFactory.advertise(inherited, unrelated, null);
        eq("scoped CIMD leaves unrelated inherited metadata unchanged", true,
                inherited.getClientIdMetadataDocumentSupported());
        McpChatGptCimdDiscoveryFactory.advertise(inherited, realm, null);
        check("scoped CIMD clears unsupported inherited advertisement",
                inherited.getClientIdMetadataDocumentSupported() == null);
        attributes.put("require.pushed.authorization.requests", "false");
        check("scoped CIMD requires client PAR", advertisedCimd(realm, issuer, pin) == null);
    }

    private static Boolean advertisedCimd(RealmModel realm, String issuer, String pin) {
        var config = new OIDCConfigurationRepresentation();
        config.setIssuer(issuer);
        config.setPushedAuthorizationRequestEndpoint(issuer + "/protocol/openid-connect/ext/par/request");
        McpChatGptCimdDiscoveryFactory.advertise(config, realm, pin);
        return config.getClientIdMetadataDocumentSupported();
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
