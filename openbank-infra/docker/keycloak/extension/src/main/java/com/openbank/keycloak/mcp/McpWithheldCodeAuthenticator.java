// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.keycloak.mcp;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.WebAuthnCredentialModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-0385 D3 — the withheld-code authenticator. It runs after the fresh passkey in
 * {@code openbank-mcp-fresh-passkey} and lets the flow succeed (so Keycloak issues the code) only
 * after sca-service consumed the context-linked {@code MCP_CLIENT_CONNECT} challenge.
 *
 * <ol>
 *   <li>The challenge id lives only in an auth-session note; the browser never supplies it.</li>
 *   <li>{@code approvalRequestId} and {@code payloadSha256} are recomputed at consume from the
 *       request facts the guard executor stored at the authorization request.</li>
 *   <li>The consume carries {@code expectedPartyId} = the party of the user who asserted the
 *       passkey in THIS session.</li>
 *   <li>sca-service's single-use CAS decides the race; a cluster-wide single-use claim keeps two
 *       concurrent polls of one session from both reaching it. Any outcome other than "not yet
 *       approved" burns the session: no code, and a fresh connect is required.</li>
 *   <li>The session is good for at most 300 s from the authorization request.</li>
 *   <li>The poll endpoint (this authenticator's action) answers at most once per 2 s and 150
 *       times per session.</li>
 *   <li>D2.3: the passkey asserted in this session must be at least 72 h old.</li>
 * </ol>
 */
public class McpWithheldCodeAuthenticator implements Authenticator {
    private static final Logger LOG = Logger.getLogger(McpWithheldCodeAuthenticator.class);
    private static final String PARTY_ATTRIBUTE = "party_id";
    private static final String PASSKEY_TYPE = WebAuthnCredentialModel.TYPE_PASSWORDLESS;
    private static final long CLAIM_SECONDS = 30;

    private final ScaClient.Settings settings;
    private final long lifespanSeconds;

    McpWithheldCodeAuthenticator(ScaClient.Settings settings, long lifespanSeconds) {
        this.settings = settings;
        this.lifespanSeconds = McpConnectPolicy.sessionLifespan(lifespanSeconds);
    }

    @Override
    public void authenticate(AuthenticationFlowContext ctx) {
        AuthenticationSessionModel auth = ctx.getAuthenticationSession();
        if (auth.getAuthNote(McpNotes.BURNED) != null) {
            refuse(ctx, AuthenticationFlowError.ACCESS_DENIED, "session already used");
            return;
        }
        if (!McpClientGuardExecutor.isMcp(auth.getClient()) || !settings.complete()) {
            refuse(ctx, AuthenticationFlowError.ACCESS_DENIED, "not an MCP client, or sca-service not configured");
            return;
        }
        long started = startedAt(auth);
        if (McpConnectPolicy.sessionExpired(started, now(), lifespanSeconds)) {
            refuse(ctx, AuthenticationFlowError.EXPIRED_CODE, "connect session expired");
            return;
        }
        UserModel user = ctx.getUser();
        Optional<String> party = partyOf(user);
        if (party.isEmpty() || user.getRequiredActionsStream().findAny().isPresent()) {
            // A pending required action would be served AFTER the consume; refuse before it instead.
            refuse(ctx, AuthenticationFlowError.INVALID_USER, "no party, or required actions pending");
            return;
        }
        Optional<CredentialModel> passkey = assertedPasskey(ctx, user);
        if (passkey.isEmpty()) {
            refuse(ctx, AuthenticationFlowError.INVALID_CREDENTIALS, "no passkey asserted in this request");
            return;
        }
        if (!McpConnectPolicy.passkeyOldEnough(passkey.get().getCreatedDate(), System.currentTimeMillis())) {
            refuse(ctx, AuthenticationFlowError.INVALID_CREDENTIALS, "passkey younger than 72 h");
            return;
        }
        McpConnectContext context;
        try {
            context = contextOf(auth, started);
        } catch (IllegalArgumentException e) {
            refuse(ctx, AuthenticationFlowError.INTERNAL_ERROR, "connect context incomplete");
            return;
        }
        String challengeId;
        try {
            challengeId = new ScaClient(settings).initiate(party.get(),
                    McpConnectContext.approvalRequestId(auth.getAuthNote(McpNotes.REQUEST_URI)), context.payloadSha256());
        } catch (IOException | RuntimeException e) {
            LOG.warnf("MCP connect: SCA initiate failed: %s", e.getMessage());
            refuse(ctx, AuthenticationFlowError.INTERNAL_ERROR, "SCA initiate failed");
            return;
        }
        auth.setAuthNote(McpNotes.CHALLENGE_ID, challengeId);
        auth.setAuthNote(McpNotes.PARTY_ID, party.get());
        auth.setAuthNote(McpNotes.PASSKEY_CREDENTIAL, passkey.get().getId());
        ctx.challenge(waitingPage(ctx, context, 200, null));
    }

    @Override
    public void action(AuthenticationFlowContext ctx) {
        AuthenticationSessionModel auth = ctx.getAuthenticationSession();
        if (auth.getAuthNote(McpNotes.BURNED) != null) {
            refuse(ctx, AuthenticationFlowError.ACCESS_DENIED, "session already used");
            return;
        }
        MultivaluedMap<String, String> form = ctx.getHttpRequest().getDecodedFormParameters();
        if (form != null && form.containsKey("cancel")) {
            burn(ctx, AuthenticationFlowError.ACCESS_DENIED, "cancelled by the customer");
            return;
        }
        String challengeId = auth.getAuthNote(McpNotes.CHALLENGE_ID);
        String party = auth.getAuthNote(McpNotes.PARTY_ID);
        long started = startedAt(auth);
        if (challengeId == null || party == null || !partyOf(ctx.getUser()).map(party::equals).orElse(false)) {
            burn(ctx, AuthenticationFlowError.INVALID_USER, "no challenge, or the session's party changed");
            return;
        }
        if (McpConnectPolicy.sessionExpired(started, now(), lifespanSeconds)) {
            burn(ctx, AuthenticationFlowError.EXPIRED_CODE, "connect session expired");
            return;
        }
        int polls = parseInt(auth.getAuthNote(McpNotes.POLLS)) + 1;
        if (polls > McpConnectPolicy.MAX_POLLS) {
            burn(ctx, AuthenticationFlowError.ACCESS_DENIED, "poll budget exhausted");
            return;
        }
        McpConnectContext context;
        try {
            context = contextOf(auth, started);
        } catch (IllegalArgumentException e) {
            burn(ctx, AuthenticationFlowError.INTERNAL_ERROR, "connect context incomplete");
            return;
        }
        long nowMillis = System.currentTimeMillis();
        if (McpConnectPolicy.pollTooSoon(parseLong(auth.getAuthNote(McpNotes.LAST_POLL)), nowMillis)) {
            ctx.challenge(waitingPage(ctx, context, 429, "Too many checks - wait a moment."));
            return;
        }
        auth.setAuthNote(McpNotes.POLLS, Integer.toString(polls));
        auth.setAuthNote(McpNotes.LAST_POLL, Long.toString(nowMillis));

        // D3.5: one consume attempt in flight per challenge, cluster-wide. The loser of a race
        // just keeps waiting; sca-service's CAS is the second, authoritative line.
        SingleUseObjectProvider claims = ctx.getSession().singleUseObjects();
        String claim = "openbank.mcp.consume." + challengeId;
        if (!claims.putIfAbsent(claim, CLAIM_SECONDS)) {
            ctx.challenge(waitingPage(ctx, context, 200, null));
            return;
        }
        ScaClient.Outcome outcome = new ScaClient(settings).consume(challengeId, party,
                McpConnectContext.approvalRequestId(auth.getAuthNote(McpNotes.REQUEST_URI)), context.payloadSha256());
        if (outcome == ScaClient.Outcome.PENDING) {
            claims.remove(claim);
            ctx.challenge(waitingPage(ctx, context, 200, null));
            return;
        }
        // From here the challenge is spent or unusable: this session never consumes again.
        auth.setAuthNote(McpNotes.BURNED, "1");
        if (outcome != ScaClient.Outcome.CONSUMED) {
            refuse(ctx, AuthenticationFlowError.ACCESS_DENIED, "SCA consume refused");
            return;
        }
        // D3.4: anything that fails after the consume fails closed — no code.
        UserModel user = ctx.getUser();
        if (!user.isEnabled() || user.getRequiredActionsStream().findAny().isPresent()
                || McpConnectPolicy.sessionExpired(started, now(), lifespanSeconds)) {
            refuse(ctx, AuthenticationFlowError.ACCESS_DENIED, "post-consume check failed");
            return;
        }
        LOG.infof("MCP connect approved: client=%s challenge=%s", auth.getClient().getClientId(), challengeId);
        ctx.success();
    }

    private static void burn(AuthenticationFlowContext ctx, AuthenticationFlowError error, String why) {
        ctx.getAuthenticationSession().setAuthNote(McpNotes.BURNED, "1");
        refuse(ctx, error, why);
    }

    private static void refuse(AuthenticationFlowContext ctx, AuthenticationFlowError error, String why) {
        ClientModel client = ctx.getAuthenticationSession().getClient();
        LOG.warnf("MCP connect refused: client=%s reason=%s", client == null ? "?" : client.getClientId(), why);
        ctx.getEvent().detail("reason", "openbank_mcp_" + why.replace(' ', '_'));
        ctx.failure(error);
    }

    private McpConnectContext contextOf(AuthenticationSessionModel auth, long started) {
        return McpConnectContext.of(auth.getClient().getClientId(), auth.getAuthNote(McpNotes.REDIRECT_URI),
                auth.getAuthNote(McpNotes.RESOURCE), auth.getAuthNote(McpNotes.SCOPE),
                auth.getAuthNote(McpNotes.REQUEST_URI), started + lifespanSeconds);
    }

    private static Optional<String> partyOf(UserModel user) {
        if (user == null) {
            return Optional.empty();
        }
        String party = user.getFirstAttribute(PARTY_ATTRIBUTE);
        try {
            return party == null ? Optional.empty() : Optional.of(UUID.fromString(party).toString());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The passkey asserted in THIS request: the WebAuthn step that precedes this one posts its
     * {@code credentialId} and the flow reaches this authenticator in the same request. A request
     * without one (no fresh assertion) finds nothing and is refused.
     */
    private static Optional<CredentialModel> assertedPasskey(AuthenticationFlowContext ctx, UserModel user) {
        MultivaluedMap<String, String> form = ctx.getHttpRequest().getDecodedFormParameters();
        String posted = form == null ? null : form.getFirst("credentialId");
        if (posted == null || posted.isBlank()) {
            return Optional.empty();
        }
        byte[] wanted;
        try {
            wanted = Base64.getUrlDecoder().decode(posted);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return user.credentialManager().getStoredCredentialsByTypeStream(PASSKEY_TYPE)
                .filter(c -> {
                    try {
                        String stored = WebAuthnCredentialModel.createFromCredentialModel(c)
                                .getWebAuthnCredentialData().getCredentialId();
                        return stored != null && Arrays.equals(Base64.getDecoder().decode(stored), wanted);
                    } catch (RuntimeException e) {
                        return false;
                    }
                })
                .findFirst();
    }

    private static Response waitingPage(AuthenticationFlowContext ctx, McpConnectContext context, int status, String notice) {
        String action = ctx.getActionUrl(ctx.generateAccessCode()).toString();
        String name = Optional.ofNullable(ctx.getAuthenticationSession().getClient().getName())
                .orElse(context.clientId());
        String html = """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Approve in your bank app</title></head><body>
                <main id="mcp-connect-wait">
                <h1>Approve this connection in your bank app</h1>
                <p><strong>Approve only if you just pressed Connect in your own assistant.</strong></p>
                <dl>
                <dt>Assistant</dt><dd id="mcp-client">%s</dd>
                <dt>Returns to</dt><dd id="mcp-redirect-host">%s</dd>
                <dt>Access</dt><dd id="mcp-resource">%s</dd>
                <dt>Can do</dt><dd>Reads your data and can propose payments; it can never pay by itself.</dd>
                <dt>Expires</dt><dd id="mcp-expires">%s</dd>
                </dl>
                %s
                <form id="mcp-connect-poll" method="post" action="%s">
                <button type="submit" name="check" value="1">I have approved it</button>
                <button type="submit" name="cancel" value="1">Cancel</button>
                </form>
                <script>setTimeout(function(){var f=document.getElementById('mcp-connect-poll');\
                var b=document.createElement('input');b.type='hidden';b.name='check';b.value='1';\
                f.appendChild(b);f.submit();},3000);</script>
                </main></body></html>
                """.formatted(escape(name), escape(context.redirectHost()), escape(context.resource()),
                escape(Instant.ofEpochSecond(context.expiresAt()).toString()),
                notice == null ? "" : "<p id=\"mcp-notice\">" + escape(notice) + "</p>", escape(action));
        return Response.status(status).type(MediaType.TEXT_HTML_TYPE.withCharset("utf-8")).entity(html)
                .header("Cache-Control", "no-store").build();
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static long startedAt(AuthenticationSessionModel auth) {
        return parseLong(auth.getAuthNote(McpNotes.STARTED_AT));
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseInt(String s) {
        return (int) Math.min(Integer.MAX_VALUE, parseLong(s));
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // none: an MCP authorize never enrols or recovers anything (ADR-0385 D2.3)
    }

    @Override
    public void close() {
        // stateless
    }
}
