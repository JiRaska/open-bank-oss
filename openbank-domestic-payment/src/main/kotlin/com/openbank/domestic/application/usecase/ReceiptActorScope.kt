// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

internal object ReceiptActorScope {
    fun hash(scope: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(StandardCharsets.UTF_8)),
    )
}
