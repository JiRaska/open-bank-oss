// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.detekt.contact

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

private const val MARKER_ANNOTATION = "MarketingCallSite"
private const val GATE_TYPE = "ContactPolicyGate"
private const val GATE_CHECK_METHOD = "check"

/**
 * ADR-0219 D4's compile-time wiring assertion. A function annotated `@MarketingCallSite`
 * (`com.openbank.libs.contact.MarketingCallSite`) is a marketing-class touch reaching the
 * delivery/surface layer; this rule fails the build when the annotated function's containing
 * class has no `ContactPolicyGate` injected (constructor `val`/`var` parameter or a class
 * property of that type), or when no `<gate>.check(...)` call is found anywhere in that class —
 * "every sender checks" as a rule nobody can silently skip, not a convention.
 *
 * Deliberately class-scoped, not a full interprocedural call graph: it looks for the gate call
 * anywhere in the annotated function's own class, not just the annotated function's body (a use
 * case class commonly resolves eligibility in one function and performs the gate check in a
 * private helper the same class calls). It does NOT follow calls into other files/classes — a
 * marketing call site that delegates the actual check to a different class is a false negative
 * this first slice does not catch. Type matching is by simple name, not full binding-context
 * resolution (`RequiresTypeResolution` is deliberately avoided — this repo's detekt runs a
 * forked CLI without a shared compile classpath per module), so a different `ContactPolicyGate`
 * class from an unrelated package would false-positive; there is exactly one in the fleet today.
 */
class MarketingCallSiteWiringRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        javaClass.simpleName,
        Severity.Defect,
        "A @MarketingCallSite function must have ContactPolicyGate injected into its class and " +
            "call .check(...) on it (ADR-0219 D4) — a marketing-class touch must not bypass the gate.",
        Debt.TWENTY_MINS,
    )

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val isMarketingCallSite = function.annotationEntries.any {
            it.shortName?.asString() == MARKER_ANNOTATION
        }
        if (!isMarketingCallSite) return

        val containingClass = function.getStrictParentOfType<KtClassOrObject>()
        if (containingClass == null) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(function),
                    "@MarketingCallSite function '${function.name}' has no containing class to inject " +
                        "$GATE_TYPE into.",
                ),
            )
            return
        }

        val gatePropertyNames = gatePropertyNames(containingClass)
        if (gatePropertyNames.isEmpty()) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(function),
                    "'${function.name}' is @MarketingCallSite but '${containingClass.name}' has no " +
                        "$GATE_TYPE injected (no constructor val/var parameter or property of that type).",
                ),
            )
            return
        }

        val callsGate = containingClass.collectDescendantsOfType<KtCallExpression> { call ->
            val calleeName = call.calleeExpression?.text
            val receiverName = (call.parent as? KtDotQualifiedExpression)?.receiverExpression?.text
            calleeName == GATE_CHECK_METHOD && receiverName != null && receiverName in gatePropertyNames
        }.isNotEmpty()
        if (!callsGate) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(function),
                    "'${function.name}' is @MarketingCallSite and '${containingClass.name}' injects " +
                        "$GATE_TYPE, but no '<gate>.$GATE_CHECK_METHOD(...)' call was found in that class — " +
                        "the gate is not in this call site's call graph.",
                ),
            )
        }
    }

    private fun gatePropertyNames(containingClass: KtClassOrObject): List<String> {
        val fromConstructor = containingClass.primaryConstructor?.valueParameters.orEmpty()
            .filter { it.hasValOrVar() && it.typeReference?.text?.substringAfterLast('.') == GATE_TYPE }
            .mapNotNull { it.name }
        val fromBody = containingClass.body?.properties.orEmpty()
            .filter { it.typeReference?.text?.substringAfterLast('.') == GATE_TYPE }
            .mapNotNull { it.name }
        return fromConstructor + fromBody
    }
}
