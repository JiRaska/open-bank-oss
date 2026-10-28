// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.importer

import java.io.BufferedReader

/** Minimal RFC 4180 reader shared by the streaming sanctions CSV importers. */
internal object StreamingCsv {
    /** A quoted CSV field can contain physical line breaks; return the complete logical record. */
    fun readRecord(reader: BufferedReader): String? {
        val first = reader.readLine() ?: return null
        var record = first
        var quotes = first.count { it == '"' }
        while (quotes % 2 != 0) {
            val next = reader.readLine() ?: break
            record += "\n" + next
            quotes += next.count { it == '"' }
        }
        return record
    }

    fun parseRecord(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && !inQuote -> inQuote = true
                ch == '"' && inQuote && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                ch == '"' && inQuote -> inQuote = false
                ch == ',' && !inQuote -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        result += current.toString()
        return result
    }
}
