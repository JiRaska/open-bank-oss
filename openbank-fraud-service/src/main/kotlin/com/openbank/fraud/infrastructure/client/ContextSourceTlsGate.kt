// SPDX-License-Identifier: Apache-2.0
package com.openbank.fraud.infrastructure.client

import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.ConfigProvider
import java.net.URI

/** The case-access check must never downgrade to a plaintext service endpoint. */
@Startup
@ApplicationScoped
@Suppress("UtilityClassWithPublicConstructor")
class ContextSourceTlsGate {
    init {
        if (LaunchMode.current() == LaunchMode.NORMAL) {
            require(secureContextSourceUrl(ConfigProvider.getConfig().getValue(URL_PROPERTY, String::class.java))) {
                "Context access requires an HTTPS endpoint"
            }
        }
    }

    private companion object {
        const val URL_PROPERTY = "quarkus.rest-client.context-service.url"
    }
}

internal fun secureContextSourceUrl(value: String): Boolean = runCatching {
    URI(value).let {
        it.scheme == "https" &&
            it.host != null &&
            it.userInfo == null &&
            it.rawQuery == null &&
            it.rawFragment == null
    }
}.getOrDefault(false)
