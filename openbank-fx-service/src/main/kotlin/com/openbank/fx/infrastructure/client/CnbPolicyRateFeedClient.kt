// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import com.openbank.libs.web.SyntheticTaintExternalBoundary
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET

/**
 * One ČNB policy-rate history file (`vyvoj_*_historie.txt`). Built programmatically per instrument
 * by [CnbPolicyRateFeedAdapter], because each file is its own URL under
 * `openbank.cnb.policy-rates.*-url` — the full URL stays in application.yaml, where
 * `check-external-feeds.py` reads and probes it. The body is read as BYTES: the file is UTF-8 with
 * a BOM and is hashed for provenance before it is decoded.
 */
@SyntheticTaintExternalBoundary("public Czech National Bank policy-rate history files are outside OpenBank")
interface CnbPolicyRateFeedClient {
    @GET
    fun fetch(): Uni<ByteArray>
}
