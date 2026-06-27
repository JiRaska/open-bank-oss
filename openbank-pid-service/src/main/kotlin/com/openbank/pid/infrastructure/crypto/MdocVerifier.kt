// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.crypto

import com.openbank.pid.application.port.out.MdocVerifierPort
import com.openbank.pid.application.port.out.PidVerificationException
import com.openbank.pid.domain.model.PidClaims
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import org.jose4j.base64url.Base64Url
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Verifies an ISO/IEC 18013-5 **mdoc** PID credential (the CBOR/COSE format, eIDAS 2.0 ARF, ADR-0094)
 * — the alternative to SD-JWT VC. Returns the same verified [PidClaims] so it plugs into the existing
 * tier-0 resolve flow.
 *
 * Input: a base64url `IssuerSigned` CBOR map `{nameSpaces, issuerAuth}`. Verification, fail-closed:
 *  1. `issuerAuth` is a COSE_Sign1 `[protected, unprotected, payload, signature]`; verify the ES256
 *     signature over the COSE `Sig_structure` against a trusted issuer key ([TrustedIssuerKeys] — mdoc
 *     carries no `iss`, so any trusted key that verifies establishes trust; the alg is pinned ES256).
 *  2. the signed payload is the Mobile Security Object (MSO); check `validityInfo.validUntil`.
 *  3. each disclosed `IssuerSignedItem`'s SHA-256 digest must match `valueDigests` in the MSO
 *     (tamper-evidence — a forged/added element is rejected).
 *  4. extract the PID data elements (given_name, family_name, birth_date) → [PidClaims].
 *
 * Out of scope (production follow-ups): device authentication (DeviceSigned/SessionTranscript),
 * x5chain → CA path validation, and CBOR digestAlgorithm other than SHA-256.
 */
@ApplicationScoped
@Suppress(
    // A fail-closed CBOR/COSE verifier is inherently guard-heavy: each check is a distinct throw and the
    // disclosure-binding loop drops every unbound element; CBOR field access is index/key driven.
    "ThrowsCount",
    "SwallowedException",
    "LoopWithTooManyJumpStatements",
    "CyclomaticComplexMethod",
    "MagicNumber",
)
class MdocVerifier(private val trustedKeys: TrustedIssuerKeys, private val clock: Clock) : MdocVerifierPort {

    override fun verify(mdocBase64Url: String): PidClaims {
        val bytes = runCatching { Base64Url.decode(mdocBase64Url.trim()) }
            .getOrElse { throw PidVerificationException("mdoc not base64url") }
        val issuerSigned = runCatching { CBORObject.DecodeFromBytes(bytes) }
            .getOrElse { throw PidVerificationException("mdoc not valid CBOR") }

        val issuerAuth = issuerSigned[KEY_ISSUER_AUTH]
            ?: throw PidVerificationException("mdoc missing issuerAuth")
        val mso = verifyIssuerAuth(issuerAuth)
        verifyValidity(mso)

        val valueDigests = mso[KEY_VALUE_DIGESTS] ?: throw PidVerificationException("MSO has no valueDigests")
        val digestAlg = mso[KEY_DIGEST_ALG]?.AsString()
        if (digestAlg != null && digestAlg != DIGEST_SHA256) {
            throw PidVerificationException("unsupported mdoc digestAlgorithm: $digestAlg")
        }
        val elements = bindAndExtract(issuerSigned[KEY_NAMESPACES], valueDigests)
        return toPidClaims(elements)
    }

    /** COSE_Sign1 signature over the Sig_structure, against any trusted key (ES256). Returns the MSO. */
    private fun verifyIssuerAuth(issuerAuth: CBORObject): CBORObject {
        if (issuerAuth.type != CBORType.Array || issuerAuth.size() != COSE_SIGN1_LEN) {
            throw PidVerificationException("issuerAuth is not a COSE_Sign1")
        }
        // A wrong CBOR type here (e.g. a non-bstr where a byte string is required) must surface as a
        // verification failure (422), not an uncaught CBORException (500).
        val (protectedBytes, payloadBytes, signature) = runCatching {
            Triple(issuerAuth[0].GetByteString(), issuerAuth[2].GetByteString(), issuerAuth[3].GetByteString())
        }.getOrElse { throw PidVerificationException("issuerAuth elements have the wrong CBOR type") }

        val alg = runCatching { CBORObject.DecodeFromBytes(protectedBytes)[COSE_HEADER_ALG]?.AsInt32() }.getOrNull()
        if (alg != COSE_ALG_ES256) throw PidVerificationException("mdoc issuerAuth alg is not ES256")

        val sigStructure = CBORObject.NewArray().apply {
            Add("Signature1")
            Add(protectedBytes)
            Add(ByteArray(0)) // external_aad
            Add(payloadBytes)
        }.EncodeToBytes()

        if (trustedKeys.allTrustedKeys().none { verifyEs256(it, sigStructure, signature) }) {
            throw PidVerificationException("mdoc issuer signature not trusted")
        }
        // payloadBytes = #6.24(bstr .cbor MSO) — untag, then decode the inner MSO.
        val msoBytes = runCatching { CBORObject.DecodeFromBytes(payloadBytes).Untag().GetByteString() }
            .getOrElse { throw PidVerificationException("MSO payload malformed") }
        return runCatching { CBORObject.DecodeFromBytes(msoBytes) }
            .getOrElse { throw PidVerificationException("MSO not valid CBOR") }
    }

    private fun verifyEs256(key: PublicKey, signed: ByteArray, rawSig: ByteArray): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(key)
            update(signed)
            verify(rawSig)
        }
    }.getOrDefault(false)

    private fun verifyValidity(mso: CBORObject) {
        // validityInfo + validUntil are MANDATORY (ISO 18013-5): a credential with no expiry would be
        // trusted forever — the mdoc analog of the SD-JWT exp requirement.
        val validity = mso[KEY_VALIDITY_INFO] ?: throw PidVerificationException("MSO missing validityInfo")
        val until = validity[KEY_VALID_UNTIL]?.AsString()?.let {
            runCatching {
                Instant.parse(it)
            }.getOrElse { throw PidVerificationException("MSO validUntil not parseable") }
        } ?: throw PidVerificationException("MSO missing validUntil")
        if (Instant.now(clock).isAfter(until.plusSeconds(CLOCK_SKEW_SECONDS))) {
            throw PidVerificationException("mdoc has expired")
        }
    }

    /**
     * For each disclosed item: its digest must be in valueDigests; return elementIdentifier -> value.
     *
     * NOTE (interop follow-up): the digest is recomputed over the RE-ENCODED item bytes. The CBOR codec
     * emits canonical (definite-length, preferred) encoding, so this matches an issuer that also produced
     * canonical CBOR (as our fixtures + tests do). A real wallet sending non-canonical CBOR would need the
     * ORIGINAL received bytes preserved for the digest (ISO 18013-5 §9.1.2) — tracked as a follow-up; it
     * fails closed (digest mismatch → element dropped), never widens trust.
     */
    private fun bindAndExtract(nameSpaces: CBORObject?, valueDigests: CBORObject): Map<String, CBORObject> {
        if (nameSpaces == null) return emptyMap()
        val sha = MessageDigest.getInstance("SHA-256")
        val out = HashMap<String, CBORObject>()
        for (ns in nameSpaces.keys) {
            val nsDigests = valueDigests[ns]
            for (taggedItem in nameSpaces[ns].values) {
                val digest = sha.digest(taggedItem.EncodeToBytes())
                val item = runCatching {
                    CBORObject.DecodeFromBytes(taggedItem.Untag().GetByteString())
                }.getOrNull() ?: continue
                val digestId = item[KEY_DIGEST_ID] ?: continue
                val expected = nsDigests?.get(digestId)?.GetByteString()
                if (expected == null || !expected.contentEquals(digest)) {
                    // Unbound / forged element — never trust it.
                    Log.warn("mdoc: dropping element not bound in valueDigests (possible forgery)")
                    continue
                }
                val name = item[KEY_ELEMENT_ID]?.AsString() ?: continue
                out[name] = item[KEY_ELEMENT_VALUE] ?: continue
            }
        }
        return out
    }

    private fun toPidClaims(elements: Map<String, CBORObject>): PidClaims {
        fun str(vararg names: String): String? =
            names.firstNotNullOfOrNull { elements[it]?.takeIf { v -> v.type == CBORType.TextString }?.AsString() }

        val given = str("given_name") ?: throw PidVerificationException("mdoc missing given_name")
        val family = str("family_name") ?: throw PidVerificationException("mdoc missing family_name")
        val birth = str("birth_date") ?: throw PidVerificationException("mdoc missing birth_date")
        val birthDate = runCatching { LocalDate.parse(birth) }
            .getOrElse { throw PidVerificationException("mdoc birth_date not ISO-8601: $birth") }
        // mdoc PID carries no globally-unique "sub"; use a stable administrative id when present so
        // tier-0 can still match, else a deterministic name+birthdate id (tier-1/2 resolve via name/DOB).
        val subjectRaw = str("personal_administrative_number", "document_number")
            ?.let { "mdoc:$it" } ?: "mdoc-pid:$family:$given:$birth"

        return PidClaims(
            subjectId = subjectRaw,
            givenName = given,
            familyName = family,
            birthDate = birthDate,
            birthPlace = str("birth_place", "place_of_birth"),
            nationalities = str("nationality")?.let { listOf(it) } ?: emptyList(),
            issuingCountry = str("issuing_country") ?: "",
            nationalIdentifier = str("personal_administrative_number"),
            issuer = "mdoc",
            levelOfAssurance = "HIGH",
        )
    }

    private companion object {
        val KEY_ISSUER_AUTH: CBORObject = CBORObject.FromObject("issuerAuth")
        val KEY_NAMESPACES: CBORObject = CBORObject.FromObject("nameSpaces")
        val KEY_VALUE_DIGESTS: CBORObject = CBORObject.FromObject("valueDigests")
        val KEY_DIGEST_ALG: CBORObject = CBORObject.FromObject("digestAlgorithm")
        val KEY_VALIDITY_INFO: CBORObject = CBORObject.FromObject("validityInfo")
        val KEY_VALID_UNTIL: CBORObject = CBORObject.FromObject("validUntil")
        val KEY_DIGEST_ID: CBORObject = CBORObject.FromObject("digestID")
        val KEY_ELEMENT_ID: CBORObject = CBORObject.FromObject("elementIdentifier")
        val KEY_ELEMENT_VALUE: CBORObject = CBORObject.FromObject("elementValue")
        val COSE_HEADER_ALG: CBORObject = CBORObject.FromObject(1)
        const val COSE_SIGN1_LEN = 4
        const val COSE_ALG_ES256 = -7
        const val DIGEST_SHA256 = "SHA-256"
        const val CLOCK_SKEW_SECONDS = 120L
    }
}
