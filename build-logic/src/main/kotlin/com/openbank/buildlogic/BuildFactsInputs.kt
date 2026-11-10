// SPDX-License-Identifier: Apache-2.0
package com.openbank.buildlogic

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

// Pact Broker inputs forwarded to the test JVM by openbank.quarkus-service. The SAME list
// decides (openbank.test-provenance) that a broker-configured test keeps full metadata, so
// a new broker key cannot be forwarded without also disabling source-only reuse.
val pactBrokerSystemProperties: List<String> = listOf(
    "pactbroker.url",
    "pactbroker.auth.username",
    "pactbroker.auth.password",
    "pactbroker.enablePending",
    "pactbroker.providerBranch",
    "pact.verifier.publishResults",
    "pact.provider.version",
    "pact.provider.branch",
    "pact.provider.tag",
    "pact.filter.pacturl",
    "pact.filter.consumers",
)

private val buildFactsPaths = listOf("docs/00-build.md", "openbank-service-build.properties")
private val markdownCommit = Regex("(?m)^(\\| Source commit \\| `)[0-9a-fA-F]{40}(` \\|)$")
private val propertiesCommit = Regex("(?m)^(git\\.commit=)[0-9a-fA-F]{40}$")

// Reintroduce the two normalized resources as explicit inputs. Every other byte,
// their presence, classpath position and dependency-JAR occurrences still matter.
// ISO-8859-1 maps each byte to one character without lossy decoding.
fun buildFactsInputs(classpath: Iterable<File>, normalizeCommit: Boolean): List<String> =
    classpath.flatMapIndexed { index, file ->
        val contents = linkedMapOf<String, String>()
        if (file.isDirectory) {
            buildFactsPaths.forEach { name ->
                file.resolve(name).takeIf { it.isFile }?.let { contents[name] = it.readBytes().toString(Charsets.ISO_8859_1) }
            }
        } else if (file.isFile && file.extension == "jar") {
            ZipFile(file).use { zip ->
                buildFactsPaths.forEach { name ->
                    zip.getEntry(name)?.let { entry ->
                        contents[name] = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
                    }
                }
            }
        } else if (file.isFile) {
            // JVM classpath archives need not use .jar. Unknown entry types are
            // conservative full-byte inputs, never silently omitted.
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            contents["unfiltered-classpath-entry"] = digest.joinToString("") { "%02x".format(it) }
        }
        contents.map { (name, text) ->
            val pattern = if (name.endsWith(".md")) markdownCommit else propertiesCommit
            val normalized = if (normalizeCommit && pattern.findAll(text).count() == 1) {
                pattern.replace(text) {
                    "${it.groupValues[1]}SOURCE_COMMIT${it.groupValues.getOrElse(2) { "" }}"
                }
            } else text
            "$index:$name:$normalized"
        }
    }
