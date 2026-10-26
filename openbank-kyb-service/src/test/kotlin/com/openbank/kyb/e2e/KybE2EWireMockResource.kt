// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.notFound
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.serverError
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Stands in for every out-of-process hop the ADR-0284 onboarding chain makes — ARES (economic
 * subject, public register, name search), party-service (entity party create, identity read,
 * mandate grant), document-service (business agreement ensure + ceremony read) and the Keycloak
 * token endpoint both platform clients mint their M2M bearer from (the party client via the NAMED
 * `m2m` oidc-client, the document client via the default shared client — #10486 pinned on the
 * wire by returning a DIFFERENT token per client). Same shape as vop's, statement's and
 * risk-engine's downstream resources: the REST clients, their @Retry/@CircuitBreaker annotations,
 * the OIDC filters and the taint propagation all cross a wire they actually have to.
 *
 * The request journal is the fan-out proof: [requestsTo] counts requests since the last reset, so
 * a test can assert "the retry actually dialled ARES twice" or "validation refused before any
 * register was asked" with [com.github.tomakehurst.wiremock.verification.LoggedRequest] bodies as
 * the verbatim evidence.
 *
 * Static stubs cover the token endpoint, the register subjects and the party create/identity/
 * mandate routes. The document agreement ensure and the ceremony GET are registered PER CASE by
 * the suite ([stubAgreementEnsure] / [stubCeremony]): the response must echo the case's own id
 * (requireOwnCeremony refuses a foreign ceremony with a 409) and the ceremony read must carry the
 * case's own signer party ids — a fixed stub could never match either.
 */
class KybE2EWireMockResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        val wm = WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        server = wm
        wm.start()
        stubTokenEndpoint(wm)
        stubAresSubjects(wm)
        stubAresVr(wm)
        stubAresSearch(wm)
        stubPartyService(wm)

        val base = wm.baseUrl()
        return mapOf(
            // Build-time flag read at augmentation: the resource config (high ordinal, applied
            // before the app boots) is what makes the reactive OIDC filter build step and the
            // runtime token producers active — the %test yaml alone is not consulted there.
            "quarkus.oidc-client.enabled" to "true",
            "quarkus.oidc-client.auth-server-url" to base,
            "quarkus.oidc-client.discovery-enabled" to "false",
            "quarkus.oidc-client.token-path" to TOKEN_PATH,
            "quarkus.oidc-client.client-id" to "openbank-services",
            "quarkus.oidc-client.credentials.secret" to "test-secret",
            "quarkus.oidc-client.grant.type" to "client",
            "quarkus.oidc-client.m2m.auth-server-url" to base,
            "quarkus.oidc-client.m2m.discovery-enabled" to "false",
            "quarkus.oidc-client.m2m.token-path" to TOKEN_PATH,
            "quarkus.oidc-client.m2m.client-id" to "openbank-kyb",
            "quarkus.oidc-client.m2m.credentials.secret" to "test-secret",
            "quarkus.oidc-client.m2m.grant.type" to "client",
            "quarkus.rest-client.ares.url" to base,
            "quarkus.rest-client.party-service.url" to base,
            "quarkus.rest-client.document-service.url" to base,
        )
    }

    override fun stop() {
        server?.stop()
        server = null
    }

    /** Keycloak token endpoint, one answer per asking client. */
    private fun stubTokenEndpoint(wm: WireMockServer) {
        // Quarkus' oidc-client may authenticate with client_id in the form body OR with HTTP
        // Basic (client_id:secret), so both carriers are stubbed; the party client must ride the
        // `m2m` client (openbank-kyb), the document client the shared default
        // (openbank-services) — #10486 pinned on the wire.
        wm.stubFor(
            post(urlEqualTo(TOKEN_PATH)).atPriority(1).withRequestBody(containing("client_id=openbank-kyb"))
                .willReturn(
                    okJson("""{"access_token":"kyb-e2e-m2m-token","token_type":"Bearer","expires_in":300}"""),
                ),
        )
        wm.stubFor(
            post(urlEqualTo(TOKEN_PATH)).atPriority(1)
                .withHeader("Authorization", equalTo("Basic b3BlbmJhbmsta3liOnRlc3Qtc2VjcmV0"))
                .willReturn(
                    okJson("""{"access_token":"kyb-e2e-m2m-token","token_type":"Bearer","expires_in":300}"""),
                ),
        )
        wm.stubFor(
            post(urlEqualTo(TOKEN_PATH)).atPriority(2)
                .withHeader("Authorization", equalTo("Basic b3BlbmJhbmstc2VydmljZXM6dGVzdC1zZWNyZXQ="))
                .willReturn(
                    okJson("""{"access_token":"kyb-e2e-default-token","token_type":"Bearer","expires_in":300}"""),
                ),
        )
        wm.stubFor(
            post(urlEqualTo(TOKEN_PATH)).willReturn(
                okJson("""{"access_token":"kyb-e2e-default-token","token_type":"Bearer","expires_in":300}"""),
            ),
        )
    }

    /** ARES economic subjects: one sole trader and one jointly represented s.r.o. */
    private fun stubAresSubjects(wm: WireMockServer) {
        wm.stubFor(get(urlEqualTo("/ekonomicke-subjekty/26185610")).willReturn(okJson(ARES_SOLE_TRADER)))
        wm.stubFor(get(urlEqualTo("/ekonomicke-subjekty/45274649")).willReturn(okJson(ARES_SRO)))
        // Unknown identifier — ARES answers 404 and the register lookup maps it to "no record".
        wm.stubFor(get(urlEqualTo("/ekonomicke-subjekty/00000019")).willReturn(notFound()))
        // Register outage — every lookup answers 500 so the @Retry fan-out is journal-provable.
        wm.stubFor(get(urlEqualTo("/ekonomicke-subjekty/76543218")).willReturn(serverError()))
    }

    /** ARES public register (VR): the s.r.o.'s record with two joint jednatelé — the source the
     *  required-signatures count and the mandate grant are derived from. */
    private fun stubAresVr(wm: WireMockServer) {
        wm.stubFor(get(urlEqualTo("/ekonomicke-subjekty-vr/45274649")).willReturn(okJson(ARES_VR_SRO)))
    }

    /**
     * ARES name search; the over-limit answer is an ARES 400 error document (refine-the-query
     * outcome), registered at priority so its body matcher wins over the catch-all page.
     */
    private fun stubAresSearch(wm: WireMockServer) {
        wm.stubFor(
            post(urlEqualTo("/ekonomicke-subjekty/vyhledat"))
                .atPriority(1)
                .withRequestBody(containing("mnoho"))
                .willReturn(
                    aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody(ARES_SEARCH_TOO_MANY),
                ),
        )
        wm.stubFor(post(urlEqualTo("/ekonomicke-subjekty/vyhledat")).willReturn(okJson(ARES_SEARCH_PAGE)))
    }

    /** party-service: the entity party create, the identity read and the mandate grant. */
    private fun stubPartyService(wm: WireMockServer) {
        // The entity party create: a fresh UUID per call, the party id the case records.
        wm.stubFor(
            post(urlEqualTo("/api/v1/parties")).willReturn(
                aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                    .withBody("""{"id":"${java.util.UUID.randomUUID()}"}"""),
            ),
        )
        // Who an initiator verifiably is: approved KYC, non-PEP, matching the sole trader's name.
        wm.stubFor(
            get(urlPathMatching("/api/v1/parties/[0-9a-f-]{36}")).willReturn(okJson(PARTY_IDENTITY)),
        )
        wm.stubFor(
            post(urlPathMatching("/api/v1/parties/[0-9a-f-]{36}/mandates"))
                .willReturn(aResponse().withStatus(201)),
        )
    }

    companion object {
        private const val TOKEN_PATH = "/realms/openbank/protocol/openid-connect/token"

        private const val ARES_SOLE_TRADER = """{
            "obchodniJmeno": "Jan Novák",
            "pravniForma": "100",
            "datumVzniku": "2015-06-01",
            "sidlo": {
              "textovaAdresa": "Rybná 6, Praha",
              "nazevObce": "Praha",
              "psc": "11000",
              "kodStatu": "CZ"
            }
        }"""

        private const val ARES_SRO = """{
            "obchodniJmeno": "Příklad s.r.o.",
            "pravniForma": "112",
            "datumVzniku": "2020-01-15",
            "dic": "CZ45274649",
            "sidlo": {
              "textovaAdresa": "Václavské náměstí 1, Praha",
              "nazevObce": "Praha",
              "psc": "11000",
              "kodStatu": "CZ"
            }
        }"""

        private const val ARES_VR_SRO = """{
            "zaznamy": [
              {
                "primarniZaznam": true,
                "spisovaZnacka": [{"soud": "Městský soud v Praze", "oddil": "C", "vlozka": "12345"}],
                "statutarniOrgany": [
                  {
                    "nazevOrganu": "Jednatelé",
                    "zpusobJednani": [
                      {"hodnota": "dva jednatelé společně", "datumZapisu": "2020-01-20"}
                    ],
                    "clenoveOrganu": [
                      {
                        "datumZapisu": "2020-01-20",
                        "fyzickaOsoba": {
                          "jmeno": "Jana",
                          "prijmeni": "Nováková",
                          "datumNarozeni": "1980-03-04",
                          "adresa": {
                            "textovaAdresa": "Václavské náměstí 1, Praha",
                            "nazevObce": "Praha",
                            "psc": "11000",
                            "kodStatu": "CZ"
                          }
                        },
                        "clenstvi": {
                          "funkce": {"nazev": "jednatel"},
                          "clenstvi": {"vznikClenstvi": "2020-01-20"}
                        }
                      },
                      {
                        "datumZapisu": "2020-01-20",
                        "fyzickaOsoba": {
                          "jmeno": "Eva",
                          "prijmeni": "Dvořáková",
                          "datumNarozeni": "1975-09-12"
                        },
                        "clenstvi": {
                          "funkce": {"nazev": "jednatel"},
                          "clenstvi": {"vznikClenstvi": "2020-01-20"}
                        }
                      }
                    ]
                  }
                ]
              }
            ]
        }"""

        private const val ARES_SEARCH_TOO_MANY = """{"kod":"CHYBA_VSTUPU","subKod":"VYSTUP_PRILIS_MNOHO_VYSLEDKU",
            "popis":"Vstupní parametr vyhledávání vrací příliš mnoho výsledků (2 818)."}"""

        private const val ARES_SEARCH_PAGE = """{
            "pocetCelkem": 2,
            "ekonomickeSubjekty": [
              {
                "ico": "45274649",
                "obchodniJmeno": "Příklad s.r.o.",
                "pravniForma": "112",
                "sidlo": {"textovaAdresa": "Václavské náměstí 1, Praha"}
              },
              {
                "ico": "27182819",
                "obchodniJmeno": "Druhá stavby a.s.",
                "pravniForma": "121",
                "sidlo": {"textovaAdresa": "Kolmá 9, Brno"}
              }
            ]
        }"""

        private const val PARTY_IDENTITY = """{
            "legalName": "Jan Novák",
            "kycStatus": "APPROVED",
            "pepFlag": false,
            "address": {"line1": "Rybná 6", "city": "Praha", "postalCode": "11000", "countryCode": "CZ"}
        }"""

        private var server: WireMockServer? = null

        private fun signerEntry(partyRef: String, status: String) =
            "{\"partyRef\":\"$partyRef\",\"status\":\"$status\",\"signedAt\":null}"

        /** The started server, for per-case ceremony stubs and journal assertions. */
        fun wm(): WireMockServer = server ?: error("KybE2EWireMockResource not started")

        /**
         * Serves `POST /api/v1/business-agreements` for one case: the ensure call's response must
         * echo the case's own id (requireOwnCeremony refuses a foreign ceremony with a 409), so
         * the stub is scoped by the request body and registered per case.
         */
        fun stubAgreementEnsure(caseId: String) {
            wm().stubFor(
                post(urlEqualTo("/api/v1/business-agreements"))
                    .withRequestBody(containing("\"caseId\":\"$caseId\""))
                    .willReturn(
                        okJson(
                            """{
                              "caseId": "$caseId",
                              "documentId": "11111111-1111-1111-1111-111111111111",
                              "templateCode": "RAMCOVA_SMLOUVA_PO_CS",
                              "templateVersion": "1.0.0",
                              "sha256": "${"a".repeat(64)}",
                              "sealedSha256": null,
                              "ceremonyId": "22222222-2222-2222-2222-222222222222",
                              "ceremonyStatus": "PENDING",
                              "signers": [],
                              "disclosures": [
                                {"code": "VOP_CS", "version": "1.1.0", "title": "Všeobecné obchodní podmínky",
                                 "sha256": "${"b".repeat(64)}", "documentId": null},
                                {"code": "SAZEBNIK_PO_CS", "version": "1.0.0", "title": "Sazebník",
                                 "sha256": "${"c".repeat(64)}", "documentId": null}
                              ]
                            }""",
                        ),
                    ),
            )
        }

        /**
         * Serves `GET /api/v1/business-agreements/{caseId}?lang=cs` for one case: the ceremony the
         * case recorded, with the given signers in the given status. Re-stubbing replaces the
         * previous mapping, so the suite flips a signer PENDING → SIGNED exactly like
         * document-service would after the customer signs there.
         */
        fun stubCeremony(caseId: String, signers: List<Pair<String, String>>) {
            val signerJson = signers.joinToString(",") { (partyRef, status) -> signerEntry(partyRef, status) }
            wm().stubFor(
                get(urlPathEqualTo("/api/v1/business-agreements/$caseId"))
                    .withQueryParam("lang", equalTo("cs")).willReturn(
                        okJson(
                            """{
                              "caseId": "$caseId",
                              "documentId": "11111111-1111-1111-1111-111111111111",
                              "templateCode": "RAMCOVA_SMLOUVA_PO_CS",
                              "templateVersion": "1.0.0",
                              "sha256": "${"a".repeat(64)}",
                              "sealedSha256": null,
                              "ceremonyId": "22222222-2222-2222-2222-222222222222",
                              "ceremonyStatus": "PENDING",
                              "signers": [$signerJson],
                              "disclosures": [
                                {"code": "VOP_CS", "version": "1.1.0", "title": "Všeobecné obchodní podmínky",
                                 "sha256": "${"b".repeat(64)}", "documentId": null},
                                {"code": "SAZEBNIK_PO_CS", "version": "1.0.0", "title": "Sazebník",
                                 "sha256": "${"c".repeat(64)}", "documentId": null}
                              ]
                            }""",
                        ),
                    ),
            )
        }

        /** Journal size for a matcher — the fan-out / zero-fan-out proof. */
        fun requestsTo(requestPattern: RequestPatternBuilder): Int = wm().findAll(requestPattern).count()

        fun resetJournal() {
            wm().resetRequests()
        }
    }
}
