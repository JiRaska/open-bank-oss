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
 * doubly here: [registerBegin]/[registerComplete]'s bearer may be an [EnrollmentTicketService]
 * ticket rather than a Keycloak-issued JWT — the OIDC filter would reject it as malformed before
 * this class ever saw it. [authBegin]/[authComplete] need no token at all (obtaining one is the
 * point). A token-bearing caller is verified out-of-band instead, by
 * [WebAuthnKeycloakClient.introspectCustomerToken] — see [authorizedEnroller].
 *
 * A party that onboarded via the F1 hosted Keycloak flow (`ASWebAuthenticationSession`) has a
 * passkey registered with *Keycloak's own* WebAuthn RP, not this one, and this edge RP keeps an
 * entirely separate credential store — so [authBegin]/[authComplete] only ever work for a device
 * that has completed [registerBegin]/[registerComplete] against THIS RP. Such a party bridges
 * across by enrolling with their access token after a hosted login
 * ([tech.openbank.app.auth.AuthService.enrollPasskey]); [authorizedEnroller] accepts exactly that,
 * which is also the recovery path when this RP's credential store is lost (issue #1260).
 */
@Path("/customer/v1/webauthn")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class WebAuthnResource(
    private val ticketService: EnrollmentTicketService,
    private val challengeStore: ChallengeStore,
    private val credentialStore: WebAuthnStore,
    private val keycloakClient: WebAuthnKeycloakClient,
    private val deviceSessions: DeviceSessionStore,
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
        val partyId = authorizedEnroller(authorization)?.partyId
            ?: return unauthorized("Invalid or expired enrollment credential")

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
        val enroller = authorizedEnroller(authorization)
            ?: return unauthorized("Invalid or expired enrollment credential")
        val partyId = enroller.partyId

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

        // An enroller who authenticated with their own access token ALREADY has a Keycloak user —
        // bind the credential to that `sub`. Routing them through ensureUser() instead would look
        // up the synthetic `party+<id>@openbank.internal` address, not find their real one, and
        // create a SECOND user for the same party: the credential would then mint a session for an
        // account holding none of their data, and party_id == sub would break for it.
        //
        // Only the ticket path lands in ensureUser: party.id == Keycloak sub (ADR-0069 invariant),
        // and the edge knows no email for a brand-new party at this layer, so it derives a
        // synthetic one. The realm's party-id protocol mapper (openbank-app client) then carries
        // party_id as a claim on any future token regardless of how this user later authenticates.
        val keycloakUserId = enroller.existingKeycloakUserId
            ?: keycloakClient.ensureUser(
                email = "party+$partyId@openbank.internal",
                partyId = partyId,
                displayName = "OpenBank Customer",
            )

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

        // Mint the session straight from the registration ceremony (ADR-0066 F2). This used to
        // return 204, forcing the app to run a SECOND ceremony against /auth/begin+complete just
        // to get a token — a second Face ID prompt during onboarding for no security gain: the
        // registration verified above is a user-verified (Face ID), user-present ceremony over a
        // server challenge bound to this origin/rpId, i.e. exactly the factors /auth/complete
        // checks before calling the same impersonate(). The enrollment ticket already authorised
        // this party binding.
        //
        // The response is additive: a client that only checked the status code keeps working.
        val (accessToken, refreshToken) = keycloakClient.impersonate(keycloakUserId)
        val deviceSessionId = deviceSessions.issue(keycloakUserId)
        return Response.ok(TokenPairDto(accessToken, refreshToken, deviceSessionId)).build()
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
        val deviceSessionId = deviceSessions.issue(stored.keycloakUserId)
        return Response.ok(TokenPairDto(accessToken, refreshToken, deviceSessionId)).build()
    }

    /**
     * Silently resume a native-passkey session WITHOUT a passkey ceremony (ADR-0066 F2 refresh fix).
     * The app presents the opaque device-session id it got at login; the edge validates+rotates it
     * (single-use) and re-mints a fresh access token via impersonate() — the exchange-minted token is
     * not refreshable by the public openbank-app client, so a normal refresh_token grant can't do it.
     * Public (no bearer): the device-session id IS the credential, device-bound in the app Keychain.
     */
    @POST
    @Path("/session/refresh")
    @Blocking
    fun sessionRefresh(request: SessionRefreshRequestDto): Response {
        val keycloakUserId = deviceSessions.consume(request.deviceSessionId)
            ?: return Response.status(Response.Status.UNAUTHORIZED)
                .entity(mapOf("error" to "invalid_device_session")).build()
        val (accessToken, refreshToken) = keycloakClient.impersonate(keycloakUserId)
        val rotated = deviceSessions.issue(keycloakUserId)
        return Response.ok(TokenPairDto(accessToken, refreshToken, rotated)).build()
    }

    // ── Internals ─────────────────────────────────────────────────────────────────────────

    /** Reads the `Authorization: Bearer <ticket>` header and resolves it to a partyId, or null. */
    /**
     * Resolve the party enrolling a credential from the `Authorization` bearer, which may be
     * EITHER of two credentials — deliberately, and in this order:
     *
     *  1. an [EnrollmentTicketService] ticket — the native F2 onboarding branch, where the party
     *     was just created via M2M and no Keycloak session exists yet;
     *  2. the party's own Keycloak access token — an already-onboarded user enrolling a native
     *     credential on this device ([tech.openbank.app.auth.AuthService.enrollPasskey], the F1→F2
     *     bridge and the only recovery path when this RP's credential store is lost, issue #1260).
     *
     * Case 2 was the KNOWN GAP in this class's KDoc: the app has always sent its access token here,
     * and a JWT happens to have the ticket's three dot-separated parts, so it reached
     * [EnrollmentTicketService.verify], failed the numeric-expiry parse and fell out as a bare 401.
     * Enrolment could therefore never succeed for an existing session.
     *
     * The ticket is tried first because it is the cheap local HMAC check; a token costs a Keycloak
     * round-trip. Both fail closed.
     */
    private fun authorizedEnroller(authorization: String?): Enroller? {
        val bearer = authorization?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        ticketService.verify(bearer)?.let { partyId ->
            // Ticket path: brand-new party, no Keycloak user yet — registerComplete creates one.
            return Enroller(partyId = partyId, existingKeycloakUserId = null)
        }
        return keycloakClient.introspectCustomerToken(bearer)
            ?.let { Enroller(partyId = it.partyId, existingKeycloakUserId = it.keycloakUserId) }
    }

    /**
     * A party permitted to enrol a credential. [existingKeycloakUserId] is non-null only when the
     * caller authenticated with their own access token, i.e. the Keycloak user already exists and
     * must be reused rather than re-created.
     */
    private data class Enroller(val partyId: String, val existingKeycloakUserId: String?)

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
