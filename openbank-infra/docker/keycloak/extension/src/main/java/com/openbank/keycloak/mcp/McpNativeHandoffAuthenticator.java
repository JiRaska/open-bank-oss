// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
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
    @Override
    public void authenticate(AuthenticationFlowContext ctx) {
        var auth = ctx.getAuthenticationSession();
        if (auth.getAuthNote(McpNotes.BURNED) != null) {
            refuse(ctx);
            return;
        }
        try {
            var factory = factory(ctx.getSession());
            var binding = binding(ctx, auth, factory);
            if (auth.getAuthNote(McpNotes.NATIVE_HANDOFF) != null) {
                ctx.challenge(page(ctx, null));
                return;
            }
            var issued = McpNativeHandoff.issue();
            var page = page(ctx, issued);
            factory.tokenAuthority().issueNativeHandoff(issued.tokenSha256(), binding);
            auth.setAuthNote(McpNotes.NATIVE_HANDOFF, issued.tokenSha256());
            ctx.challenge(page);
        } catch (IOException | WriterException | RuntimeException e) {
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
            refuse(ctx);
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
            refuse(ctx);
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
        var receipt = authority.consumeNativeHandoff(handoffDigest, binding);
        if (receipt == null) {
            ctx.challenge(page(ctx, null));
            return;
        }
        resolveUser(ctx, auth, party, digest, receipt);
    }

    private static void resolveUser(AuthenticationFlowContext ctx, AuthenticationSessionModel auth, String party,
                                    String digest, McpConnectAuthorityClient.NativeReceipt receipt) {
        var existing = ctx.getUser();
        var user = ctx.getSession().users().getUserById(ctx.getRealm(), receipt.keycloakSubject());
        if (!party.equals(receipt.partyId()) || !digest.equals(receipt.contextSha256()) || user == null
                || !user.isEnabled() || user.getRequiredActionsStream().findAny().isPresent()
                || !McpWithheldCodeAuthenticator.partyOf(user).map(party::equals).orElse(false)
                || (existing != null && !existing.getId().equals(user.getId()))) {
            refuse(ctx);
            return;
        }
        ctx.setUser(user);
        auth.removeAuthNote(McpNotes.NATIVE_HANDOFF);
        auth.removeAuthNote(McpNotes.LAST_POLL);
        auth.setAuthNote(McpNotes.PARTY_ID, party);
        auth.setAuthNote(McpNotes.NATIVE_PROOF, digest);
        ctx.success();
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
        String action = McpWithheldCodeAuthenticator.escape(ctx.getActionUrl(ctx.generateAccessCode()).toString());
        String client = McpWithheldCodeAuthenticator.escape(ctx.getAuthenticationSession().getClient().getClientId());
        String connect = "<p>If you have not opened the app yet, restart the connection in your assistant.</p>";
        String handoffInput = "";
        String scriptNonce = UUID.randomUUID().toString();
        if (issued != null) {
            String link = issued.appLink();
            handoffInput = "<input type=\"hidden\" name=\"handoff\" value=\"" + issued.formValue() + "\">";
            var out = new ByteArrayOutputStream();
            var matrix = new MultiFormatWriter().encode(link, BarcodeFormat.QR_CODE, 240, 240);
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            String image = Base64.getEncoder().encodeToString(out.toByteArray());
            connect = "<p><a href=\"" + McpWithheldCodeAuthenticator.escape(link)
                    + "\">Open OpenBank</a></p><img alt=\"Scan to open OpenBank\" src=\"data:image/png;base64,"
                    + image + "\">";
        }
        String html = """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Continue in OpenBank</title></head><body><main>
                <h1>Continue in OpenBank</h1><p>Assistant: %s</p>
                <p>Open the bank app or scan the code. Review the accounts and verify your passkey.</p>%s
                <form id="native-mcp-check" method="post" action="%s">%s
                <button type="submit" name="check" value="1">Continue</button>
                <button type="submit" name="cancel" value="1">Cancel</button></form>
                <script nonce="%s">setTimeout(function(){
                document.getElementById('native-mcp-check').requestSubmit();
                },3000);</script>
                </main></body></html>
                """.formatted(client, connect, action, handoffInput, scriptNonce);
        return Response.ok(html, MediaType.TEXT_HTML_TYPE.withCharset("utf-8"))
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy",
                        "default-src 'none'; img-src data:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'; "
                                + "script-src 'nonce-" + scriptNonce + "'")
                .build();
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
