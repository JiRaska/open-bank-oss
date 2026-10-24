// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.trace

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Gives a trace-contract IT its own Quarkus application instance, and therefore its own
 * [RecordingSpanExporter].
 *
 * Isolation is the point, not the settings: the recorder is an application-scoped bean, so without
 * a distinct profile every other test class in this module would deposit its spans in the same
 * recorder and `hasNoErrorSpan()` would be a statement about the whole module's traffic rather
 * than about the one operation under test. The outbox dispatcher is switched off for the same
 * reason — its background sends are spans this test never asked about.
 *
 * A service whose boot needs more than this (`authz.enforce`, a scheduler, a fixed test port)
 * subclasses it and returns ONLY the extra keys from [extraConfigOverrides]; a service that has no
 * outbox sets [disableOutboxDispatcher] to `false`.
 *
 * Only literals here: a profile loads in a different classloader from the test class, so a
 * computed value would not be the value the application sees.
 */
open class TraceContractProfile : QuarkusTestProfile {

    /** Whether to pin `openbank.outbox.dispatch-enabled=false`; `false` for a service without an outbox. */
    protected open val disableOutboxDispatcher: Boolean = true

    /** Keys beyond the fleet baseline, e.g. `authz.enforce`. Literal values only. */
    protected open fun extraConfigOverrides(): Map<String, String> = emptyMap()

    final override fun getConfigOverrides(): Map<String, String> {
        val overrides = linkedMapOf(
            // Some modules switch the SDK off for tests; without it there are no spans to contract at all.
            "quarkus.otel.sdk.disabled" to "false",
            // TraceContractRecorder is a bean in this jar; ArC only indexes the jar when asked to.
            "quarkus.index-dependency.openbank-libs-testing.group-id" to "com.openbank",
            "quarkus.index-dependency.openbank-libs-testing.artifact-id" to "openbank-libs-testing",
        )
        if (disableOutboxDispatcher) overrides["openbank.outbox.dispatch-enabled"] = "false"
        overrides.putAll(extraConfigOverrides())
        return overrides
    }
}
