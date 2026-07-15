// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import com.fasterxml.jackson.databind.ObjectMapper
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionsAuthenticatorOutputs
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.util.Base64UrlUtil
import com.webauthn4j.verifier.exception.VerificationException
import io.quarkus.logging.Log
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.security.SecureRandom
import java.util.Base64

/**
 * WebAuthn Relying Party (ADR-0066 F2, variant B1): the edge verifies passkey registration and
 * authentication ceremonies itself (webauthn4j) rather than delegating to Keycloak's own WebAuthn
 * support, then mints a real session via Keycloak token-exchange ([WebAuthnKeycloakClient]).
 *
 * A SEPARATE, un-annotated resource class — same reason as
 * [com.openbank.customeredge.infrastructure.rest.OnboardingResource]'s `startOnboarding`: a
 * class-level `@RolesAllowed` (or being on a class Quarkus's proactive OIDC filter otherwise
 * guards) pre-empts method-level `@PermitAll` with a bare 401 before this code runs. That matters
 * doubly here: [registerBegin]/[registerComplete]'s bearer token is an [EnrollmentTicketService]
 * ticket, not a Keycloak-issued JWT — the OIDC filter would reject it as malformed before this
 * class ever saw it. [authBegin]/[authComplete] need no token at all (obtaining one is the point).
 *
 * KNOWN GAP (tracked, not silently swept under the rug): a party that onboarded via the F1 hosted
 * Keycloak flow (`ASWebAuthenticationSession` — the default today) has a passkey registered with
 * *Keycloak's own* WebAuthn RP, not this one. This edge RP has an entirely separate credential
 * store, so [authBegin]/[authComplete] only work for a device that has previously completed
 * [registerBegin]/[registerComplete] against THIS RP. Today that only happens via the native F2
 * onboarding branch (`AppConfig.useNativePasskey`); there is no "register a native edge passkey"
 * flow yet for an already-onboarded F1 user. Out of scope here — see the tracking issue.
 */
@Path("/customer/v1/webauthn")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class WebAuthnResource(
    private val ticketService: EnrollmentTicketService,
    private val challengeStore: ChallengeStore,
    private val credentialStore: WebAuthnStore,
    private val keycloakClient: WebAuthnKeycloakClient,
    private val jsonMapper: ObjectMapper,
) {

    @ConfigProperty(name = "openbank.webauthn.rp-id", defaultValue = "open-bank.tech")
    lateinit var rpId: String

    @ConfigProperty(name = "openbank.webauthn.origin", defaultValue = "https://open-bank.tech")
    lateinit var origin: String

    private val objectConverter = ObjectConverter()
    private val attestedCredentialDataConverter = AttestedCredentialDataConverter(objectConverter)
    private val webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter)
    private val random = SecureRandom()

    // ── Registration (device enrollment during native onboarding, F2) ──────────────────────

    @POST
    @Path("/register/begin")
    @PermitAll
    @Blocking
    fun registerBegin(@HeaderParam("Authorization") authorization: String?): Response {
        val partyId = authorizedPartyId(authorization)
            ?: return unauthorized("Invalid or expired enrollment ticket")

        val challenge = randomBytes()
        challengeStore.save(Base64UrlUtil.encodeToString(challenge), PURPOSE_REGISTRATION)

        val userId = randomBytes(USER_ID_BYTES)
        val body = RegistrationChallengeDto(
            challenge = Base64UrlUtil.encodeToString(challenge),
            rpId = rpId,
            userId = Base64UrlUtil.encodeToString(userId),
            userName = partyId,
            displayName = "OpenBank",
        )
        return Response.ok(body).build()
    }

    @POST
    @Path("/register/complete")
    @PermitAll
    @Blocking
    fun registerComplete(
        @HeaderParam("Authorization") authorization: String?,
        request: RegistrationCompleteRequestDto,
    ): Response {
        val partyId = authorizedPartyId(authorization)
            ?: return unauthorized("Invalid or expired enrollment ticket")

        val clientDataJsonBytes = Base64UrlUtil.decode(request.clientDataJson)
        val challenge = extractChallenge(clientDataJsonBytes)
            ?: return badRequest("Malformed clientDataJSON")
        if (challengeStore.consume(challenge) != PURPOSE_REGISTRATION) {
            return badRequest("Unknown or expired registration challenge")
        }

        val registrationData = try {
            webAuthnManager.verify(
                RegistrationRequest(Base64UrlUtil.decode(request.attestationObject), clientDataJsonBytes),
                RegistrationParameters(
                    ServerProperty(Origin.create(origin), rpId, DefaultChallenge(Base64UrlUtil.decode(challenge))),
                    null, // pubKeyCredParams — not enforced; the platform authenticator dictates the algorithm
                    true, // userVerificationRequired — Face ID
                    true, // userPresenceRequired
                ),
            )
        } catch (e: VerificationException) {
            Log.warnf(e, "registerComplete: attestation verification failed for party=%s", partyId)
            return badRequest("Attestation verification failed")
        }

        val authenticatorData = registrationData.attestationObject?.authenticatorData
            ?: return badRequest("Attestation missing authenticator data")
        val attestedCredentialData = authenticatorData.attestedCredentialData
            ?: return badRequest("Attestation missing credential data")
        val credentialId = Base64UrlUtil.encodeToString(attestedCredentialData.credentialId)

        // party.id == Keycloak sub (ADR-0069 invariant): use the party's own edge-visible identity
        // (partyId, the enrollment ticket's subject) to derive a synthetic username/email, since
        // this is a brand-new party with no email known to the edge at this layer. The realm's
        // party-id protocol mapper (openbank-app client) then carries party_id as a claim on any
        // future token regardless of how this user later authenticates.
        val syntheticEmail = "party+$partyId@openbank.internal"
        val keycloakUserId = keycloakClient.ensureUser(syntheticEmail, partyId, displayName = "OpenBank Customer")

        credentialStore.save(
            RegisteredCredential(
                credentialId = credentialId,
                partyId = partyId,
                keycloakUserId = keycloakUserId,
                attestedCredentialDataB64 = Base64.getEncoder().encodeToString(
                    attestedCredentialDataConverter.convert(attestedCredentialData),
                ),
                signCount = authenticatorData.signCount,
            ),
        )
        return Response.noContent().build()
    }

    // ── Authentication (returning-user login, no browser) ───────────────────────────────────

    @POST
    @Path("/auth/begin")
    @PermitAll
    @Blocking
    fun authBegin(): Response {
        val challenge = randomBytes()
        challengeStore.save(Base64UrlUtil.encodeToString(challenge), PURPOSE_AUTHENTICATION)
        // allowCredentials deliberately empty: the platform authenticator resolves ANY resident
        // (discoverable) credential for rpId, matching the app's ASAuthorizationController usage
        // (no allowedCredentials list — see OAuthLauncher.ios.kt launchPasskeyAuth).
        val body = AuthenticationChallengeDto(challenge = Base64UrlUtil.encodeToString(challenge), rpId = rpId)
        return Response.ok(body).build()
    }

    @POST
    @Path("/auth/complete")
    @PermitAll
    @Blocking
    fun authComplete(request: AuthCompleteRequestDto): Response {
        val clientDataJsonBytes = Base64UrlUtil.decode(request.clientDataJson)
        val challenge = extractChallenge(clientDataJsonBytes)
            ?: return badRequest("Malformed clientDataJSON")
        if (challengeStore.consume(challenge) != PURPOSE_AUTHENTICATION) {
            return badRequest("Unknown or expired authentication challenge")
        }

        val credentialIdB64 = request.credentialId
        val stored = credentialStore.find(credentialIdB64)
            ?: return unauthorized("Unknown credential")

        val attestedCredentialData = attestedCredentialDataConverter.convert(
            Base64.getDecoder().decode(stored.attestedCredentialDataB64),
        )
        // CredentialRecord (not the deprecated Authenticator-based constructor/overload): the
        // extra fields webauthn4j added alongside it (clientData, clientExtensions, transports,
        // uvInitialized/backupEligible/backupState) are all nullable and unused here — this
        // store only ever persisted the attested credential data + counter.
        val credentialRecord = CredentialRecordImpl(
            NoneAttestationStatement(),
            null, // uvInitialized
            null, // backupEligible
            null, // backupState
            stored.signCount,
            attestedCredentialData,
            AuthenticationExtensionsAuthenticatorOutputs(),
            null, // clientData
            null, // clientExtensions
            null, // transports
        )

        // Result unused: [webAuthnManager] throws VerificationException on any failure — this
        // call's only job is to run the assertion checks; success is verifying it doesn't throw.
        try {
            webAuthnManager.verify(
                AuthenticationRequest(
                    Base64UrlUtil.decode(request.credentialId),
                    Base64UrlUtil.decode(request.userHandle),
                    Base64UrlUtil.decode(request.authenticatorData),
                    clientDataJsonBytes,
                    Base64UrlUtil.decode(request.signature),
                ),
                AuthenticationParameters(
                    ServerProperty(Origin.create(origin), rpId, DefaultChallenge(Base64UrlUtil.decode(challenge))),
                    credentialRecord,
                    null, // allowCredentials — not enforced; the lookup above already pinned the exact credential
                    true, // userVerificationRequired — Face ID
                    true, // userPresenceRequired
                ),
            )
        } catch (e: VerificationException) {
            Log.warnf(e, "authComplete: assertion verification failed for credential=%s", credentialIdB64)
            return unauthorized("Assertion verification failed")
        }

        // Persist the post-verify counter (webauthn4j clone-detection: a signature whose
        // embedded counter does not exceed the stored one throws above, before reaching here —
        // this line only ever advances it).
        credentialStore.save(stored.copy(signCount = credentialRecord.counter))
        Log.debugf(
            "authComplete: verified assertion for credential=%s, newCounter=%d",
            credentialIdB64,
            credentialRecord.counter,
        )

        val (accessToken, refreshToken) = keycloakClient.impersonate(stored.keycloakUserId)
        return Response.ok(TokenPairDto(accessToken, refreshToken)).build()
    }

    // ── Internals ─────────────────────────────────────────────────────────────────────────

    /** Reads the `Authorization: Bearer <ticket>` header and resolves it to a partyId, or null. */
    private fun authorizedPartyId(authorization: String?): String? {
        val ticket = authorization?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return ticketService.verify(ticket)
    }

    private fun extractChallenge(clientDataJsonBytes: ByteArray): String? = runCatching {
        jsonMapper.readTree(clientDataJsonBytes).get("challenge")?.asText()
    }.getOrNull()

    private fun randomBytes(size: Int = CHALLENGE_BYTES): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun unauthorized(message: String) =
        Response.status(Response.Status.UNAUTHORIZED).entity(mapOf("error" to message)).build()

    private fun badRequest(message: String) =
        Response.status(Response.Status.BAD_REQUEST).entity(mapOf("error" to message)).build()

    companion object {
        private const val CHALLENGE_BYTES = 32
        private const val USER_ID_BYTES = 16
        private const val PURPOSE_REGISTRATION = "registration"
        private const val PURPOSE_AUTHENTICATION = "authentication"
    }
}
