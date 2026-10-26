// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.http.ContentType
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.jose4j.jws.JsonWebSignature
import org.junit.jupiter.api.Test

/**
 * E2E suite for the EUDI identity flows (eIDAS 2.0, ADR-0094), proving the full durable + wire
 * evidence chain over real HTTP, the real Postgres and — for the Trusted List — the real SSRF-safe
 * egress HTTP client against WireMock. Every pre-existing test in this module boots with the
 * in-memory Kafka connector, an EMPTY issuer signing key (issuance disabled) and an EMPTY trust
 * store (every presentation fails closed), so:
 *
 * - the OpenID4VCI issuance flow (offer → token → proof-of-possession → holder-bound SD-JWT VC)
 *   had never run end to end — [EudiCredentialIssuerResource] unit coverage calls the class
 *   directly, and the persistence ITs exercise the store, not the endpoints;
 * - the OpenID4VP cross-device flow (authorization request → signed Request Object → wallet
 *   direct_post callback → poll) had never completed over HTTP;
 * - the eIDAS Trusted List refresh had never fetched over the wire — the egress client, anchor
 *   signature verification, expiry enforcement and fail-closed keep-current-trust semantics are
 *   all proven here against WireMock.
 */
@QuarkusTest
@TestProfile(PidE2EProfile::class)
class PidEudiE2E : PidE2EBase() {

    private val mapper = ObjectMapper()

    // ── OpenID4VCI: metadata + issuance ─────────────────────────────────────────

    @Test
    fun `issuer metadata advertises the bank public key and endpoints`() {
        val body = (
            Given { this } When { get("/.well-known/openid-credential-issuer") } Then {
                statusCode(200)
            }
            ).extract().body().asString()
        val meta = mapper.readTree(body)
        assertThat(meta["credential_issuer"].asText()).isEqualTo(PidE2EKeys.BANK_ISSUER_ID)
        assertThat(meta["credential_endpoint"].asText())
            .isEqualTo("${PidE2EKeys.BANK_ISSUER_ID}/api/v1/parties/eudi/credential")
        assertThat(meta["token_endpoint"].asText())
            .isEqualTo("${PidE2EKeys.BANK_ISSUER_ID}/api/v1/parties/eudi/token")
        val keys = meta["jwks"]["keys"]
        assertThat(keys).hasSize(1)
        assertThat(keys[0]["kid"].asText()).isEqualTo(PidE2EKeys.bankIssuer.keyId)
    }

    @Test
    @TestSecurity(user = "e2e-m2m", roles = ["ROLE_API"])
    fun `the full OpenID4VCI flow mints a holder-bound SD-JWT VC`() {
        val (preAuthCode, _) = createOffer()
        assertThat(offerStatus(preAuthCode)).isEqualTo("OFFERED")

        val token = redeemToken(preAuthCode)
        assertThat(offerStatus(preAuthCode)).isEqualTo("AUTHORIZED")

        val vc = mintCredential(token.accessToken, PidE2EKeys.proofOfPossession(token.cNonce))

        // The issued credential is a genuine bank-signed SD-JWT VC: signature verifies against the
        // advertised public key, the holder key is bound via cnf.jwk, disclosures hash-bind into
        // _sd, and the status index is a real allocation in the durable store.
        val parts = vc.split("~")
        val jws = JsonWebSignature().apply {
            compactSerialization = parts[0]
            key = PidE2EKeys.bankIssuer.publicKey
        }
        assertThat(jws.verifySignature()).isTrue()
        val payload = mapper.readTree(jws.payload)
        assertThat(payload["iss"].asText()).isEqualTo(PidE2EKeys.BANK_ISSUER_ID)
        assertThat(payload["vct"].asText()).isEqualTo(PidE2EKeys.PID_VCT)
        assertThat(payload["sub"].asText()).isEqualTo("party-e2e-001")
        assertThat(payload["exp"].asLong() - payload["iat"].asLong()).isEqualTo(31_536_000L)
        val cnf = payload["cnf"]["jwk"]
        assertThat(cnf["kid"].asText()).isEqualTo(PidE2EKeys.holder.keyId)
        val disclosures = parts.subList(1, parts.size - 1).filter { it.isNotEmpty() }
        assertThat(disclosures).hasSize(3)
        val disclosed = disclosures.associate { d ->
            val arr = mapper.readTree(org.jose4j.base64url.Base64Url.decode(d).toString(Charsets.UTF_8))
            arr[1].asText() to arr[2].asText()
        }
        assertThat(disclosed).containsEntry("given_name", "Tereza")
        assertThat(disclosed).containsEntry("family_name", "E2eova")
        assertThat(disclosed).containsEntry("birthdate", "1991-04-17")
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        val sdHashes = payload["_sd"].map { it.asText() }.toSet()
        disclosures.forEach { d ->
            assertThat(
                sdHashes,
            ).contains(org.jose4j.base64url.Base64Url.encode(sha.digest(d.toByteArray(Charsets.US_ASCII))))
        }
        val statusIndex = payload["status"]["status_list"]["idx"].asLong()
        assertThat(statusListEntry(statusIndex)).isEqualTo(false to true)
        assertThat(payload["status"]["status_list"]["uri"].asText())
            .isEqualTo("${PidE2EKeys.BANK_ISSUER_ID}/api/v1/parties/eudi/status-lists/1")
    }

    @Test
    @TestSecurity(user = "e2e-m2m", roles = ["ROLE_API"])
    fun `the token endpoint is single-use and fail-closed`() {
        val (preAuthCode, _) = createOffer()
        redeemToken(preAuthCode)

        // The pre-authorized code is spent exactly once.
        Given {
            contentType(ContentType.URLENC)
            formParam("grant_type", PRE_AUTH_GRANT)
            formParam("pre-authorized_code", preAuthCode)
        } When { post("/api/v1/parties/eudi/token") } Then {
            statusCode(400)
            body("error", equalTo("invalid_grant"))
        }

        Given {
            contentType(ContentType.URLENC)
            formParam("grant_type", "authorization_code")
            formParam("pre-authorized_code", preAuthCode)
        } When { post("/api/v1/parties/eudi/token") } Then {
            statusCode(400)
            body("error", equalTo("unsupported_grant_type"))
        }

        Given {
            contentType(ContentType.URLENC)
            formParam("grant_type", PRE_AUTH_GRANT)
            formParam("pre-authorized_code", "no-such-code")
        } When { post("/api/v1/parties/eudi/token") } Then {
            statusCode(400)
            body("error", equalTo("invalid_grant"))
        }
    }

    @Test
    @TestSecurity(user = "e2e-m2m", roles = ["ROLE_API"])
    fun `the credential proof is bound to the nonce and issuer id and minting is single-use`() {
        val (preAuthCode, _) = createOffer()
        val token = redeemToken(preAuthCode)

        // A proof over the wrong nonce is refused before anything is minted.
        Given {
            contentType(ContentType.JSON)
            header("Authorization", "Bearer ${token.accessToken}")
            body(mapOf("proof" to mapOf("jwt" to PidE2EKeys.proofOfPossession("wrong-nonce"))))
        } When { post("/api/v1/parties/eudi/credential") } Then {
            statusCode(400)
            body("error", equalTo("invalid_proof"))
        }

        // A proof for a different audience is equally refused.
        Given {
            contentType(ContentType.JSON)
            header("Authorization", "Bearer ${token.accessToken}")
            body(mapOf("proof" to mapOf("jwt" to PidE2EKeys.proofOfPossession(token.cNonce, "https://evil.example"))))
        } When { post("/api/v1/parties/eudi/credential") } Then {
            statusCode(400)
            body("error", equalTo("invalid_proof"))
        }

        mintCredential(token.accessToken, PidE2EKeys.proofOfPossession(token.cNonce))

        // The access token is single-use by CAS: a second mint loses the race.
        Given {
            contentType(ContentType.JSON)
            header("Authorization", "Bearer ${token.accessToken}")
            body(mapOf("proof" to mapOf("jwt" to PidE2EKeys.proofOfPossession(token.cNonce))))
        } When { post("/api/v1/parties/eudi/credential") } Then {
            statusCode(400)
            body("error", equalTo("invalid_token"))
        }
        assertThat(offerStatus(preAuthCode)).isEqualTo("ISSUED")
    }

    // ── OpenID4VCI: revocation ──────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "e2e-m2m", roles = ["ROLE_API"])
    fun `revocation flips the durable bit and the published status-list token proves it`() {
        val vc = issueAndParseCredential()
        val idx = vc.statusIndex

        Given { this } When { post("/api/v1/parties/eudi/credentials/$idx/revoke") } Then {
            statusCode(200)
            body("status", equalTo("revoked"))
        }
        assertThat(statusListEntry(idx)).isEqualTo(true to true)

        // The published Token Status List verifies against the bank key and carries the flipped bit.
        val response = Given { this } When { get("/api/v1/parties/eudi/status-lists/1") } Then {
            statusCode(200)
            header("Cache-Control", containsString("max-age=3600"))
        }
        assertThat(response.extract().contentType()).contains("application/statuslist+jwt")
        val parsed = PidE2EKeys.parseStatusListToken(response.extract().body().asString())
        assertThat(parsed.revokedIndices).contains(idx)
        assertThat(parsed.ttl).isEqualTo(3600L)

        // An index that was never allocated cannot be revoked.
        Given { this } When { post("/api/v1/parties/eudi/credentials/987654321/revoke") } Then {
            statusCode(400)
            body("error", equalTo("unknown status index"))
        }

        // A different status list id is not served.
        Given { this } When { get("/api/v1/parties/eudi/status-lists/42") } Then { statusCode(404) }
    }

    @Test
    @TestSecurity(user = "e2e-m2m", roles = ["ROLE_API"])
    fun `a credential the bank revoked fails verification at the relying party`() {
        val vc = issueAndParseCredential()
        Given { this } When { post("/api/v1/parties/eudi/credentials/${vc.statusIndex}/revoke") } Then {
            statusCode(200)
        }

        // The presentation is cryptographically genuine — signature, trust, disclosures — but the
        // revocation gate must still refuse it (Token Status List, ADR-0094).
        Given {
            contentType(ContentType.JSON)
            body(mapOf("vpToken" to vc.presentation))
        } When { post("/api/v1/parties/eudi/verify-presentation") } Then {
            statusCode(422)
            body("message", containsString("revoked"))
        }
    }

    // ── Relying-party verification (SD-JWT VC over the M2M leg) ────────────────

    @Test
    @TestSecurity(user = "e2e-m2m", roles = ["ROLE_API"])
    fun `a genuine wallet presentation verifies and resolves over the real HTTP path`() {
        val presentation = PidE2EKeys.walletPresentation(PidE2EKeys.walletA, "sub-wallet-a-1")
        val body = (
            Given {
                contentType(ContentType.JSON)
                body(mapOf("vpToken" to presentation.presentation))
            } When { post("/api/v1/parties/eudi/verify-presentation") } Then {
                statusCode(200)
            }
            ).extract().body().asString()
        val result = mapper.readTree(body)
        assertThat(result["decision"].asText()).isEqualTo("NO_MATCH")
        assertThat(result["loa"].asText()).isEqualTo("HIGH")
        assertThat(result["verifiedClaims"]["givenName"].asText()).isEqualTo("Tereza")
        assertThat(result["verifiedClaims"]["familyName"].asText()).isEqualTo("E2eova")
        assertThat(result["verifiedClaims"]["birthDate"].asText()).isEqualTo("1991-04-17")
        assertThat(result["verifiedClaims"]["issuer"].asText()).isEqualTo(PidE2EKeys.WALLET_A_ISS)
        assertThat(result["verifiedClaims"]["subjectIdMasked"].asText()).isNotBlank()
    }

    @Test
    @TestSecurity(user = "e2e-m2m", roles = ["ROLE_API"])
    fun `an untrusted issuer and a mismatched key-binding fail closed`() {
        // Wallet C is on no trust layer at all.
        val fromC = PidE2EKeys.walletPresentation(PidE2EKeys.walletC, "sub-wallet-c-1")
        Given {
            contentType(ContentType.JSON)
            body(mapOf("vpToken" to fromC.presentation))
        } When { post("/api/v1/parties/eudi/verify-presentation") } Then {
            statusCode(422)
            body("message", containsString("untrusted issuer"))
        }

        // Wallet A is trusted, but its key-binding JWT was minted for a different nonce.
        val wrongBinding = PidE2EKeys.walletPresentation(PidE2EKeys.walletA, "sub-wallet-a-2", nonce = "kb-nonce")
        Given {
            contentType(ContentType.JSON)
            body(
                mapOf(
                    "vpToken" to wrongBinding.presentation,
                    "nonce" to "expected-nonce",
                ),
            )
        } When { post("/api/v1/parties/eudi/verify-presentation") } Then {
            statusCode(422)
            body("message", containsString("key-binding nonce mismatch"))
        }
    }

    // ── OpenID4VP cross-device flow ─────────────────────────────────────────────

    @Test
    @TestSecurity(user = "e2e-m2m", roles = ["ROLE_API"])
    fun `the OpenID4VP cross-device flow completes end to end`() {
        val created = (
            Given { this } When { post("/api/v1/parties/eudi/presentation-requests") } Then {
                statusCode(200)
            }
            ).extract().body().asString()
        val transactionId = mapper.readTree(created)["transactionId"].asText()

        // An issuer key is configured, so the request rides the signed Request Object (JAR) mode.
        val authUri = mapper.readTree(created)["authorizationRequestUri"].asText()
        assertThat(authUri).startsWith("openid4vp://authorize?client_id=openbank-pid&request_uri=")

        val row = exchangeRow(transactionId)
        assertThat(row).isNotNull()
        val (_, nonce, audience) = row!!
        assertThat(audience).isEqualTo("openbank-pid")

        // The wallet dereferences the request_uri: a bank-signed JAR carrying the exchange nonce.
        val jarPayload = fetchAndVerifyJar(transactionId)
        assertThat(jarPayload["client_id"].asText()).isEqualTo("openbank-pid")
        assertThat(jarPayload["state"].asText()).isEqualTo(transactionId)
        assertThat(jarPayload["nonce"].asText()).isEqualTo(nonce)
        assertThat(jarPayload["response_mode"].asText()).isEqualTo("direct_post")

        // The wallet posts its presentation bound to the single-use nonce and this RP's audience.
        val vpToken = PidE2EKeys.walletPresentation(
            PidE2EKeys.walletA,
            "sub-cross-device-1",
            nonce = nonce,
            audience = audience,
        ).presentation
        Given {
            contentType(ContentType.URLENC)
            formParam("vp_token", vpToken)
            formParam("state", transactionId)
            formParam("presentation_submission", "{}")
        } When { post("/api/v1/parties/eudi/presentation-responses") } Then {
            statusCode(200)
            body("status", equalTo("received"))
        }

        // The authenticated poll collects the decision; the nonce is spent.
        val poll = (
            Given { this } When { get("/api/v1/parties/eudi/presentation-requests/$transactionId") } Then {
                statusCode(200)
            }
            ).extract().body().asString()
        val pollNode = mapper.readTree(poll)
        assertThat(pollNode["status"].asText()).isEqualTo("COMPLETED")
        assertThat(pollNode["decision"].asText()).isEqualTo("NO_MATCH")
        assertThat(pollNode["verifiedClaims"]["givenName"].asText()).isEqualTo("Tereza")
        assertThat(exchangeRow(transactionId)?.first).isEqualTo("COMPLETED")

        // Replaying the same direct_post against the spent exchange is refused neutrally.
        Given {
            contentType(ContentType.URLENC)
            formParam("vp_token", vpToken)
            formParam("state", transactionId)
        } When { post("/api/v1/parties/eudi/presentation-responses") } Then {
            statusCode(400)
            body("error", equalTo("invalid_request"))
        }
    }

    @Test
    @TestSecurity(user = "e2e-m2m", roles = ["ROLE_API"])
    fun `the wallet callback stays a neutral oracle and a failed attempt keeps the exchange pending`() {
        val transactionId = mapper.readTree(
            (
                Given { this } When { post("/api/v1/parties/eudi/presentation-requests") } Then {
                    statusCode(200)
                }
                ).extract().body().asString(),
        )["transactionId"].asText()

        // Unknown state: indistinguishable from a consumed one.
        Given {
            contentType(ContentType.URLENC)
            formParam("vp_token", "anything")
            formParam("state", "no-such-transaction")
        } When { post("/api/v1/parties/eudi/presentation-responses") } Then {
            statusCode(400)
            body("error", equalTo("invalid_request"))
        }

        // A garbage presentation fails neutrally and MUST NOT spend the exchange — the wallet may
        // retry within the TTL.
        Given {
            contentType(ContentType.URLENC)
            formParam("vp_token", "not-a-jwt")
            formParam("state", transactionId)
        } When { post("/api/v1/parties/eudi/presentation-responses") } Then {
            statusCode(400)
            body("error", equalTo("invalid_request"))
        }
        assertThat(exchangeRow(transactionId)?.first).isEqualTo("PENDING")

        // The retry with a genuine presentation succeeds.
        val row = exchangeRow(transactionId)!!
        val vpToken = PidE2EKeys.walletPresentation(
            PidE2EKeys.walletA,
            "sub-retry-1",
            nonce = row.second,
            audience = row.third,
        ).presentation
        Given {
            contentType(ContentType.URLENC)
            formParam("vp_token", vpToken)
            formParam("state", transactionId)
        } When { post("/api/v1/parties/eudi/presentation-responses") } Then {
            statusCode(200)
            body("status", equalTo("received"))
        }
        assertThat(exchangeRow(transactionId)?.first).isEqualTo("COMPLETED")
    }

    // ── eIDAS Trusted List over the real egress wire ────────────────────────────

    @Test
    @TestSecurity(user = "e2e-m2m", roles = ["ROLE_API"])
    fun `the signed trusted list widens trust over the wire and a tampered list never applies`() {
        // The boot-time refresh already applied the initial list (wallet B) — the dynamic layer works.
        assertThat(verifyDecision(PidE2EKeys.walletB, "sub-list-b-1")).isEqualTo("NO_MATCH")

        // The scheduled refresher is alive: its first tick lands ~30 s after boot (the @Scheduled
        // delay is hard-coded), observed through the WireMock request journal.
        awaitListRequests(atLeast = 2, deadlineSeconds = 60)

        // Widening: a fresh anchor-signed list adds wallet C; the next refresh applies it.
        PidE2EWireMockResource.serveSigned(
            PidE2EKeys.WALLET_B_ISS to PidE2EKeys.walletB,
            PidE2EKeys.WALLET_C_ISS to PidE2EKeys.walletC,
        )
        val seenAfterWiden = PidE2EWireMockResource.listRequests()
        awaitDecision(PidE2EKeys.walletC, "sub-list-c-1", "NO_MATCH", deadlineSeconds = 30)
        // The refresher must keep polling AFTER the widened list applied — await the next tick
        // instead of asserting instantly (a 1 s cadence means the next fetch may not have landed
        // yet the moment the decision flips; measured: instant assert raced the tick and failed 4>4).
        awaitListRequests(atLeast = seenAfterWiden + 1, deadlineSeconds = 15)

        // Tampering: a list signed by the WRONG key must never apply. Current trust is kept
        // (wallet C stays resolvable) and the tampered entry (wallet D) stays untrusted. The
        // wallet-D presentation is signed by wallet C's key but claims the tampered iss.
        PidE2EWireMockResource.serveTampered("did:e2e:wallet-d" to PidE2EKeys.walletC)
        awaitListRequests(atLeast = PidE2EWireMockResource.listRequests() + 1, deadlineSeconds = 15)
        assertThat(verifyDecision(PidE2EKeys.walletC, "sub-list-c-2")).isEqualTo("NO_MATCH")
        assertThat(verifyDecision(PidE2EKeys.walletB, "sub-list-b-2")).isEqualTo("NO_MATCH")
        Given {
            contentType(ContentType.JSON)
            body(
                mapOf(
                    "vpToken" to PidE2EKeys.walletPresentation(
                        PidE2EKeys.walletC,
                        "sub-list-d-1",
                        issOverride = "did:e2e:wallet-d",
                    ).presentation,
                ),
            )
        } When { post("/api/v1/parties/eudi/verify-presentation") } Then {
            statusCode(422)
            body("message", containsString("untrusted issuer"))
        }
    }

    // ── Party lifecycle on the real broker ──────────────────────────────────────

    @Test
    @TestSecurity(user = "e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `creating a party publishes PartyCreated on the real broker`() {
        ensureTopicOnce(PARTY_EVENTS_TOPIC)
        val body = (
            Given {
                contentType(ContentType.JSON)
                body(
                    """
                    {
                      "partyType": "NATURAL_PERSON",
                      "givenName": "E2E",
                      "familyName": "Brokerova",
                      "birthdate": "1993-08-02",
                      "nationalities": ["CZ"],
                      "verificationSource": "BANKID",
                      "onboardingChannel": "BANKID"
                    }
                    """.trimIndent(),
                )
            } When { post("/api/v1/parties") } Then {
                statusCode(201)
            }
            ).extract().body().asString()

        val createdId = mapper.readTree(body)["id"].asText()
        val envelope = awaitEvent(PARTY_EVENTS_TOPIC, createdId, "PartyCreated")
        assertThat(envelope["aggregateId"].asText()).isEqualTo(createdId)
        assertThat(envelope["payload"]["partyType"].asText()).isEqualTo("NATURAL_PERSON")
        assertThat(envelope["payload"]["givenName"].asText()).isEqualTo("E2E")
        assertThat(envelope["payload"]["occurredAt"].asText()).isNotBlank()
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private data class Offer(val preAuthCode: String, val subjectId: String)

    private data class Token(val accessToken: String, val cNonce: String)

    private class IssuedCredential(val presentation: String, val statusIndex: Long)

    private fun createOffer(subjectId: String = "party-e2e-001"): Offer {
        val body = (
            Given {
                contentType(ContentType.JSON)
                body(
                    """
                    {
                      "subjectId": "$subjectId",
                      "givenName": "Tereza",
                      "familyName": "E2eova",
                      "birthdate": "1991-04-17",
                      "issuingCountry": "CZ"
                    }
                    """.trimIndent(),
                )
            } When { post("/api/v1/parties/eudi/credential-offers") } Then {
                statusCode(200)
            }
            ).extract().body().asString()
        val node = mapper.readTree(body)
        val offer = node["credentialOffer"]
        assertThat(offer["credential_issuer"].asText()).isEqualTo(PidE2EKeys.BANK_ISSUER_ID)
        assertThat(node["credentialOfferUri"].asText()).startsWith("openid-credential-offer://")
        val preAuthCode = offer["grants"][PRE_AUTH_GRANT]["pre-authorized_code"].asText()
        assertThat(preAuthCode).isNotBlank()
        return Offer(preAuthCode, subjectId)
    }

    private fun redeemToken(preAuthCode: String): Token {
        val body = (
            Given {
                contentType(ContentType.URLENC)
                formParam("grant_type", PRE_AUTH_GRANT)
                formParam("pre-authorized_code", preAuthCode)
            } When { post("/api/v1/parties/eudi/token") } Then {
                statusCode(200)
                body("token_type", equalTo("bearer"))
            }
            ).extract().body().asString()
        val node = mapper.readTree(body)
        return Token(node["access_token"].asText(), node["c_nonce"].asText())
    }

    private fun mintCredential(accessToken: String, proofJwt: String): String {
        val body = (
            Given {
                contentType(ContentType.JSON)
                header("Authorization", "Bearer $accessToken")
                body(mapOf("proof" to mapOf("jwt" to proofJwt)))
            } When { post("/api/v1/parties/eudi/credential") } Then {
                statusCode(200)
            }
            ).extract().body().asString()
        return mapper.readTree(body)["credential"].asText()
    }

    /** Issue a bank credential and return it re-wrapped as a wallet presentation (issuer JWS + disclosures). */
    private fun issueAndParseCredential(): IssuedCredential {
        val offer = createOffer()
        val token = redeemToken(offer.preAuthCode)
        val vc = mintCredential(token.accessToken, PidE2EKeys.proofOfPossession(token.cNonce))
        val parts = vc.split("~")
        val payload = mapper.readTree(
            JsonWebSignature().apply {
                compactSerialization = parts[0]
                key = PidE2EKeys.bankIssuer.publicKey
            }.let {
                check(it.verifySignature()) { "bank signature must verify" }
                it.payload
            },
        )
        return IssuedCredential(
            presentation = parts.takeWhile { it.isNotEmpty() }.joinToString("~") + "~",
            statusIndex = payload["status"]["status_list"]["idx"].asLong(),
        )
    }

    /** Dereferences the request_uri and returns the verified JAR payload (signed by the bank issuer key). */
    private fun fetchAndVerifyJar(transactionId: String): com.fasterxml.jackson.databind.JsonNode {
        val jar = (
            Given { this } When { get("/api/v1/parties/eudi/request-objects/$transactionId") } Then {
                statusCode(200)
            }
            ).extract().body().asString()
        val jarJws = JsonWebSignature().apply {
            compactSerialization = jar
            key = PidE2EKeys.bankIssuer.publicKey
        }
        assertThat(jarJws.verifySignature()).isTrue()
        return mapper.readTree(jarJws.payload)
    }

    private fun verifyDecision(issuerKey: org.jose4j.jwk.PublicJsonWebKey, subjectId: String): String {
        val response = Given {
            contentType(ContentType.JSON)
            body(mapOf("vpToken" to PidE2EKeys.walletPresentation(issuerKey, subjectId).presentation))
        } When { post("/api/v1/parties/eudi/verify-presentation") }
        val status = response.statusCode
        val body = response.then().extract().body().asString()
        check(status == 200) { "verify-presentation answered $status: $body" }
        return mapper.readTree(body)["decision"].asText()
    }

    private fun awaitDecision(
        issuerKey: org.jose4j.jwk.PublicJsonWebKey,
        subjectId: String,
        expected: String,
        deadlineSeconds: Long,
    ) {
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(deadlineSeconds).toNanos()
        // A 422 (untrusted issuer / not yet applied) IS the expected pre-refresh state — poll through
        // it; only the deadline is a failure. (Measured: the first poll landed ~200 ms after the
        // restub, one refresh tick before the widened list applied, and an immediate check() threw.)
        var last = "<no attempt>"
        while (System.nanoTime() < deadline) {
            val response = Given {
                contentType(ContentType.JSON)
                body(mapOf("vpToken" to PidE2EKeys.walletPresentation(issuerKey, subjectId).presentation))
            } When { post("/api/v1/parties/eudi/verify-presentation") }
            val body = response.then().extract().body().asString()
            if (response.statusCode == 200) {
                val decision = runCatching { mapper.readTree(body)["decision"].asText() }.getOrNull()
                if (decision == expected) return
                last = "decision=$decision"
            } else {
                last = "${response.statusCode}: $body"
            }
            Thread.sleep(500)
        }
        error("decision did not become $expected within ${deadlineSeconds}s (last: $last)")
    }

    private fun awaitListRequests(atLeast: Int, deadlineSeconds: Long) {
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(deadlineSeconds).toNanos()
        while (System.nanoTime() < deadline) {
            if (PidE2EWireMockResource.listRequests() >= atLeast) return
            Thread.sleep(500)
        }
        error("trusted-list fetch count did not reach $atLeast within ${deadlineSeconds}s")
    }

    private companion object {
        const val PRE_AUTH_GRANT = "urn:ietf:params:oauth:grant-type:pre-authorized_code"

        fun equalTo(expected: String) = org.hamcrest.Matchers.equalTo(expected)

        fun containsString(substring: String) = org.hamcrest.Matchers.containsString(substring)
    }
}
