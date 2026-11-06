// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import org.junit.jupiter.api.extension.Extension
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestTemplateInvocationContext
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider
import java.util.stream.Stream

/** Preserve official Pact extensions while making each version/index visible in final XML. */
class FinrepUniqueInvocationProvider : TestTemplateInvocationContextProvider {
    private val delegate = PactVerificationInvocationContextProvider()

    override fun supportsTestTemplate(context: ExtensionContext): Boolean = delegate.supportsTestTemplate(context)

    override fun provideTestTemplateInvocationContexts(
        context: ExtensionContext,
    ): Stream<TestTemplateInvocationContext> =
        delegate.provideTestTemplateInvocationContexts(context).map { invocation ->
            object : TestTemplateInvocationContext {
                override fun getDisplayName(invocationIndex: Int): String =
                    if (context.requiredTestClass.simpleName == "LedgerControlledFinrepOrderProviderVerificationTest") {
                        ControlledFinrepOrderResults.displayName(invocationIndex - 1)
                    } else {
                        val replay = HostedFinrepRun.replay
                        val index = invocationIndex - 1
                        "${replay.consumerSha}:$index:${replay.pact.interactions[index].description}"
                    }

                override fun getAdditionalExtensions(): List<Extension> = invocation.additionalExtensions
            }
        }
}
