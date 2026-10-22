// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.detekt.purity

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

private const val PRODUCT_PACKAGE = "com.openbank.libs.product"

/** Import prefixes that mean I/O, time, logging or metrics — none of which a pure interpreter may touch. */
private val FORBIDDEN_IMPORT_PREFIXES = listOf(
    "java.io.",
    "java.nio.file.",
    "java.net.",
    "java.util.logging.",
    "java.util.concurrent.",
    "java.lang.System",
    "org.jboss.logging.",
    "org.slf4j.",
    "io.micrometer.",
    "io.opentelemetry.",
    "kotlin.io.",
    "java.time.Clock",
)

/** A qualified call whose receiver is one of these reads ambient state (the clock, the process, the environment). */
private val FORBIDDEN_RECEIVERS = setOf("System", "Clock", "Runtime", "Thread", "ProcessHandle")

/** `X.now()` on any temporal type is a clock read; the interpreter's clock comes in as an argument. */
private val AMBIENT_TIME_CALLS = setOf("now", "systemUTC", "systemDefaultZone", "currentTimeMillis", "nanoTime")

/** Top-level functions that are I/O in disguise. */
private val FORBIDDEN_TOP_LEVEL_CALLS = setOf("println", "print", "readLine", "readln")

/**
 * ADR-0352 §3 and §8: the product interpreter under `com.openbank.libs.product` is a pure function
 * — zero I/O, no clock other than the injected one, no logging, no metrics. This rule holds the
 * package to that structurally: a forbidden import (`java.io`, `java.nio.file`, `java.net`,
 * `java.time.Clock`, `System`, any logging or Micrometer package) is a finding, and so is a call on
 * an ambient receiver (`System.currentTimeMillis()`, `Clock.systemUTC()`, `Instant.now()`) or a
 * console call (`println`). `ADR-0100`'s clock-injection gate already forbids `Instant.now()` in
 * money-path domain layers; this rule applies the same idea to the one package every product
 * consumer shares, and adds the I/O and observability halves the clock gate does not cover.
 *
 * Text-based like the other rules in this module (no type resolution): it matches on the imported
 * fully-qualified name and on the receiver's source text. A fully-qualified call with no import
 * (`java.lang.System.getenv()`) is caught by the receiver check (`System` is the last qualifier).
 */
class ProductIrPurityRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        javaClass.simpleName,
        Severity.Defect,
        "com.openbank.libs.product must stay pure (ADR-0352): no I/O, no ambient clock, no logging, no metrics.",
        Debt.TWENTY_MINS,
    )

    private var inScope = false

    override fun visitKtFile(file: KtFile) {
        val pkg = file.packageFqName.asString()
        inScope = pkg == PRODUCT_PACKAGE || pkg.startsWith("$PRODUCT_PACKAGE.")
        if (!inScope) return
        super.visitKtFile(file)
    }

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val path = importDirective.importedFqName?.asString() ?: return
        val hit = FORBIDDEN_IMPORT_PREFIXES.firstOrNull { path == it.trimEnd('.') || path.startsWith(it) }
        if (hit != null) {
            report(CodeSmell(issue, Entity.from(importDirective), "import of '$path' is not allowed under $PRODUCT_PACKAGE (ADR-0352 purity: $hit)"))
        }
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val call = expression.selectorExpression as? KtCallExpression ?: return
        val callee = call.calleeExpression?.text ?: return
        val receiver = expression.receiverExpression.text.substringAfterLast('.')
        if (receiver in FORBIDDEN_RECEIVERS) {
            report(CodeSmell(issue, Entity.from(expression), "'$receiver.$callee(...)' reads ambient state; the interpreter may only read its arguments (ADR-0352 purity)"))
        } else if (callee in AMBIENT_TIME_CALLS && receiver.firstOrNull()?.isUpperCase() == true) {
            report(CodeSmell(issue, Entity.from(expression), "'$receiver.$callee(...)' reads the ambient clock; use the injected ProductClock (ADR-0352, ADR-0100)"))
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.parent is KtDotQualifiedExpression) return
        val callee = expression.calleeExpression?.text ?: return
        if (callee in FORBIDDEN_TOP_LEVEL_CALLS) {
            report(CodeSmell(issue, Entity.from(expression), "'$callee(...)' is console I/O; the interpreter does not log (ADR-0352 purity)"))
        }
    }
}
