// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.application

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.HexFormat

/** Stable, length-delimited hash: query inputs and projection slices cannot collide by separator choice. */
internal object ContextDisclosureFingerprint {
    fun of(vararg fields: String?): String = of(fields.asList())

    fun of(fields: List<String?>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fields.forEach { value ->
                if (value == null) {
                    out.writeInt(-1)
                } else {
                    val encoded = value.toByteArray(Charsets.UTF_8)
                    out.writeInt(encoded.size)
                    out.write(encoded)
                }
            }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
    }
}
