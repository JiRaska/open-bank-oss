// SPDX-License-Identifier: Apache-2.0
package com.openbank.libs.testing.evidence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.ClassDescriptor
import org.junit.jupiter.api.ClassOrdererContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Optional

class HistoryClassOrdererTest {
    @TempDir
    lateinit var directory: Path

    private val now = Instant.parse("2026-10-25T12:00:00Z")
    private val component = "openbank-context-service"

    @Test
    fun `ranked classes move first while every descriptor and unknown class is preserved`() {
        val descriptors = mutableListOf(
            descriptor(String::class.java),
            descriptor(Long::class.javaObjectType),
            descriptor(Int::class.javaObjectType),
        )
        val original = descriptors.toList()
        val priorities = read(manifest(classes = "\"java.lang.String\",\"example.AbsentTest\""))

        HistoryClassOrderer { priorities }.orderClasses(context(descriptors))

        assertThat(descriptors).containsExactly(original[0], original[2], original[1])
        assertThat(descriptors).containsExactlyInAnyOrderElementsOf(original)
    }

    @Test
    fun `unavailable history falls back to deterministic class names`() {
        val descriptors = mutableListOf(
            descriptor(String::class.java),
            descriptor(Long::class.javaObjectType),
            descriptor(Int::class.javaObjectType),
        )
        HistoryClassOrderer { read("invalid JSON") }.orderClasses(context(descriptors))
        assertThat(descriptors.map { it.testClass.name })
            .containsExactly("java.lang.Integer", "java.lang.Long", "java.lang.String")
    }

    @Test
    fun `valid manifest keeps the supplied ranking and accepts freshness boundaries`() {
        assertThat(read(manifest(classes = "\"example.ZTest\",\"example.ATest\"")))
            .containsExactlyEntriesOf(linkedMapOf("example.ZTest" to 0, "example.ATest" to 1))
        for (timestamp in listOf(now.minus(Duration.ofDays(7)), now.plus(Duration.ofMinutes(5)))) {
            assertThat(read(manifest(generatedAt = timestamp))).containsEntry("example.Test", 0)
        }
    }

    @Test
    fun `stale future wrong component duplicate classes and malformed manifests are ignored`() {
        val invalid = listOf(
            manifest(generatedAt = now.minus(Duration.ofDays(7)).minusSeconds(1)),
            manifest(generatedAt = now.plus(Duration.ofMinutes(5)).plusSeconds(1)),
            manifest().replace(component, "openbank-other-service"),
            manifest(classes = "\"example.Test\",\"example.Test\""),
            manifest(classes = "\"../example.Test\""),
            manifest(classes = "7"),
            manifest().replace("\"schemaVersion\":1", "\"schemaVersion\":true"),
            manifest().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            manifest().replace(now.toString(), "yesterday"),
            manifest().replace("\"classes\":[\"example.Test\"]", "\"classes\":null"),
            manifest() + " {}",
            "{}",
            "null",
        )
        for (document in invalid) assertThat(read(document)).describedAs(document).isEmpty()
    }

    @Test
    fun `missing files absent identity excessive size and excessive classes are ignored`() {
        assertThat(HistoryClassOrderer.readPriorities(null, component, now)).isEmpty()
        val missing = directory.resolve("missing.json").toString()
        assertThat(HistoryClassOrderer.readPriorities(missing, component, now)).isEmpty()
        val path = directory.resolve("history.json")
        Files.writeString(path, manifest())
        assertThat(HistoryClassOrderer.readPriorities(path.toString(), null, now)).isEmpty()
        assertThat(read(" ".repeat(256 * 1024 + 1))).isEmpty()
        assertThat(read(manifest(classes = (0..2000).joinToString(",") { "\"example.Test$it\"" }))).isEmpty()
    }

    private fun read(document: String): Map<String, Int> {
        val path = directory.resolve("history.json")
        Files.writeString(path, document)
        return HistoryClassOrderer.readPriorities(path.toString(), component, now)
    }

    private fun manifest(generatedAt: Instant = now, classes: String = "\"example.Test\""): String =
        """{"schemaVersion":1,"component":"$component","generatedAt":"$generatedAt","classes":[$classes]}"""

    private fun descriptor(type: Class<*>): ClassDescriptor = Proxy.newProxyInstance(
        ClassDescriptor::class.java.classLoader,
        arrayOf(ClassDescriptor::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getTestClass" -> type
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> type.name
            else -> error("Unexpected descriptor method ${method.name}")
        }
    } as ClassDescriptor

    private fun context(descriptors: MutableList<ClassDescriptor>): ClassOrdererContext = object : ClassOrdererContext {
        override fun getClassDescriptors(): MutableList<out ClassDescriptor> = descriptors
        override fun getConfigurationParameter(key: String): Optional<String> = Optional.empty()
    }
}
