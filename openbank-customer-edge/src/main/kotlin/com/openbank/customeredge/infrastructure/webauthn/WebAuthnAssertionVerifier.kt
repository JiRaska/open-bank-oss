// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionsAuthenticatorOutputs
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.util.Base64UrlUtil
import java.util.Base64

internal object WebAuthnAssertionVerifier {
    private val converter = ObjectConverter()
    private val attestedData = AttestedCredentialDataConverter(converter)
    private val manager = WebAuthnManager.createNonStrictWebAuthnManager(converter)

    fun verify(
        request: AuthCompleteRequestDto,
        credential: RegisteredCredential,
        challenge: String,
        rpId: String,
        origin: String,
    ): Long {
        val record = CredentialRecordImpl(
            NoneAttestationStatement(),
            null,
            null,
            null,
            credential.signCount,
            attestedData.convert(Base64.getDecoder().decode(credential.attestedCredentialDataB64)),
            AuthenticationExtensionsAuthenticatorOutputs(),
            null,
            null,
            null,
        )
        manager.verify(
            AuthenticationRequest(
                Base64UrlUtil.decode(request.credentialId),
                Base64UrlUtil.decode(request.userHandle),
                Base64UrlUtil.decode(request.authenticatorData),
                Base64UrlUtil.decode(request.clientDataJson),
                Base64UrlUtil.decode(request.signature),
            ),
            AuthenticationParameters(
                ServerProperty(Origin.create(origin), rpId, DefaultChallenge(Base64UrlUtil.decode(challenge))),
                record,
                null,
                true,
                true,
            ),
        )
        return record.counter
    }
}
