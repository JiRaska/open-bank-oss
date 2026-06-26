// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.analytics.infrastructure.support

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Concrete [GenericContainer] subtype so Kotlin can resolve Testcontainers' self-recursive `SELF` type
 * parameter — the builder methods (`withEnv`, `withExposedPorts`, …) then return this exact type and
 * chain cleanly, instead of collapsing to a star-projection that won't compile.
 */
class KGenericContainer(image: DockerImageName) : GenericContainer<KGenericContainer>(image) {
    constructor(image: String) : this(DockerImageName.parse(image))
}
