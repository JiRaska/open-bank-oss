// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.keycloak.common.VerificationException;
import org.keycloak.common.crypto.CryptoIntegration;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.crypto.SignatureProvider;
import org.keycloak.crypto.SignatureProviderFactory;
import org.keycloak.crypto.SignatureVerifierContext;
import org.keycloak.crypto.def.DefaultCryptoProvider;
import org.keycloak.jose.jws.DefaultTokenManager;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.jose.jws.crypto.HashUtils;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.representations.AccessToken;
import org.keycloak.util.JWKSUtils;
import org.keycloak.util.JsonSerialization;

/** Exercises the pinned realm-token and original-resource validators with ephemeral signatures. */
public final class McpReadDelegationEvidenceTests {
    private static final String ISSUER = "https://identity.test/realms/openbank-customers";
    private static final URI RESOURCE = URI.create("https://mcp.test/customer/mcp");
    private static final String CLIENT = "test-local-client";
    private static final String NONCE = "test-resource-issued-nonce";
    private static final UUID ACCOUNT = UUID.fromString("7b103c26-b2f1-4522-ab39-66889d05d700");
    private static final UUID CONNECTION = UUID.fromString("f02cb79a-57a5-4cee-94df-5f7a7dd6c925");
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();

    private McpReadDelegationEvidenceTests() {
    }

    public static void main(String[] args) throws Exception {
        CryptoIntegration.setProvider(new DefaultCryptoProvider());
        KeyPair issuer = keyPair();
        KeyPair holder = keyPair();
        Map<String, Object> holderHeader = Map.of("alg", "ES256", "typ", "dpop+jwt", "jwk", jwk(holder));
        var publicJwk = new JWSInput(sign(holderHeader, Map.of(), holder)).getHeader().getKey();
        String thumbprint = JWKSUtils.computeThumbprint(publicJwk);
        long now = Instant.now().getEpochSecond();
        Clock clock = Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC);
        Map<String, Object> tokenHeader = Map.of("alg", "ES256", "typ", "JWT", "kid", "test-realm-key");
        Map<String, Object> tokenClaims = Map.of("iss", ISSUER, "aud", "openbank-mcp-service", "typ", "DPoP",
                "sub", "test-customer", "party_id", ACCOUNT.toString(), "azp", CLIENT, "iat", now, "exp", now + 120, "cnf", Map.of("jkt", thumbprint));
        String token = sign(tokenHeader, tokenClaims, issuer);
        KeycloakSession session = session(issuer);
        McpReadDelegationEvidence.NonceAuthority nonces =
                (nonce, key, resource) -> NONCE.equals(nonce) && thumbprint.equals(key)
                        && RESOURCE.toString().equals(resource) ? McpReadDelegationEvidence.NonceStatus.VALID
                        : McpReadDelegationEvidence.NonceStatus.INVALID;
        var verifier = new McpReadDelegationEvidence(ISSUER, RESOURCE, clock, nonces, false);
        var boundVerifier = new McpReadDelegationEvidence(ISSUER, RESOURCE, clock, nonces, true);

        Map<String, Object> claims = claims(token, now);
        String valid = sign(holderHeader, claims, holder);
        var verified = verifier.verify(session, request(token, valid));
        require("test-customer".equals(verified.subject()) && CLIENT.equals(verified.clientId())
                && ACCOUNT.equals(verified.partyId()) && thumbprint.equals(verified.keyThumbprint()) && claims.get("jti").equals(verified.proofId()));
        refuse(verifier, session, token, valid);
        refuse(verifier, session, token, sign(holderHeader, differing(token, now, "htm", "GET"), holder));
        refuse(verifier, session, token, sign(holderHeader, differing(token, now, "htu", RESOURCE + "?query"), holder));
        refuse(verifier, session, token, sign(holderHeader, differing(token, now, "ath", "different-token"), holder));
        refuse(verifier, session, token, sign(holderHeader, differing(token, now, "iat", now - 11), holder));
        refuse(verifier, session, token, sign(holderHeader, differing(token, now, "iat", now + 6), holder));
        refuse(verifier, session, token, sign(holderHeader, differing(token, now, "nonce", "unknown-nonce"), holder));
        refuse(verifier, session, token, sign(holderHeader, claims(token, now), issuer));
        Map<String, Object> wrongKey = Map.of("alg", "ES256", "typ", "dpop+jwt", "jwk", jwk(issuer));
        refuse(verifier, session, token, sign(wrongKey, claims(token, now), issuer));

        String unverifiedToken = sign(tokenHeader, tokenClaims, holder);
        refuse(verifier, session, unverifiedToken, sign(holderHeader, claims(unverifiedToken, now), holder));

        var missingParty = new HashMap<>(tokenClaims);
        missingParty.remove("party_id");
        String tokenWithoutParty = sign(tokenHeader, missingParty, issuer);
        refuse(verifier, session, tokenWithoutParty, sign(holderHeader, claims(tokenWithoutParty, now), holder));

        var references = new McpIssuerProofReference(new byte[32], "synthetic");
        String reference = references.key("customers", verified);
        require(reference.matches("[0-9a-f]{64}"));
        require(reference.equals(references.key("customers", verified)));
        require(!reference.equals(references.key("different-realm", verified)));
        var otherProof = new McpReadDelegationEvidence.Verified(
                verified.subject(), verified.partyId(), verified.clientId(), verified.keyThumbprint(), "other-proof",
                verified.issuer(), verified.validUntil(), null);
        require(!reference.equals(references.key("customers", otherProof)));

        var confirmation = new AccessToken.Confirmation();
        confirmation.setCertThumbprint("A".repeat(43));
        var grant = new McpBalancePermitClient.Permit(UUID.randomUUID(), ACCOUNT, clock.instant().plusSeconds(35), null);
        var read = McpBalanceReadToken.create(verified, grant, confirmation, clock.instant());
        require(Arrays.equals(new String[] {"openbank-balance-service"}, read.getAudience()));
        require(read.getExp() == now + 35 && McpBalanceReadToken.SCOPE.equals(read.getScope()));
        require(read.getRealmAccess() == null && read.getResourceAccess().isEmpty());
        require(confirmation.getCertThumbprint().equals(read.getConfirmation().getCertThumbprint()));
        require(ACCOUNT.toString().equals(read.getOtherClaims().get("account_id")));
        boolean refusedWithoutCertificate = false;
        try {
            McpBalanceReadToken.create(verified, grant, null, clock.instant());
        } catch (IOException expected) {
            refusedWithoutCertificate = true;
        }
        require(refusedWithoutCertificate);

        var boundClaims = new HashMap<>(tokenClaims);
        boundClaims.put(McpReadDelegationEvidence.CONNECTION_CLAIM, CONNECTION.toString());
        String boundToken = sign(tokenHeader, boundClaims, issuer);
        var bound = boundVerifier.verify(session, new McpReadDelegationRequest(boundToken,
                sign(holderHeader, claims(boundToken, now), holder), ACCOUNT, CONNECTION));
        require(CONNECTION.equals(bound.connectionId()));
        require(CONNECTION.toString().equals(McpBalancePermitClient.permitBody(bound, ACCOUNT, reference).get("connectionId")));
        require(!McpBalancePermitClient.permitBody(verified, ACCOUNT, reference).containsKey("connectionId"));
        var boundGrant = new McpBalancePermitClient.Permit(grant.grantId(), ACCOUNT, grant.validUntil(), CONNECTION);
        var boundRead = McpBalanceReadToken.create(bound, boundGrant, confirmation, clock.instant());
        require(CONNECTION.toString().equals(boundRead.getOtherClaims().get(McpReadDelegationEvidence.CONNECTION_CLAIM)));
        require(!read.getOtherClaims().containsKey(McpReadDelegationEvidence.CONNECTION_CLAIM));
        try {
            McpBalanceReadToken.create(bound, grant, confirmation, clock.instant());
            throw new AssertionError("unbound permit accepted for a bound subject");
        } catch (IOException expected) {
            // The second check at token construction denies a wrong permit.
        }
        refuse(boundVerifier, session, request(boundToken, sign(holderHeader, claims(boundToken, now), holder)));
        var wrongConnection = new McpReadDelegationRequest(boundToken,
                sign(holderHeader, claims(boundToken, now), holder), ACCOUNT, UUID.randomUUID());
        refuse(boundVerifier, session, wrongConnection);
        refuse(verifier, session, wrongConnection);
        refuse(boundVerifier, session, new McpReadDelegationRequest(token,
                sign(holderHeader, claims(token, now), holder), ACCOUNT, CONNECTION));
        boundClaims.put(McpReadDelegationEvidence.CONNECTION_CLAIM, CONNECTION.toString().toUpperCase(java.util.Locale.ROOT));
        String malformedConnection = sign(tokenHeader, boundClaims, issuer);
        refuse(boundVerifier, session, new McpReadDelegationRequest(malformedConnection,
                sign(holderHeader, claims(malformedConnection, now), holder), ACCOUNT, CONNECTION));
        refuse(verifier, session, request(boundToken, sign(holderHeader, claims(boundToken, now), holder)));

        var unavailable = new McpReadDelegationEvidence(ISSUER, RESOURCE, clock,
                (nonce, key, resource) -> McpReadDelegationEvidence.NonceStatus.UNAVAILABLE, false);
        try {
            unavailable.verify(session, request(token, sign(holderHeader, claims(token, now), holder)));
            throw new AssertionError("Expected missing nonce authority to refuse");
        } catch (McpReadDelegationEvidence.NonceUnavailable expected) {
            System.out.println("MCP evidence and balance claims: 35 checks passed");
        }
    }

    private static Map<String, Object> claims(String token, long now) {
        return Map.of("jti", UUID.randomUUID().toString(), "htm", "POST", "htu", RESOURCE.toString(),
                "iat", now, "ath", HashUtils.accessTokenHash("ES256", token, true), "nonce", NONCE);
    }

    private static Map<String, Object> differing(String token, long now, String claim, Object value) {
        var result = new HashMap<>(claims(token, now));
        result.put(claim, value);
        return result;
    }

    private static McpReadDelegationRequest request(String token, String proof) {
        return new McpReadDelegationRequest(token, proof, ACCOUNT, null);
    }

    private static void refuse(McpReadDelegationEvidence verifier, KeycloakSession session, String token, String proof) {
        refuse(verifier, session, request(token, proof));
    }

    private static void refuse(McpReadDelegationEvidence verifier, KeycloakSession session,
                               McpReadDelegationRequest request) {
        try {
            verifier.verify(session, request);
        } catch (VerificationException expected) {
            return;
        }
        throw new AssertionError("Expected evidence refusal");
    }

    private static void require(boolean result) {
        if (!result) {
            throw new AssertionError("Expected the signed original request binding");
        }
    }

    private static KeycloakSession session(KeyPair issuer) {
        ClientModel client = fixture(ClientModel.class, (method, arguments) -> switch (method) {
            case "isEnabled" -> true;
            case "getAttribute" -> "L";
            default -> throw new UnsupportedOperationException(method);
        });
        RealmModel realm = fixture(RealmModel.class, (method, arguments) -> switch (method) {
            case "getClientByClientId" -> CLIENT.equals(arguments[0]) ? client : null;
            default -> throw new UnsupportedOperationException(method);
        });
        KeycloakContext context = fixture(KeycloakContext.class, (method, arguments) -> switch (method) {
            case "getRealm" -> realm;
            default -> throw new UnsupportedOperationException(method);
        });
        SignatureProviderFactory factory = fixture(SignatureProviderFactory.class, (method, arguments) -> switch (method) {
            case "getId" -> "ES256";
            case "getJwkPrivateKeyClaims" -> Set.of("d");
            default -> throw new UnsupportedOperationException(method);
        });
        SignatureProvider provider = fixture(SignatureProvider.class, (method, arguments) -> switch (method) {
            case "isAsymmetricAlgorithm" -> true;
            case "verifier" -> signatureVerifier(arguments[0] instanceof KeyWrapper key
                    ? (PublicKey) key.getPublicKey() : issuer.getPublic());
            default -> throw new UnsupportedOperationException(method);
        });
        KeycloakSessionFactory sessionFactory = fixture(KeycloakSessionFactory.class, (method, arguments) -> switch (method) {
            case "getProviderFactoriesStream" -> Stream.of(factory);
            case "getProviderFactory" -> factory;
            default -> throw new UnsupportedOperationException(method);
        });
        Set<String> seen = new HashSet<>();
        SingleUseObjectProvider replay = fixture(SingleUseObjectProvider.class, (method, arguments) -> switch (method) {
            case "putIfAbsent" -> seen.add((String) arguments[0]);
            default -> throw new UnsupportedOperationException(method);
        });
        KeycloakSession[] session = new KeycloakSession[1];
        session[0] = fixture(KeycloakSession.class, (method, arguments) -> switch (method) {
            case "tokens" -> new DefaultTokenManager(session[0]);
            case "getContext" -> context;
            case "getProvider" -> provider;
            case "getKeycloakSessionFactory" -> sessionFactory;
            case "singleUseObjects" -> replay;
            default -> throw new UnsupportedOperationException(method);
        });
        return session[0];
    }

    private static SignatureVerifierContext signatureVerifier(PublicKey key) {
        return SignatureVerifierContext.class.cast(Proxy.newProxyInstance(
                SignatureVerifierContext.class.getClassLoader(), new Class<?>[] {SignatureVerifierContext.class},
                (proxy, method, arguments) -> {
                    if ("verify".equals(method.getName())) {
                        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
                        signature.initVerify(key);
                        signature.update((byte[]) arguments[0]);
                        return signature.verify((byte[]) arguments[1]);
                    }
                    if ("getAlgorithm".equals(method.getName())) {
                        return "ES256";
                    }
                    throw new UnsupportedOperationException(method.getName());
                }));
    }

    private static <T> T fixture(Class<T> type, BiFunction<String, Object[], Object> methods) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, arguments) -> methods.apply(method.getName(), arguments)));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static Map<String, String> jwk(KeyPair pair) {
        ECPublicKey key = (ECPublicKey) pair.getPublic();
        return Map.of("kty", "EC", "crv", "P-256",
                "x", coordinate(key.getW().getAffineX().toByteArray()),
                "y", coordinate(key.getW().getAffineY().toByteArray()));
    }

    private static String coordinate(byte[] integer) {
        byte[] coordinate = new byte[32];
        byte[] magnitude = integer.length == 33 ? Arrays.copyOfRange(integer, 1, 33) : integer;
        System.arraycopy(magnitude, 0, coordinate, coordinate.length - magnitude.length, magnitude.length);
        return BASE64.encodeToString(coordinate);
    }

    private static String sign(Map<String, Object> header, Map<String, Object> claims, KeyPair signer) throws Exception {
        String signed = BASE64.encodeToString(JsonSerialization.writeValueAsBytes(header))
                + "." + BASE64.encodeToString(JsonSerialization.writeValueAsBytes(claims));
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(signer.getPrivate());
        signature.update(signed.getBytes(StandardCharsets.UTF_8));
        return signed + "." + BASE64.encodeToString(signature.sign());
    }
}
