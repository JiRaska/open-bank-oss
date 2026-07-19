// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [WebAuthnResource]'s gates that don't require exercising real webauthn4j crypto
 * (attestation/assertion verification is exercised by the app + a real device, not fixture data
 * here): the enrollment-ticket bearer check on the registration endpoints, and the challenge-store
 * replay guard shared by all four routes.
 */
class WebAuthnResourceTest {

    private val ticketService = mockk<EnrollmentTicketService>()
    private val challengeStore = mockk<ChallengeStore>()
    private val credentialStore = mockk<WebAuthnStore>()
    private val keycloakClient = mockk<WebAuthnKeycloakClient>()
    private val deviceSessions = mockk<DeviceSessionStore>(relaxed = true)

    private val resource = WebAuthnResource(
        ticketService,
        challengeStore,
        credentialStore,
        keycloakClient,
        deviceSessions,
        ObjectMapper(),
    ).apply {
        rpId = "open-bank.tech"
        origin = "https://open-bank.tech"
    }

    @BeforeEach
    fun `default to no access-token identity`() {
        // The registration routes fall back to Keycloak introspection when the bearer is not a
        // ticket; unless a test says otherwise, that fallback finds nothing.
        every { keycloakClient.introspectCustomerToken(any()) } returns null
    }

    // ---- register/begin: enrollment ticket gate --------------------------------------------

    @Test
    fun `register begin without an Authorization header is rejected`() {
        val resp = resource.registerBegin(authorization = null)
        assertThat(resp.status).isEqualTo(401)
    }

    @Test
    fun `register begin with a malformed Authorization header is rejected`() {
        // No "Bearer " prefix to strip, so the raw header value is handed to the ticket
        // service as-is — it must reject it exactly like any other invalid ticket string.
        every { ticketService.verify("not-a-bearer-header") } returns null
        val resp = resource.registerBegin(authorization = "not-a-bearer-header")
        assertThat(resp.status).isEqualTo(401)
    }

    @Test
    fun `register begin with an invalid or expired ticket is rejected`() {
        every { ticketService.verify("bad-ticket") } returns null
        val resp = resource.registerBegin(authorization = "Bearer bad-ticket")
        assertThat(resp.status).isEqualTo(401)
    }

    @Test
    fun `register begin with a valid ticket issues a challenge and saves it`() {
        every { ticketService.verify("good-ticket") } returns "party-123"
        every { challengeStore.save(any(), "registration") } returns Unit

        val resp = resource.registerBegin(authorization = "Bearer good-ticket")

        assertThat(resp.status).isEqualTo(200)
        val body = resp.entity as RegistrationChallengeDto
        assertThat(body.rpId).isEqualTo("open-bank.tech")
        assertThat(body.userName).isEqualTo("party-123")
        verify(exactly = 1) { challengeStore.save(any(), "registration") }
    }

    @Test
    fun `register complete without a valid ticket is rejected before touching the challenge store`() {
        every { ticketService.verify(any()) } returns null

        val resp = resource.registerComplete(
            authorization = "Bearer whatever",
            request = RegistrationCompleteRequestDto("id", "attestation", "clientData"),
        )

        assertThat(resp.status).isEqualTo(401)
        verify(exactly = 0) { challengeStore.consume(any()) }
    }

    // ---- register/*: the access-token path (F1 -> F2 bridge, issue #1260 recovery) ---------

    @Test
    fun `register begin accepts the party's own access token when the bearer is not a ticket`() {
        // The app's enrollPasskey sends a Keycloak access token, not a ticket. A JWT has the
        // ticket's three dot-separated parts, so it reaches the ticket service and fails there —
        // introspection is what must recognise it.
        every { ticketService.verify("kc-access-token") } returns null
        every { keycloakClient.introspectCustomerToken("kc-access-token") } returns
            CustomerTokenIdentity(partyId = "party-123", keycloakUserId = "kc-user-9")
        every { challengeStore.save(any(), "registration") } returns Unit

        val resp = resource.registerBegin(authorization = "Bearer kc-access-token")

        assertThat(resp.status).isEqualTo(200)
        assertThat((resp.entity as RegistrationChallengeDto).userName).isEqualTo("party-123")
    }

    @Test
    fun `register begin is rejected when introspection does not recognise the token`() {
        every { ticketService.verify(any()) } returns null
        every { keycloakClient.introspectCustomerToken("forged") } returns null

        val resp = resource.registerBegin(authorization = "Bearer forged")

        assertThat(resp.status).isEqualTo(401)
        verify(exactly = 0) { challengeStore.save(any(), any()) }
    }

    @Test
    fun `register complete with an access token gets past the auth gate`() {
        // Proves the token is accepted as an enrolment credential: the request now fails on its
        // malformed clientDataJSON (400) rather than on authorization (401). The crypto path
        // beyond this point needs a real authenticator, so it is exercised on a device.
        every { ticketService.verify(any()) } returns null
        every { keycloakClient.introspectCustomerToken("kc-access-token") } returns
            CustomerTokenIdentity(partyId = "party-123", keycloakUserId = "kc-user-9")

        val resp = resource.registerComplete(
            authorization = "Bearer kc-access-token",
            request = RegistrationCompleteRequestDto("id", "attestation", "bm90LWpzb24"), // "not-json"
        )

        assertThat(resp.status).isEqualTo(400)
    }

    @Test
    fun `an access-token enroller never has a second Keycloak user created for their party`() {
        // The token's `sub` IS the user. ensureUser() looks up a synthetic
        // party+<id>@openbank.internal address, would not find their real one, and would create a
        // duplicate — binding the new credential to an account holding none of their data.
        every { ticketService.verify(any()) } returns null
        every { keycloakClient.introspectCustomerToken("kc-access-token") } returns
            CustomerTokenIdentity(partyId = "party-123", keycloakUserId = "kc-user-9")

        resource.registerComplete(
            authorization = "Bearer kc-access-token",
            request = RegistrationCompleteRequestDto("id", "attestation", "bm90LWpzb24"),
        )

        verify(exactly = 0) { keycloakClient.ensureUser(any(), any(), any()) }
    }

    // A registration ceremony is user-verified (Face ID) + user-present over a server challenge —
    // the same factors /auth/complete checks before minting. Returning 204 here forced the app to
    // run a SECOND ceremony purely to get a token, i.e. a second Face ID prompt in onboarding for
    // no security gain (ADR-0066 F2). The mint must never fire before the credential is stored.
    @Test
    fun `register complete never mints a session when the ceremony is rejected`() {
        every { ticketService.verify("good-ticket") } returns "party-123"
        // Malformed clientDataJSON -> the ceremony fails before any credential is stored.
        val resp = resource.registerComplete(
            authorization = "Bearer good-ticket",
            request = RegistrationCompleteRequestDto("id", "attestation", "bm90LWpzb24"),
        )

        assertThat(resp.status).isNotEqualTo(200)
        verify(exactly = 0) { keycloakClient.impersonate(any()) }
        verify(exactly = 0) { credentialStore.save(any()) }
    }

    // ---- auth/begin + auth/complete: fully public, challenge-store gate --------------------

    @Test
    fun `auth begin issues a challenge with no allowCredentials restriction`() {
        every { challengeStore.save(any(), "authentication") } returns Unit

        val resp = resource.authBegin()

        assertThat(resp.status).isEqualTo(200)
        val body = resp.entity as AuthenticationChallengeDto
        assertThat(body.allowCredentials).isEmpty()
        assertThat(body.rpId).isEqualTo("open-bank.tech")
    }

    @Test
    fun `auth complete rejects an unknown or already-consumed challenge`() {
        // clientDataJson = base64url(`{"challenge":"c","type":"webauthn.get"}`)
        val clientDataJson = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"challenge":"c","type":"webauthn.get"}""".toByteArray())
        every { challengeStore.consume("c") } returns null

        val resp = resource.authComplete(
            AuthCompleteRequestDto(
                credentialId = "cred",
                authenticatorData = "AA",
                clientDataJson = clientDataJson,
                signature = "AA",
                userHandle = "AA",
            ),
        )

        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { credentialStore.find(any()) }
    }

    @Test
    fun `auth complete rejects an unknown credential without calling Keycloak`() {
        val clientDataJson = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"challenge":"c","type":"webauthn.get"}""".toByteArray())
        every { challengeStore.consume("c") } returns "authentication"
        every { credentialStore.find("unknown-cred") } returns null

        val resp = resource.authComplete(
            AuthCompleteRequestDto(
                credentialId = "unknown-cred",
                authenticatorData = "AA",
                clientDataJson = clientDataJson,
                signature = "AA",
                userHandle = "AA",
            ),
        )

        assertThat(resp.status).isEqualTo(401)
        verify(exactly = 0) { keycloakClient.impersonate(any()) }
    }
}
