// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.keycloak.mcp;

/**
 * Auth-session notes. Server-side only: the browser never supplies any of them (ADR-0385 D3.1).
 * The executor writes the request facts at the authorization request; the authenticator owns the
 * rest.
 */
final class McpNotes {
    static final String STARTED_AT = "openbank.mcp.started_at";
    static final String REQUEST_URI = "openbank.mcp.request_uri";
    static final String REDIRECT_URI = "openbank.mcp.redirect_uri";
    static final String SCOPE = "openbank.mcp.scope";
    static final String RESOURCE = "openbank.mcp.resource";
    static final String CHALLENGE_ID = "openbank.mcp.challenge_id";
    static final String PARTY_ID = "openbank.mcp.party_id";
    static final String PASSKEY_CREDENTIAL = "openbank.mcp.passkey_credential";
    static final String LAST_POLL = "openbank.mcp.last_poll";
    static final String POLLS = "openbank.mcp.polls";
    /** Set once a consume was attempted to completion: any later poll in this session fails. */
    static final String BURNED = "openbank.mcp.burned";

    private McpNotes() {
    }
}
