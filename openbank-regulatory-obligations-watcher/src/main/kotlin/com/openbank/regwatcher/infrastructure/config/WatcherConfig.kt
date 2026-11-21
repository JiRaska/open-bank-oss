// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher.infrastructure.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration
import java.util.Optional

/**
 * `openbank.regulatory-watcher.*`. Every secret is `Optional`: an unseeded value degrades the
 * agent to "reads nothing / files nothing" (each adapter fails closed and says so) instead of
 * CrashLooping the pod at boot (SRCFG00040 / SRCFG00014).
 */
@Suppress("TooManyFunctions") // a config mapping: one accessor per key, not behaviour
@ConfigMapping(prefix = "openbank.regulatory-watcher")
interface WatcherConfig {
    /** The charter id; also the `agent:<id>` subject the broker sees. */
    @WithDefault("regulatory-obligations-watcher")
    fun agentId(): String

    /** Kill switch (charter `limits.kill_switch`). false = every run is skipped. */
    @WithDefault("true")
    fun enabled(): Boolean

    fun checkCron(): String

    @WithDefault("60000")
    fun tokensPerRun(): Int

    @WithDefault("1")
    fun runsPerDay(): Int

    /** Spam guard: at most this many NEW issues per run; the rest is deferred to the next run. */
    @WithDefault("5")
    fun maxIssuesPerRun(): Int

    fun register(): Register

    fun broker(): Broker

    fun identity(): Identity

    fun model(): Model

    fun github(): GitHub

    fun policy(): Policy

    interface Register {
        @WithDefault("governance-register/regulatory-obligations.yaml")
        fun resource(): String
    }

    interface Broker {
        fun mcpUrl(): String

        @WithDefault("PT60S")
        fun timeout(): Duration
    }

    interface Identity {
        fun tokenUrl(): String

        @WithDefault("openbank-regulatory-obligations-watcher")
        fun clientId(): String

        fun clientSecret(): Optional<String>
    }

    interface Model {
        fun id(): String

        fun gatewayUrl(): String

        fun apiKey(): Optional<String>

        /** Characters of page text sent to the model per call (the rest is cut and the cut is stated). */
        @WithDefault("24000")
        fun maxContentChars(): Int
    }

    interface GitHub {
        @WithDefault("https://api.github.com")
        fun apiUrl(): String

        @WithDefault("JiRaska/open-bank-oss")
        fun repo(): String

        fun token(): Optional<String>
    }

    interface Policy {
        @WithDefault("http://localhost:8181")
        fun opaUrl(): String

        @WithDefault("PT2S")
        fun timeout(): Duration
    }
}
