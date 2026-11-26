// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.OAuthErrorException;
import org.keycloak.crypto.SignatureProvider;
import org.keycloak.crypto.SignatureSignerContext;
import org.keycloak.jose.jws.DefaultTokenManager;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.util.JsonSerialization;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class McpConnectAuthorityClientTests {
    private static final String PARTY = "7639cb30-e287-4821-9ef8-78134bbc1140";
    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
    private static final String ACCOUNT = "00d0391b-4266-4fd4-b574-074b32bacf21";
    private static final long EXPIRY = 1800000123L;
    private static final String INTERNAL = "/api/v1/delegations/mcp-connect/requests";

    private McpConnectAuthorityClientTests() {
    }

    public static void main(String[] args) throws Exception {
        McpConnectContext base = McpConnectContext.of("synthetic-local", "http://localhost:5555/return",
                "https://mcp.test/customer/mcp", "openid balance:read openid", "urn:ietf:params:oauth:request_uri:synthetic-par", EXPIRY);
        var request = new McpConnectAuthorityClient.Request(base, PARTY, KEY);
        require(!request.registration().toString().contains("synthetic-par"), "opaque PAR reference");
        require(request.approvalRequestId().equals("mcp-connect:" + request.reference()), "reserved approval id");

        // The Delegation OpenAPI contract carries arrays at the wire boundary. Do not echo the Java producer.
        var pending = new LinkedHashMap<String, Object>(Map.of(
                "status", "PENDING_SELECTION", "requestRef", request.reference(), "partyId", PARTY,
                "clientId", "synthetic-local", "parJkt", KEY, "expiresAtEpochSecond", EXPIRY,
                "redirectHost", "localhost", "resource", "https://mcp.test/customer/mcp",
                "scopes", List.of("balance:read", "openid")));
        var selected = new LinkedHashMap<>(pending);
        selected.put("status", "SELECTED");
        selected.put("accounts", List.of(Map.of("accountId", ACCOUNT, "capabilities", List.of("ACCOUNT_READ_BALANCES"))));
        String accountDigest = McpConnectContextV2.of(base, PARTY, KEY,
                List.of(new McpConnectContextV2.AccountScope(ACCOUNT, List.of("ACCOUNT_READ_BALANCES")))).payloadSha256();
        var challenge = new McpConnectAuthorityClient.Challenge("f02cb79a-57a5-4cee-94df-5f7a7dd6c925", accountDigest);
        var bound = new LinkedHashMap<>(pending);
        bound.putAll(challenge.binding(request));
        bound.put("status", "CHALLENGE_BOUND");

        var family = new McpConnectAuthorityClient.FamilyReference("a".repeat(64));
        String codeRef = McpConnectTokenReferences.codeBinding("realm-uuid", PARTY, "client-uuid", KEY, KEY);
        var staged = new LinkedHashMap<>(bound);
        staged.put("status", "APPROVED_AWAITING_FAMILY");
        staged.put("codeBindingRef", codeRef);
        var active = new LinkedHashMap<>(staged);
        active.put("status", "ACTIVE");
        active.put("connectionId", "4d1f6c04-ad1d-4c45-a7f5-846e88d584af");
        active.put("refreshFamilyRef", family.value());
        var transport = new Transport(List.of(new Reply(201, pending), new Reply(200, pending),
                new Reply(200, selected), new Reply(200, bound), new Reply(200, staged), new Reply(200, staged),
                new Reply(200, active), new Reply(200, active)));
        var client = new McpConnectAuthorityClient("https://connect.test/", transport);
        client.register(request);
        require(List.of("balance:read", "openid").equals(((Map<?, ?>) transport.posted.getFirst()).get("scopes")),
                "registration sends the sorted distinct scope array required by Delegation");
        require(client.selectedAccounts(request).isEmpty(), "pending selection");
        var scopes = client.selectedAccounts(request);
        require(scopes.size() == 1 && ACCOUNT.equals(scopes.getFirst().accountId()), "attested account");
        client.bindChallenge(request, challenge);
        client.stage(request, challenge, codeRef);
        var resolvedStage = client.staged(PARTY, base.clientId(), KEY, codeRef);
        require(resolvedStage.request().equals(request) && resolvedStage.challenge().equals(challenge),
                "the staged lookup reconstructs the exact context");
        var activation = client.activate(resolvedStage, family);
        require(client.activeFamily(PARTY, base.clientId(), KEY, family).equals(activation.connectionId()),
                "active family attestation");
        require(activation.family().equals(family)
                && activation.connectionId().toString().equals(active.get("connectionId")), "attested activation");
        require(transport.paths.equals(List.of(INTERNAL, INTERNAL + "/" + request.reference() + "/selection",
                INTERNAL + "/" + request.reference() + "/selection", INTERNAL + "/" + request.reference() + "/challenge",
                INTERNAL + "/" + request.reference() + "/stage", INTERNAL + "/staged",
                INTERNAL + "/" + request.reference() + "/activate", INTERNAL + "/family-status")),
                "exact origin paths");
        require(transport.keys.equals(List.of(request.reference(), request.reference(), request.reference(),
                request.reference(), request.reference(), codeRef, request.reference(), family.value())),
                "stable operation-specific idempotency keys");
        require(challenge.binding(request).equals(transport.posted.get(3)), "exact challenge request");
        var activateRequest = challenge.binding(request);
        activateRequest.put("refreshFamilyRef", family.value());
        activateRequest.put("codeBindingRef", codeRef);
        require(activateRequest.equals(transport.posted.get(6)), "exact activation request");
        require(!McpConnectTokenReferences.family("realm-uuid", "actual-sid", "client-uuid", "one")
                .equals(McpConnectTokenReferences.family("realm-uuid", "actual-sid", "client-uuid", "two")),
                "two refresh chains in one client session have different references");

        for (String field : List.of("requestRef", "partyId", "clientId", "parJkt", "expiresAtEpochSecond")) {
            var invalid = new LinkedHashMap<>(selected);
            invalid.put(field, "unexpected");
            refuse(() -> McpConnectAuthorityClient.selectionOf(request, json(invalid)), "identity mismatch " + field);
        }
        for (Object invalidScopes : List.of("balance:read openid", List.of(), List.of("openid", "balance:read"),
                List.of("openid", "openid"), List.of("balance:read", 7), List.of("offline_access", "openid"))) {
            var wrongRegistration = new LinkedHashMap<>(pending);
            wrongRegistration.put("scopes", invalidScopes);
            var rejectedRegistration = new McpConnectAuthorityClient("https://connect.test",
                    new Transport(List.of(new Reply(200, wrongRegistration))));
            refuse(() -> rejectedRegistration.register(request), "registration requires the exact scope array");
            var wrongStaged = new LinkedHashMap<>(staged);
            wrongStaged.put("scopes", invalidScopes);
            var rejectedStaged = new McpConnectAuthorityClient("https://connect.test",
                    new Transport(List.of(new Reply(200, wrongStaged))));
            refuse(() -> rejectedStaged.staged(PARTY, base.clientId(), KEY, codeRef), "staged scopes must be canonical");
        }
        var changedScopes = new LinkedHashMap<>(pending);
        changedScopes.put("scopes", List.of("openid"));
        var rejectedChange = new McpConnectAuthorityClient("https://connect.test",
                new Transport(List.of(new Reply(200, changedScopes))));
        refuse(() -> rejectedChange.register(request), "registration cannot remove a requested scope");

        var malformed = new LinkedHashMap<>(pending);
        malformed.put("accounts", "not-an-array");
        refuse(() -> McpConnectAuthorityClient.selectionOf(request, json(malformed)), "malformed pending response");
        var changed = new LinkedHashMap<>(bound);
        changed.put("contextSha256", "unexpected");
        refuse(() -> McpConnectAuthorityClient.requireChallenge(request, challenge, json(changed), "CHALLENGE_BOUND"),
                "mismatched challenge digest");
        for (String field : List.of("refreshFamilyRef", "connectionId", "challengeId", "contextSha256", "codeBindingRef")) {
            var invalid = new LinkedHashMap<>(active);
            invalid.put(field, "unexpected");
            var mismatched = new McpConnectAuthorityClient("https://connect.test",
                    new Transport(List.of(new Reply(200, invalid))));
            refuse(() -> mismatched.activate(resolvedStage, family), "activation mismatch " + field);
        }
        var revoked = new LinkedHashMap<>(active);
        revoked.put("status", "REVOKED");
        var revokedFamily = new McpConnectAuthorityClient("https://connect.test",
                new Transport(List.of(new Reply(200, revoked))));
        refuse(() -> revokedFamily.activeFamily(PARTY, base.clientId(), KEY, family), "revoked family");
        var down = new McpConnectAuthorityClient("https://connect.test", new Transport(List.of(new Reply(503, pending))));
        refuse(() -> down.register(request), "unavailable authority");
        KeycloakSession defaultOff = tokenPolicySession("L");
        deny(() -> McpConnectTokenPolicy.authorizationCode(defaultOff, null), "disabled code token hook");
        deny(() -> McpConnectTokenPolicy.refresh(defaultOff, null), "disabled refresh token hook");
        KeycloakSession unrelatedClient = tokenPolicySession(null);
        McpConnectTokenPolicy.authorizationCode(unrelatedClient, null);
        McpConnectTokenPolicy.refresh(unrelatedClient, null);
        verifySignedAccessToken(activation.connectionId());
        nativeHandoffContract();
        System.out.println("MCP connect authority: activation, default denial and signed access-token connection binding passed");
    }

    private static void verifySignedAccessToken(UUID connection) throws Exception {
        var pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        SignatureSignerContext signer = SignatureSignerContext.class.cast(Proxy.newProxyInstance(
                SignatureSignerContext.class.getClassLoader(), new Class<?>[] {SignatureSignerContext.class},
                (unused, method, arguments) -> switch (method.getName()) {
                    case "getKid" -> "synthetic-test-key";
                    case "getAlgorithm" -> "RS256";
                    case "sign" -> {
                        Signature signature = Signature.getInstance("SHA256withRSA");
                        signature.initSign(pair.getPrivate());
                        signature.update((byte[]) arguments[0]);
                        yield signature.sign();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
        SignatureProvider provider = proxy(SignatureProvider.class, "signer", signer);
        RealmModel realm = proxy(RealmModel.class, "getDefaultSignatureAlgorithm", "RS256");
        KeycloakContext ctx = KeycloakContext.class.cast(Proxy.newProxyInstance(KeycloakContext.class.getClassLoader(),
                new Class<?>[] {KeycloakContext.class}, (unused, method, arguments) -> switch (method.getName()) {
                    case "getRealm" -> realm;
                    case "getClient" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
        KeycloakSession session = KeycloakSession.class.cast(Proxy.newProxyInstance(KeycloakSession.class.getClassLoader(),
                new Class<?>[] {KeycloakSession.class}, (unused, method, arguments) -> switch (method.getName()) {
                    case "getContext" -> ctx;
                    case "getProvider" -> provider;
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
        var manager = new DefaultTokenManager(session);
        var access = new AccessToken();
        access.type("DPoP");
        McpConnectTokenPolicy.bindConnection(access, connection);
        JWSInput signed = new JWSInput(manager.encode(access));
        Signature verification = Signature.getInstance("SHA256withRSA");
        verification.initVerify(pair.getPublic());
        verification.update(signed.getEncodedSignatureInput().getBytes(StandardCharsets.UTF_8));
        require(verification.verify(signed.getSignature()), "signed by the test realm issuer");
        AccessToken encoded = signed.readJsonContent(AccessToken.class);
        require("DPoP".equals(encoded.getType())
                && connection.toString().equals(encoded.getOtherClaims().get(McpConnectTokenPolicy.CONNECTION_CLAIM)),
                "connection claim survives Keycloak's access-token encoding");
        var unrelated = new JWSInput(manager.encode(new AccessToken())).readJsonContent(AccessToken.class);
        require(!unrelated.getOtherClaims().containsKey(McpConnectTokenPolicy.CONNECTION_CLAIM),
                "unrelated token has no connection claim");
    }

    private static KeycloakSession tokenPolicySession(String tier) {
        ClientModel client = ClientModel.class.cast(Proxy.newProxyInstance(ClientModel.class.getClassLoader(),
                new Class<?>[] {ClientModel.class}, (unused, method, arguments) -> switch (method.getName()) {
                    case "getClientId" -> "synthetic-fixture";
                    case "getAttribute" -> McpConnectPolicy.TIER_ATTRIBUTE.equals(arguments[0]) ? tier : null;
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
        KeycloakContext context = proxy(KeycloakContext.class, "getClient", client);
        var authenticator = new McpWithheldCodeAuthenticatorFactory();
        KeycloakSessionFactory factory = proxy(KeycloakSessionFactory.class, "getProviderFactory", authenticator);
        return KeycloakSession.class.cast(Proxy.newProxyInstance(KeycloakSession.class.getClassLoader(),
                new Class<?>[] {KeycloakSession.class}, (unused, method, arguments) -> switch (method.getName()) {
                    case "getContext" -> context;
                    case "getKeycloakSessionFactory" -> factory;
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
    }

    private static <T> T proxy(Class<T> type, String methodName, Object result) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[] {type}, (unused, method, arguments) -> {
                    if (methodName.equals(method.getName())) {
                        return result;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }));
    }

    @FunctionalInterface
    private interface TokenCall {
        void run() throws ClientPolicyException;
    }

    private static void deny(TokenCall call, String description) throws Exception {
        try {
            call.run();
        } catch (ClientPolicyException expected) {
            require(OAuthErrorException.INVALID_GRANT.equals(expected.getError()), description);
            return;
        }
        throw new AssertionError(description);
    }

    private static void nativeHandoffContract() throws IOException {
        String tokenSha256 = "a".repeat(64);
        String digest = "c".repeat(64);
        var binding = new McpNativeHandoff.Binding("b".repeat(64), "https://bank.example/realms/test",
                "hosted-fixture", "https://bank.example/customer/mcp", "https://assistant.example/callback",
                "d".repeat(64), "HOSTED", null, List.of("balance:read"), EXPIRY);
        var transport = new Transport(List.of(new Reply(204, null), new Reply(204, null),
                new Reply(200, Map.of("partyId", PARTY)), new Reply(204, null),
                new Reply(200, Map.of("partyId", PARTY, "keycloakSubject", "verified-subject",
                        "contextSha256", digest))));
        var client = new McpConnectAuthorityClient("https://connect.test", transport);
        client.issueNativeHandoff(tokenSha256, binding);
        require(client.claimedNativeParty(tokenSha256, binding) == null, "204 is pending without identity");
        require(PARTY.equals(client.claimedNativeParty(tokenSha256, binding)), "claimed party parses exactly");
        client.attestNativeSelection(tokenSha256, binding, digest);
        var receipt = client.consumeNativeHandoff(tokenSha256, binding);
        require(receipt.equals(new McpConnectAuthorityClient.NativeReceipt(PARTY, "verified-subject", digest)),
                "native receipt binds identity and selected context");
        String prefix = "/api/v1/delegations/mcp-connect/native-handoffs/issuer/";
        require(transport.paths.equals(List.of(prefix + "issue", prefix + "claimed-party",
                prefix + "claimed-party", prefix + "attest-selection", prefix + "consume")), "native issuer paths");
        require(transport.keys.stream().allMatch(tokenSha256::equals), "native issuer idempotency uses digest");
        require(digest.equals(((Map<?, ?>) transport.posted.get(3)).get("contextSha256")),
                "exact selected digest is sent only on attestation");
    }

    private record Reply(int status, Map<String, Object> body) {
    }

    private static final class Transport extends ScaClient {
        private final List<Reply> replies;
        private final List<String> paths = new ArrayList<>();
        private final List<String> keys = new ArrayList<>();
        private final List<Object> posted = new ArrayList<>();

        Transport(List<Reply> replies) {
            super(new Settings(null, null, null, null));
            this.replies = replies;
        }

        @Override
        HttpResponse<String> postWithBankIdentity(HttpRequest.Builder builder, Object body) throws IOException {
            int index = paths.size();
            var request = builder.build();
            keys.add(request.headers().firstValue("Idempotency-Key").orElseThrow());
            var uri = request.uri();
            require("https".equals(uri.getScheme()) && "connect.test".equals(uri.getHost()), "trusted HTTPS origin");
            paths.add(uri.getPath());
            posted.add(body);
            Reply reply = replies.get(index);
            String response = JsonSerialization.writeValueAsString(reply.body());
            return cast(Proxy.newProxyInstance(HttpResponse.class.getClassLoader(), new Class<?>[] {HttpResponse.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "statusCode" -> reply.status();
                        case "body" -> response;
                        default -> throw new UnsupportedOperationException(method.getName());
                    }));
        }
    }

    @SuppressWarnings("unchecked") // The synthetic runtime response body is a String.
    private static HttpResponse<String> cast(Object proxy) {
        return (HttpResponse<String>) proxy;
    }

    private static JsonNode json(Map<String, Object> value) throws IOException {
        return JsonSerialization.readValue(JsonSerialization.writeValueAsString(value), JsonNode.class);
    }

    @FunctionalInterface
    private interface IoCall {
        void run() throws IOException;
    }

    private static void refuse(IoCall call, String description) throws Exception {
        try {
            call.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(description);
    }

    private static void require(boolean result, String description) {
        if (!result) {
            throw new AssertionError(description);
        }
    }
}
