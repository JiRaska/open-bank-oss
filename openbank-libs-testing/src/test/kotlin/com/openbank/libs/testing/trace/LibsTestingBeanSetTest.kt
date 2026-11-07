// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.trace

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.AnnotatedElement

/**
 * Pins the CDI beans this jar contributes.
 *
 * [TraceContractProfile] puts the whole jar into the Quarkus bean index
 * (`quarkus.index-dependency`) for every trace IT, about twenty modules. Anything in the jar that
 * defines a bean, produces one, observes an event or intercepts a call therefore joins ALL of
 * those applications, and nothing else would notice. Adding a bean here must be a conscious
 * decision made by editing [EXPECTED], not a side effect of writing a new test helper.
 */
class LibsTestingBeanSetTest {

    private val beanMarkers = setOf(
        "jakarta.enterprise.context.ApplicationScoped",
        "jakarta.enterprise.context.RequestScoped",
        "jakarta.enterprise.context.SessionScoped",
        "jakarta.enterprise.context.ConversationScoped",
        "jakarta.enterprise.context.Dependent",
        "jakarta.inject.Singleton",
        "jakarta.interceptor.Interceptor",
        "jakarta.decorator.Decorator",
        "jakarta.enterprise.inject.Produces",
        "jakarta.enterprise.event.Observes",
        "jakarta.enterprise.event.ObservesAsync",
        "io.quarkus.runtime.Startup",
        "io.quarkus.arc.Unremovable",
    )

    private fun marked(element: AnnotatedElement): Boolean =
        element.annotations.any { it.annotationClass.java.name in beanMarkers }

    private fun definesBean(type: Class<*>): Boolean = marked(type) ||
        type.declaredMethods.any { m -> marked(m) || m.parameters.any { marked(it) } } ||
        type.declaredFields.any { marked(it) } ||
        type.declaredConstructors.any { c -> marked(c) || c.parameters.any { marked(it) } }

    @Test
    fun `the only bean this jar defines is the trace recorder`() {
        val root = File(TraceContractRecorder::class.java.protectionDomain.codeSource.location.toURI())
        assertThat(root).describedAs("main classes directory").isDirectory
        val loader = TraceContractRecorder::class.java.classLoader
        val found = root.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .filter { !it.endsWith("package-info") && !it.endsWith("module-info") }
            .map { Class.forName(it, false, loader) }
            .filter { definesBean(it) }
            .map { it.name }
            .toSortedSet()
        assertThat(found).describedAs("classes in openbank-libs-testing that define or produce CDI beans")
            .containsExactlyElementsOf(EXPECTED)
    }

    @Test
    fun `the scan can see a bean at all`() {
        // Negative control: a scan that finds nothing would also pass the test above.
        assertThat(definesBean(TraceContractRecorder::class.java)).isTrue()
        assertThat(definesBean(LibsTestingBeanSetTest::class.java)).isFalse()
    }

    private companion object {
        val EXPECTED = listOf("com.openbank.libs.testing.trace.TraceContractRecorder")
    }
}
