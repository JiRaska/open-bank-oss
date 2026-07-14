// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * REST contract for the four WebAuthn RP endpoints (ADR-0066 F2, variant B1). Field names are
 * dictated by the app's client-side models (`openbank-app` shared `WebAuthnApi.kt`, kotlinx.serialization) —
 * they must match exactly, including the snake_case fields, or the app fails to (de)serialize.
 */
data class RegistrationChallengeDto(
    val challenge: String, // base64url random bytes
    val rpId: String,
    val userId: String, // base64url
    val userName: String,
    val displayName: String,
)

data class RegistrationCompleteRequestDto(
    @JsonProperty("credential_id") val credentialId: String, // base64url
    @JsonProperty("attestation_object") val attestationObject: String, // base64url
    @JsonProperty("client_data_json") val clientDataJson: String, // base64url
)

data class AuthenticationChallengeDto(
    val challenge: String, // base64url random bytes
    val rpId: String,
    val allowCredentials: List<String> = emptyList(),
)

data class AuthCompleteRequestDto(
    @JsonProperty("credential_id") val credentialId: String, // base64url
    @JsonProperty("authenticator_data") val authenticatorData: String, // base64url
    @JsonProperty("client_data_json") val clientDataJson: String, // base64url
    val signature: String, // base64url
    @JsonProperty("user_handle") val userHandle: String, // base64url
)

data class TokenPairDto(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("refresh_token") val refreshToken: String?,
)
