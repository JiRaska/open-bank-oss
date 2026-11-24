// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Input to the dormant ADR-0383 downstream balance-read grant. */
record McpReadDelegationRequest(String subjectToken, String resourceProof, UUID accountId, UUID connectionId, UUID hostedReadId) {
    McpReadDelegationRequest(String token, String proof, UUID account, UUID connection) {
        this(token, proof, account, connection, null);
    }
    static final String GRANT_TYPE = "urn:openbank:oauth:grant-type:mcp-read-delegation";
    static final String CLIENT_ID = "openbank-mcp-read-delegation";
    static final String REALM = "openbank-customers";
    static final String X509_AUTHENTICATOR = "client-x509";
    static final String SUBJECT_TOKEN = "subject_token";
    static final String RESOURCE_PROOF = "resource_dpop_proof";

    private static final String HOSTED_READ_ID = "hosted_read_id";
    private static final String ACCOUNT_ID = "account_id";
    private static final String CONNECTION_ID = "connection_id";
    private static final int MAX_TOKEN_LENGTH = 8192;
    private static final int MAX_PROOF_LENGTH = 4096;
    private static final Set<String> PARAMETERS =
            Set.of("grant_type", "client_id", SUBJECT_TOKEN, RESOURCE_PROOF, ACCOUNT_ID);
    private static final Set<String> BOUND_PARAMETERS =
            Set.of("grant_type", "client_id", SUBJECT_TOKEN, RESOURCE_PROOF, ACCOUNT_ID, CONNECTION_ID);
    private static final Set<String> HOSTED_PARAMETERS =
            Set.of("grant_type", "client_id", SUBJECT_TOKEN, HOSTED_READ_ID, ACCOUNT_ID, CONNECTION_ID);
    private static final Pattern COMPACT_JWS = Pattern.compile(
            "[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    /**
     * These properties come from Keycloak's authenticated client context and its configuration.
     * The token endpoint checks the configured authenticator before invoking the grant.
     */
    static boolean admitsCaller(String realm, String clientId, boolean enabled, boolean publicClient,
                                String authenticator, boolean exactSubject, boolean issuerConfigured) {
        return REALM.equals(realm) && CLIENT_ID.equals(clientId) && enabled && !publicClient
                && X509_AUTHENTICATOR.equals(authenticator) && exactSubject && issuerConfigured;
    }

    /** This parses an envelope only. Evidence verification and authorization happen separately. */
    static McpReadDelegationRequest parse(Map<String, List<String>> form) {
        if (form == null || !(form.keySet().equals(PARAMETERS) || form.keySet().equals(BOUND_PARAMETERS)
                || form.keySet().equals(HOSTED_PARAMETERS))
                || !GRANT_TYPE.equals(one(form, "grant_type"))
                || !CLIENT_ID.equals(one(form, "client_id"))) {
            throw invalid();
        }
        String subjectToken = one(form, SUBJECT_TOKEN);
        boolean hosted = form.keySet().equals(HOSTED_PARAMETERS);
        String resourceProof = hosted ? null : one(form, RESOURCE_PROOF);
        if (!compactJws(subjectToken, MAX_TOKEN_LENGTH) || (!hosted && !compactJws(resourceProof, MAX_PROOF_LENGTH))) {
            throw invalid();
        }
        UUID accountId = canonicalUuid(one(form, ACCOUNT_ID));
        UUID connectionId = form.containsKey(CONNECTION_ID) ? canonicalUuid(one(form, CONNECTION_ID)) : null;
        UUID hostedReadId = hosted ? canonicalUuid(one(form, HOSTED_READ_ID)) : null;
        return new McpReadDelegationRequest(subjectToken, resourceProof, accountId, connectionId, hostedReadId);
    }

    static UUID canonicalUuid(Object value) {
        if (!(value instanceof String text) || text.length() != 36) {
            throw invalid();
        }
        UUID id = UUID.fromString(text);
        if (!id.toString().equals(text)) {
            throw invalid();
        }
        return id;
    }

    private static String one(Map<String, List<String>> form, String name) {
        List<String> values = form.get(name);
        if (values == null || values.size() != 1 || values.getFirst() == null) {
            throw invalid();
        }
        return values.getFirst();
    }

    private static boolean compactJws(String text, int maxLength) {
        return text.length() <= maxLength && COMPACT_JWS.matcher(text).matches();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid delegation request");
    }

    @Override
    public String toString() {
        return "McpReadDelegationRequest[redacted]";
    }
}
