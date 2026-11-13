// SPDX-License-Identifier: Apache-2.0
package com.openbank.billing.shadow

import com.openbank.billing.application.usecase.FeeShadowComparator
import com.openbank.billing.application.usecase.FeeShadowResult
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.Action
import com.openbank.libs.product.ir.Cadence
import com.openbank.libs.product.ir.Hook
import com.openbank.libs.product.ir.HookRule
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.product.ir.ProductRevisionIr
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScheduleRegistrationFallbackTest {
    private val comparator = FeeShadowComparator(ProductInterpreter(FinanceProductArithmetic))
    private val revision = ProductIrParser.parse(ShadowFixtures.IR)
    private val registration = revision.hooks.getValue(Hook.ON_OPEN).single()
    private val input = ShadowFixtures.input("500", listOf(ShadowFixtures.charged())).copy(scheduledTrigger = null)

    private fun result(ir: ProductRevisionIr) = comparator.compare(ir, input).single()

    private fun opening(vararg rules: HookRule) = revision.copy(
        hooks =
        revision.hooks + (Hook.ON_OPEN to rules.toList()),
    )

    @Test
    fun `one unconditional opening registration permits counterfactual comparison`() {
        assertThat(result(revision)).isInstanceOf(FeeShadowResult.Compared::class.java)
        assertThat((result(revision) as FeeShadowResult.Compared).diverged).isFalse()
    }

    @Test
    fun `false opening guard is not erased or evaluated using current account facts`() {
        val guarded = opening(registration.copy(whenPredicate = revision.fees.single().waiver))
        assertThat(result(guarded)).isInstanceOf(FeeShadowResult.UnknownInput::class.java)
    }

    @Test
    fun `closing lifecycle registration does not establish an opening schedule`() {
        val closing = revision.copy(hooks = (revision.hooks - Hook.ON_OPEN) + (Hook.ON_CLOSE to listOf(registration)))
        assertThat(result(closing)).isInstanceOf(FeeShadowResult.UnknownInput::class.java)
    }

    @Test
    fun `duplicate registrations sharing one tag do not collapse into a unique producer`() {
        assertThat(result(opening(registration, registration.copy(ruleId = "open.duplicate"))))
            .isInstanceOf(FeeShadowResult.UnknownInput::class.java)
    }

    @Test
    fun `multiple same cadence tags do not choose a producer`() {
        val other = registration.copy(
            ruleId = "open.other",
            action = Action.Schedule(Hook.SCHEDULED, "OTHER", Cadence.MONTHLY),
        )
        assertThat(result(opening(registration, other))).isInstanceOf(FeeShadowResult.UnknownInput::class.java)
    }

    @Test
    fun `a competing registration of the same tag on another cadence is ambiguous`() {
        val other = registration.copy(
            ruleId = "open.daily",
            action = Action.Schedule(Hook.SCHEDULED, "FEE_MAINT", Cadence.DAILY),
        )
        assertThat(result(opening(registration, other))).isInstanceOf(FeeShadowResult.UnknownInput::class.java)
    }

    @Test
    fun `preceding opening rejection prevents inferred registration`() {
        val rejection = HookRule("open.reject", null, null, Action.Reject("NOT_ELIGIBLE"))
        assertThat(result(opening(rejection, registration))).isInstanceOf(FeeShadowResult.UnknownInput::class.java)
    }

    @Test
    fun `later opening rejection also discards already emitted registration`() {
        val rejection = HookRule("open.reject", null, null, Action.Reject("NOT_ELIGIBLE"))
        assertThat(result(opening(registration, rejection))).isInstanceOf(FeeShadowResult.UnknownInput::class.java)
    }

    @Test
    fun `explicit captured tag is retained even when opening registration cannot be reconstructed`() {
        val guarded = opening(registration.copy(whenPredicate = revision.fees.single().waiver))
        val compared = comparator.compare(guarded, input.copy(scheduledTrigger = "FEE_MAINT")).single()
        assertThat(compared).isInstanceOf(FeeShadowResult.Compared::class.java)
        assertThat((compared as FeeShadowResult.Compared).diverged).isFalse()
    }

    @Test
    fun `distinct IR fees sharing one legacy code are ambiguous instead of silently dropping a charge`() {
        val extraFee = revision.fees.single().copy(ruleId = "fee.second")
        val extraCharge = revision.hooks.getValue(Hook.SCHEDULED).single().copy(
            ruleId = "scheduled.second",
            action = Action.ChargeFee(extraFee.ruleId),
        )
        val duplicateCode = revision.copy(
            fees = revision.fees + extraFee,
            hooks = revision.hooks + (Hook.SCHEDULED to (revision.hooks.getValue(Hook.SCHEDULED) + extraCharge)),
        )
        assertThat(comparator.compare(duplicateCode, input.copy(scheduledTrigger = "FEE_MAINT")).single())
            .isInstanceOf(FeeShadowResult.UnknownInput::class.java)
    }

    @Test
    fun `equal numeric fees in different currencies are not compared`() {
        val result = comparator.compare(revision, input.copy(currency = "CZK", scheduledTrigger = "FEE_MAINT")).single()
        assertThat(result).isInstanceOf(FeeShadowResult.UnknownInput::class.java)
    }
}
