// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.security

import com.openbank.fraud.application.port.out.CounterpartyKeyPort
import com.openbank.libs.fincrime.CounterpartyAccount
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Optional
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA-256 counterparty keying (ADR-0377 D3). A plain hash of an IBAN can be brute-forced, so
 * the pseudonym is keyed with a secret only this service holds; the key id is part of the value so
 * a later rotation can read under both keys during a dual-key window.
 *
 * With no key configured this returns null and the payee rule keeps its pre-D3 behaviour. `@Startup`
 * so the warning appears at boot, not on the first request (an `@ApplicationScoped` bean is lazy).
 */
@Startup
@ApplicationScoped
class HmacCounterpartyKeyer(
    @ConfigProperty(name = "openbank.fraud.counterparty-hmac.key") key: Optional<String>,
    @ConfigProperty(name = "openbank.fraud.counterparty-hmac.key-id") keyId: Optional<String>,
) : CounterpartyKeyPort {

    private val keyIdValue: String? = keyId.orElse(null)?.trim()?.takeIf { it.matches(KEY_ID) }
    private val secret: SecretKeySpec? = key.orElse(null)?.takeIf { it.isNotBlank() && keyIdValue != null }
        ?.let { SecretKeySpec(it.toByteArray(Charsets.UTF_8), ALGORITHM) }

    init {
        if (secret == null) {
            Logger.getLogger(HmacCounterpartyKeyer::class.java).warn(
                "openbank.fraud.counterparty-hmac.key/key-id not configured (or key-id invalid): counterparty " +
                    "identifiers are NOT keyed and the new-payee rule cannot use them " +
                    "(metric openbank.fraud.counterparty.key.missing counts each such request)",
            )
        }
    }

    override fun key(account: CounterpartyAccount): String? {
        val spec = secret ?: return null
        val mac = Mac.getInstance(ALGORITHM).apply { init(spec) }
        val digest = mac.doFinal("${account.scheme.name}:${account.value}".toByteArray(Charsets.UTF_8))
        return "$PREFIX$keyIdValue:" + digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PREFIX = "cp2:"
        private const val ALGORITHM = "HmacSHA256"
        private val KEY_ID = Regex("^[A-Za-z0-9_-]{1,32}$")
    }
}
