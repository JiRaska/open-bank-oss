// SPDX-License-Identifier: Apache-2.0
package com.openbank.libs.probe

/** TEMPORARY probe for issue #6384 — 20 uncovered lines, no test anywhere. NOT FOR COMMIT. */
object DilutionProbe {
    fun a(i: Int): Int {
        val x = i + 1
        val y = x * 2
        val z = y - 3
        return z
    }
    fun b(i: Int): Int {
        val x = i + 4
        val y = x * 5
        val z = y - 6
        return z
    }
    fun c(i: Int): Int {
        val x = i + 7
        val y = x * 8
        val z = y - 9
        return z
    }
    fun d(i: Int): Int {
        val x = i + 10
        val y = x * 11
        val z = y - 12
        return z
    }
}
