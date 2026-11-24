// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import javax.security.auth.x500.X500Principal;

import org.keycloak.representations.AccessToken;

/** Pins the dedicated identity and binds the token to the same native TLS peer. */
final class McpReadClientCertificate {
    static final String URI_SAN = "spiffe://openbank/mcp-read";
    private static final int URI_NAME_TYPE = 6;

    private McpReadClientCertificate() {
    }

    static AccessToken.Confirmation confirmation(X509Certificate[] chain, String subjectDn, String issuerDn) {
        if (chain == null || chain.length == 0 || chain[0] == null || subjectDn == null || issuerDn == null) {
            return null;
        }
        X509Certificate leaf = chain[0];
        try {
            if (!leaf.getSubjectX500Principal().equals(new X500Principal(subjectDn))
                    || !leaf.getIssuerX500Principal().equals(new X500Principal(issuerDn))
                    || !hasDedicatedUri(leaf.getSubjectAlternativeNames())) {
                return null;
            }
            byte[] fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded());
            var confirmation = new AccessToken.Confirmation();
            confirmation.setCertThumbprint(Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprint));
            return confirmation;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    static boolean hasDedicatedUri(Collection<List<?>> names) {
        if (names == null) {
            return false;
        }
        var uris = names.stream()
                .filter(name -> name != null && !name.isEmpty() && Integer.valueOf(URI_NAME_TYPE).equals(name.getFirst()))
                .toList();
        return uris.size() == 1 && uris.getFirst().size() == 2 && URI_SAN.equals(uris.getFirst().get(1));
    }
}
