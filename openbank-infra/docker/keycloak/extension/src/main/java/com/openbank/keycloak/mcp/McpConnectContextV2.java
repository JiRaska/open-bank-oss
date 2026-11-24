// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import java.util.Base64;
import java.util.Collection;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Immutable candidate for the customer approval digest. This code has no authentication side effects. */
public final class McpConnectContextV2 {
    public static final String VERSION = "openbank-mcp-connect-context/v2";

    private static final Set<String> ALLOWED_CAPABILITIES = Set.of(
            "ACCOUNT_READ_BALANCES", "ACCOUNT_READ_TRANSACTIONS", "ACCOUNT_VIEW_DETAILS",
            "ACCOUNT_DOWNLOAD_STATEMENTS", "ACCOUNT_PROPOSE_PAYMENT");

    /** A resolved account and the specific capabilities the app selected for this account. */
    public record AccountScope(String accountId, Collection<String> capabilities) {
    }

    private final String canonical;

    private McpConnectContextV2(String canonical) {
        this.canonical = canonical;
    }

    /**
     * The caller must derive the party from the authenticated connect session, the thumbprint
     * from Keycloak's verified PAR DPoP proof, and accounts from owner-validated app selections.
     * An absent thumbprint represents PAR authenticated as a confidential hosted client, which
     * the caller must establish from authorization-server state before constructing the context.
     * This method validates the canonical representation of those facts.
     */
    public static McpConnectContextV2 of(McpConnectContext base, String partyId, String parProofThumbprint,
                                       Collection<AccountScope> accounts) {
        if (base == null || accounts == null || accounts.isEmpty()) {
            throw new IllegalArgumentException("connect context and selected accounts are required");
        }
        requireCanonicalUuid(partyId);
        if (parProofThumbprint != null) {
            requireCanonicalThumbprint(parProofThumbprint);
        }

        var selected = new TreeMap<String, String>();
        for (AccountScope account : accounts) {
            requireCanonicalUuid(account.accountId());
            var capabilities = new TreeSet<>(account.capabilities());
            if (capabilities.isEmpty() || !ALLOWED_CAPABILITIES.containsAll(capabilities)) {
                throw new IllegalArgumentException("invalid account capabilities");
            }
            if (selected.putIfAbsent(account.accountId(), String.join(",", capabilities)) != null) {
                throw new IllegalArgumentException("account selected twice");
            }
        }
        StringBuilder result = new StringBuilder(VERSION)
                .append(base.canonical().substring(McpConnectContext.VERSION.length()))
                .append("\nparty_id=").append(partyId);
        if (parProofThumbprint == null) {
            result.append("\npar_binding=confidential_client");
        } else {
            result.append("\npar_jkt=").append(parProofThumbprint);
        }
        selected.forEach((account, capabilities) -> result.append("\naccount_scope=").append(account)
                .append(':').append(capabilities));
        return new McpConnectContextV2(result.toString());
    }

    private static void requireCanonicalUuid(String value) {
        if (value == null || value.length() != 36 || !UUID.fromString(value).toString().equals(value)) {
            throw new IllegalArgumentException("expected a canonical UUID");
        }
    }

    static void requireCanonicalThumbprint(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("invalid PAR proof thumbprint");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
            throw new IllegalArgumentException("invalid PAR proof thumbprint");
        }
    }

    public String canonical() {
        return canonical;
    }

    public String payloadSha256() {
        return McpConnectContext.sha256Hex(canonical);
    }
}
