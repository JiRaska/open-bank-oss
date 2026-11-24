// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Standalone test runner for the request and verified-caller boundary. */
public final class McpReadDelegationRequestTests {
    private McpReadDelegationRequestTests() {
    }

    public static void main(String[] args) throws IOException, CertificateException {
        String realm = McpReadDelegationRequest.REALM;
        String client = McpReadDelegationRequest.CLIENT_ID;
        String x509 = McpReadDelegationRequest.X509_AUTHENTICATOR;
        require(McpReadDelegationRequest.admitsCaller(realm, client, true, false, x509, true, true),
                "Expected the approved authenticated workload");
        require(!McpReadDelegationRequest.admitsCaller("openbank", client, true, false, x509, true, true),
                "Expected the customers realm");
        require(!McpReadDelegationRequest.admitsCaller(realm, "mcp-local-reference", true, false, x509, true, true),
                "Expected the dedicated workload client");
        require(!McpReadDelegationRequest.admitsCaller(realm, client, false, false, x509, true, true),
                "Expected an enabled client");
        require(!McpReadDelegationRequest.admitsCaller(realm, client, true, true, x509, true, true),
                "Expected a confidential client");
        require(!McpReadDelegationRequest.admitsCaller(realm, client, true, false, "client-secret", true, true),
                "Expected the X.509 client authenticator");
        require(!McpReadDelegationRequest.admitsCaller(realm, client, true, false, x509, false, true),
                "Expected an exact subject");
        require(!McpReadDelegationRequest.admitsCaller(realm, client, true, false, x509, true, false),
                "Expected an explicit certificate issuer");

        UUID account = UUID.fromString("7b103c26-b2f1-4522-ab39-66889d05d700");
        Map<String, List<String>> request = Map.of(
                "grant_type", List.of(McpReadDelegationRequest.GRANT_TYPE),
                "client_id", List.of(client),
                "subject_token", List.of("header.subject.signature"),
                "resource_dpop_proof", List.of("header.proof.signature"),
                "account_id", List.of(account.toString()));
        McpReadDelegationRequest parsed = McpReadDelegationRequest.parse(request);
        require(parsed.accountId().equals(account) && parsed.connectionId() == null, "Expected the legacy account envelope");
        var bound = McpReadDelegationRequest.parse(with(request, "connection_id", List.of(account.toString())));
        require(account.equals(bound.connectionId()), "Expected the exact connection UUID");
        require(!parsed.toString().contains("signature"), "Expected credential redaction");

        refuse(Map.of());
        refuse(with(request, "client_id", List.of("mcp-local-reference")));
        refuse(with(request, "scope", List.of("unrecognized")));
        refuse(with(request, "account_id", List.of("1-2-3-4-5")));
        refuse(with(request, "account_id", List.of(account.toString(), account.toString())));
        refuse(with(request, "subject_token", List.of("header.subject.")));
        refuse(with(request, "resource_dpop_proof", List.of("header." + "a".repeat(4096) + ".signature")));
        refuse(with(request, "connection_id", List.of(account.toString().toUpperCase(java.util.Locale.ROOT))));
        refuse(with(request, "connection_id", List.of(account.toString(), account.toString())));
        refuse(with(request, "connection_id", List.of("")));
        checkNativePeerCertificate(Path.of(args[0]));
        System.out.println("MCP read delegation boundary: 28 checks passed");
    }

    private static void checkNativePeerCertificate(Path fixture) throws IOException, CertificateException {
        X509Certificate leaf;
        try (var input = Files.newInputStream(fixture)) {
            leaf = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        String subjectDn = "CN=Synthetic MCP Read Client";
        X509Certificate[] chain = {leaf};
        var confirmation = McpReadClientCertificate.confirmation(chain, subjectDn, subjectDn);
        require(confirmation != null && "OY5SssrFS3hAl6lmWdG66gOSHuOg3WbIW4MP2evzSxM".equals(
                confirmation.getCertThumbprint()), "Expected the native peer leaf fingerprint");
        require(McpReadClientCertificate.confirmation(null, subjectDn, subjectDn) == null,
                "Expected a native TLS connection with a client certificate");
        require(McpReadClientCertificate.confirmation(chain, "CN=Different Subject", subjectDn) == null,
                "Expected the configured native peer subject DN");
        require(McpReadClientCertificate.confirmation(chain, subjectDn, "CN=Different Issuing CA") == null,
                "Expected the configured native peer issuing CA DN");
        require(!McpReadClientCertificate.hasDedicatedUri(List.of(List.of(2, McpReadClientCertificate.URI_SAN))),
                "Expected URI SAN type");
        require(!McpReadClientCertificate.hasDedicatedUri(List.of(List.of(6, McpReadClientCertificate.URI_SAN + "/other"))),
                "Expected the exact dedicated URI");
        require(!McpReadClientCertificate.hasDedicatedUri(List.of(
                List.of(6, McpReadClientCertificate.URI_SAN), List.of(6, "spiffe://openbank/another-workload"))),
                "Expected exactly one URI identity");
    }

    private static Map<String, List<String>> with(Map<String, List<String>> request, String key, List<String> values) {
        Map<String, List<String>> copy = new HashMap<>(request);
        copy.put(key, values);
        return copy;
    }

    private static void refuse(Map<String, List<String>> request) {
        try {
            McpReadDelegationRequest.parse(request);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected request refusal");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
