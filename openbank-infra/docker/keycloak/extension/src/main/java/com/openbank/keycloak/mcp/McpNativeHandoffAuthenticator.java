// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import com.google.zxing.WriterException;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.Instant;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.Urls;
import org.keycloak.sessions.AuthenticationSessionModel;

/** Resolves the user only from a one-use bank receipt, before the usual Connect V2 SCA step. */
final class McpNativeHandoffAuthenticator implements Authenticator {
    private static final Logger LOG = Logger.getLogger(McpNativeHandoffAuthenticator.class);

    private enum InitialStage { BINDING, PAGE, AUTHORITY }

    private enum PollRejection {
        SESSION, AUTHORITY_OR_PAGE, RECEIPT_UNAVAILABLE, RECEIPT_CONTEXT, USER_LOOKUP,
        USER_NOT_FOUND, USER_DISABLED, REQUIRED_ACTIONS, PARTY_MISMATCH, BROWSER_USER_MISMATCH
    }

    @Override
    public void authenticate(AuthenticationFlowContext ctx) {
        var auth = ctx.getAuthenticationSession();
        if (auth.getAuthNote(McpNotes.BURNED) != null) {
            refuse(ctx);
            return;
        }
        InitialStage stage = InitialStage.BINDING;
        try {
            var factory = factory(ctx.getSession());
            var binding = binding(ctx, auth, factory);
            stage = InitialStage.PAGE;
            if (auth.getAuthNote(McpNotes.NATIVE_HANDOFF) != null) {
                ctx.challenge(page(ctx, null));
                return;
            }
            var issued = McpNativeHandoff.issue();
            var page = page(ctx, issued);
            stage = InitialStage.AUTHORITY;
            factory.tokenAuthority().issueNativeHandoff(issued.tokenSha256(), binding);
            stage = InitialStage.PAGE;
            auth.setAuthNote(McpNotes.NATIVE_HANDOFF, issued.tokenSha256());
            ctx.challenge(page);
        } catch (IOException | WriterException | RuntimeException e) {
            LOG.warnf("MCP native handoff rejected at stage=%s", stage);
            refuse(ctx);
        }
    }

    @Override
    public void action(AuthenticationFlowContext ctx) {
        var auth = ctx.getAuthenticationSession();
        var form = ctx.getHttpRequest().getDecodedFormParameters();
        String handoffDigest = auth.getAuthNote(McpNotes.NATIVE_HANDOFF);
        if (auth.getAuthNote(McpNotes.BURNED) != null || handoffDigest == null
                || (form != null && form.containsKey("cancel"))) {
            refusePoll(ctx, PollRejection.SESSION);
            return;
        }
        var issued = McpNativeHandoff.recover(form == null ? null : form.getFirst("handoff"), handoffDigest);
        try {
            long now = System.currentTimeMillis();
            String last = auth.getAuthNote(McpNotes.LAST_POLL);
            if (last != null && McpConnectPolicy.pollTooSoon(Long.parseLong(last), now)) {
                ctx.challenge(page(ctx, issued));
                return;
            }
            auth.setAuthNote(McpNotes.LAST_POLL, Long.toString(now));
            var factory = factory(ctx.getSession());
            var binding = binding(ctx, auth, factory);
            poll(ctx, auth, factory.tokenAuthority(), binding, handoffDigest, issued);
        } catch (IOException | WriterException | RuntimeException e) {
            refusePoll(ctx, PollRejection.AUTHORITY_OR_PAGE);
        }
    }

    private static void poll(AuthenticationFlowContext ctx, AuthenticationSessionModel auth,
                             McpConnectAuthorityClient authority, McpNativeHandoff.Binding binding,
                             String handoffDigest, McpNativeHandoff.Issued issued) throws IOException, WriterException {
        String party = authority.claimedNativeParty(handoffDigest, binding);
        if (party == null) {
            ctx.challenge(page(ctx, issued));
            return;
        }
        var base = new McpConnectContext(binding.clientId(), McpConnectContext.redirectHost(binding.redirectUri()),
                binding.resource(), String.join(" ", binding.scopes()), binding.requestRef(),
                binding.expiresAtEpochSecond());
        var request = new McpConnectAuthorityClient.Request(base, party, binding.parJkt());
        var accounts = authority.selectedAccounts(request);
        if (accounts.isEmpty()) {
            ctx.challenge(page(ctx, null));
            return;
        }
        String digest = McpConnectContextV2.of(base, party, binding.parJkt(), accounts).payloadSha256();
        authority.attestNativeSelection(handoffDigest, binding, digest);
        McpConnectAuthorityClient.NativeReceipt receipt;
        try {
            receipt = authority.consumeNativeHandoff(handoffDigest, binding);
        } catch (IOException | RuntimeException e) {
            refusePoll(ctx, PollRejection.RECEIPT_UNAVAILABLE);
            return;
        }
        if (receipt == null) {
            ctx.challenge(page(ctx, null));
            return;
        }
        resolveUser(ctx, auth, party, digest, receipt);
    }

    private static void resolveUser(AuthenticationFlowContext ctx, AuthenticationSessionModel auth, String party,
                                    String digest, McpConnectAuthorityClient.NativeReceipt receipt) {
        var existing = ctx.getUser();
        UserModel user;
        PollRejection rejection;
        try {
            user = ctx.getSession().users().getUserById(ctx.getRealm(), receipt.keycloakSubject());
            rejection = receiptRejection(auth, existing, user, party, digest, receipt);
        } catch (RuntimeException e) {
            refusePoll(ctx, PollRejection.USER_LOOKUP);
            return;
        }
        if (rejection != null) {
            refusePoll(ctx, rejection);
            return;
        }
        ctx.setUser(user);
        auth.removeAuthNote(McpNotes.NATIVE_HANDOFF);
        auth.removeAuthNote(McpNotes.LAST_POLL);
        auth.setAuthNote(McpNotes.PARTY_ID, party);
        auth.setAuthNote(McpNotes.NATIVE_PROOF, digest);
        ctx.success();
    }

    private static PollRejection receiptRejection(AuthenticationSessionModel auth, UserModel existing, UserModel user,
                                                   String party, String digest,
                                                   McpConnectAuthorityClient.NativeReceipt receipt) {
        if (!party.equals(receipt.partyId()) || !digest.equals(receipt.contextSha256())) {
            return PollRejection.RECEIPT_CONTEXT;
        }
        if (user == null) return PollRejection.USER_NOT_FOUND;
        if (!user.isEnabled()) return PollRejection.USER_DISABLED;
        if (McpNativeEnrollment.blocks(auth, user, true)) return PollRejection.REQUIRED_ACTIONS;
        if (!McpWithheldCodeAuthenticator.partyOf(user).map(party::equals).orElse(false)) {
            return PollRejection.PARTY_MISMATCH;
        }
        if (existing != null && !existing.getId().equals(user.getId())) return PollRejection.BROWSER_USER_MISMATCH;
        return null;
    }

    private static void refusePoll(AuthenticationFlowContext ctx, PollRejection rejection) {
        LOG.warnf("MCP native handoff rejected at stage=POLL reason=%s", rejection);
        refuse(ctx);
    }

    private static McpNativeHandoff.Binding binding(AuthenticationFlowContext ctx, AuthenticationSessionModel auth,
                                                     McpWithheldCodeAuthenticatorFactory factory) {
        return McpNativeHandoff.binding(auth, Urls.realmIssuer(ctx.getUriInfo().getBaseUri(), ctx.getRealm().getName()),
                Instant.now().getEpochSecond(), factory.connectLifespanSeconds());
    }

    private static McpWithheldCodeAuthenticatorFactory factory(KeycloakSession session) {
        var factory = session.getKeycloakSessionFactory().getProviderFactory(Authenticator.class,
                McpWithheldCodeAuthenticatorFactory.PROVIDER_ID);
        if (factory instanceof McpWithheldCodeAuthenticatorFactory mcp) {
            return mcp;
        }
        throw new IllegalStateException("no MCP Connect authority");
    }

    private static void refuse(AuthenticationFlowContext ctx) {
        ctx.getAuthenticationSession().setAuthNote(McpNotes.BURNED, "1");
        ctx.failure(AuthenticationFlowError.ACCESS_DENIED);
    }

    private static Response page(AuthenticationFlowContext ctx, McpNativeHandoff.Issued issued) throws WriterException, IOException {
        String action = ctx.getActionUrl(ctx.generateAccessCode()).toString();
        var registeredClient = ctx.getAuthenticationSession().getClient();
        String clientName = registeredClient.getName();
        return McpNativeHandoffPage.render(action,
                clientName == null || clientName.isBlank() ? registeredClient.getClientId() : clientName, issued);
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }
}
