// SPDX-License-Identifier: Apache-2.0
import com.openbank.buildlogic.buildFactsInputs
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TestProvenanceTest {
    @TempDir
    lateinit var temporary: Path
    private val first = "1".repeat(40)
    private val second = "2".repeat(40)

    private fun metadata(commit: String, version: String = "1.0") =
        "| Release version | `$version` |\n| Source commit | `$commit` |\n| API version | `1` |\n"

    @Test
    fun `only a valid commit is normalized and raw artifact inputs retain it`() {
        val directory = temporary.resolve("resources").toFile().apply { mkdirs() }
        val facts = directory.resolve("docs/00-build.md").apply { parentFile.mkdirs() }
        facts.writeText(metadata(first))
        val properties = directory.resolve("openbank-service-build.properties")
        properties.writeText("git.commit=$first\n")
        val normalized = buildFactsInputs(listOf(directory), true)
        val raw = buildFactsInputs(listOf(directory), false)
        facts.writeText(metadata(second))
        properties.writeText("git.commit=$second\n")
        assertEquals(normalized, buildFactsInputs(listOf(directory), true))
        assertNotEquals(raw, buildFactsInputs(listOf(directory), false))
        facts.writeText(metadata(second, "2.0"))
        assertNotEquals(normalized, buildFactsInputs(listOf(directory), true))
        facts.writeText(metadata("malformed"))
        assertNotEquals(normalized, buildFactsInputs(listOf(directory), true))
        facts.writeText(metadata(second))
        properties.appendText("runtime.option=changed\n")
        assertNotEquals(normalized, buildFactsInputs(listOf(directory), true))
        facts.writeText(metadata(second) + metadata(first))
        val duplicate = buildFactsInputs(listOf(directory), true)
        facts.writeText(metadata(second) + metadata(second))
        assertNotEquals(duplicate, buildFactsInputs(listOf(directory), true))
        properties.writeBytes(byteArrayOf(0xc0.toByte()))
        val invalidBytes = buildFactsInputs(listOf(directory), true)
        properties.writeBytes(byteArrayOf(0xc1.toByte()))
        assertNotEquals(invalidBytes, buildFactsInputs(listOf(directory), true))
        properties.delete()
        assertNotEquals(normalized, buildFactsInputs(listOf(directory), true))
    }

    @Test
    fun `real test cache preserves changes in code docs versions and dependency metadata`() {
        val project = temporary.resolve("fixture").toFile().apply { mkdirs() }
        project.resolve("settings.gradle").writeText("""
            rootProject.name = 'cache-probe'
            buildCache { local { directory = file('isolated-cache') } }
        """.trimIndent())
        val junitJars = System.getProperty("fixture.junit.classpath").split(java.io.File.pathSeparator)
            .joinToString(", ") { "'" + it.replace("\\", "\\\\").replace("'", "\\'") + "'" }
        project.resolve("build.gradle").writeText("""
            plugins { id 'java'; id 'openbank.test-provenance' }
            dependencies {
                testImplementation files($junitJars)
                testRuntimeOnly files('dependency.jar', 'dependency.zip')
            }
            test {
                useJUnitPlatform()
                if (providers.gradleProperty('fixtureBroker').isPresent()) {
                    systemProperty 'pactbroker.url', 'https://broker.example.invalid'
                }
            }
            tasks.register('providerPactTest', Test) {
                useJUnitPlatform()
                testClassesDirs = sourceSets.test.output.classesDirs
                classpath = sourceSets.test.runtimeClasspath
            }
        """.trimIndent())
        val resources = project.resolve("src/main/resources").apply { mkdirs() }
        val facts = resources.resolve("docs/00-build.md").apply { parentFile.mkdirs() }
        facts.writeText(metadata(first))
        resources.resolve("openbank-service-build.properties").writeText("git.commit=$first\n")
        val application = project.resolve("src/main/java/Application.java").apply { parentFile.mkdirs() }
        application.writeText("public class Application { public static int value() { return 7; } }")
        val source = project.resolve("src/test/java/CacheTest.java").apply { parentFile.mkdirs() }
        source.writeText("""
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            public class CacheTest {
                @Test void metadataIsActuallyReadable() throws Exception {
                    try (var input = getClass().getClassLoader().getResourceAsStream("openbank-service-build.properties")) {
                        assertNotNull(input);
                        System.out.println(new String(input.readAllBytes()));
                    }
                    assertEquals(7, Application.value());
                    assertTrue(true);
                }
            }
        """.trimIndent())
        fun dependency(text: String, extension: String = "jar") {
            ZipOutputStream(project.resolve("dependency.$extension").outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("docs/00-build.md"))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        dependency(metadata(first))
        dependency(metadata(first), "zip")
        fun runner(vararg extra: String) = GradleRunner.create().withProjectDir(project)
            .withPluginClasspath().withTestKitDir(java.io.File(System.getProperty("fixture.testkit.dir")))
            .withArguments("test", "--offline", "--build-cache", "--console=plain", "--max-workers=2", "--no-watch-fs", *extra)
        assertEquals(TaskOutcome.SUCCESS, runner().build().task(":test")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, runner("providerPactTest").build().task(":providerPactTest")!!.outcome)
        facts.writeText(metadata(second))
        resources.resolve("openbank-service-build.properties").writeText("git.commit=$second\n")
        assertEquals(TaskOutcome.UP_TO_DATE, runner().build().task(":test")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, runner("providerPactTest").build().task(":providerPactTest")!!.outcome)
        project.resolve("build/test-results/test").deleteRecursively()
        assertEquals(TaskOutcome.FROM_CACHE, runner().build().task(":test")!!.outcome)
        facts.writeText(metadata(second, "2.0"))
        assertEquals(TaskOutcome.SUCCESS, runner().build().task(":test")!!.outcome)
        facts.writeText(facts.readText().replace("| API version | `1` |", "| API version | `2` |"))
        assertEquals(TaskOutcome.SUCCESS, runner().build().task(":test")!!.outcome)
        facts.appendText("authored additional guidance\n")
        assertEquals(TaskOutcome.SUCCESS, runner().build().task(":test")!!.outcome)
        resources.resolve("docs/README.md").writeText("authored docs changed")
        assertEquals(TaskOutcome.SUCCESS, runner().build().task(":test")!!.outcome)
        dependency(metadata(second, "9.0"))
        assertEquals(TaskOutcome.SUCCESS, runner().build().task(":test")!!.outcome)
        dependency(metadata(second, "10.0"), "zip")
        assertEquals(TaskOutcome.SUCCESS, runner().build().task(":test")!!.outcome)
        // Actual execution still sees the current commit when tests need to run.
        assertTrue(project.resolve("build/test-results/test/TEST-CacheTest.xml").readText().contains(second))
        // This is an offline task-input fixture, never broker verification evidence.
        val brokerFacts = facts.readText()
        val brokerProperties = resources.resolve("openbank-service-build.properties").readText()
        assertEquals(TaskOutcome.SUCCESS, runner("-PfixtureBroker").build().task(":test")!!.outcome)
        facts.writeText(brokerFacts.replace(second, first))
        resources.resolve("openbank-service-build.properties").writeText(brokerProperties.replace(second, first))
        assertEquals(TaskOutcome.SUCCESS, runner("-PfixtureBroker").build().task(":test")!!.outcome)
        facts.writeText(brokerFacts)
        resources.resolve("openbank-service-build.properties").writeText(brokerProperties)
        source.writeText(source.readText().replace("assertTrue(true)", "assertTrue(false)"))
        assertEquals(TaskOutcome.FAILED, runner().buildAndFail().task(":test")!!.outcome)
        source.writeText(source.readText().replace("assertTrue(false)", "assertTrue(true)"))
        application.writeText(application.readText().replace("return 7", "return 8"))
        assertEquals(TaskOutcome.FAILED, runner().buildAndFail().task(":test")!!.outcome)
    }
}
