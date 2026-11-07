// SPDX-License-Identifier: AGPL-3.0-only
package com.openbank.communication.contract

import au.com.dius.pact.provider.junit5.PactVerificationContext
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver

/** Ordinary negative HTTP tests have no Pact interaction to configure in BeforeEach. */
class NoCommunicationPactContext : ParameterResolver {
    override fun supportsParameter(parameter: ParameterContext, context: ExtensionContext): Boolean =
        parameter.parameter.type == PactVerificationContext::class.java

    override fun resolveParameter(parameter: ParameterContext, context: ExtensionContext): Any? = null
}
