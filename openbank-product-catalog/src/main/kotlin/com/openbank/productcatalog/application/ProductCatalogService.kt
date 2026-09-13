// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.application

import com.openbank.libs.product.WaiveConditionParser
import com.openbank.libs.product.WaivePredicate
import com.openbank.productcatalog.application.port.out.ProductRepository
import com.openbank.productcatalog.domain.CardConfig
import com.openbank.productcatalog.domain.EligibilitySegment
import com.openbank.productcatalog.domain.Fee
import com.openbank.productcatalog.domain.MultiCurrencyConfig
import com.openbank.productcatalog.domain.OverdraftConfig
import com.openbank.productcatalog.domain.Product
import com.openbank.productcatalog.domain.ProductStatus
import com.openbank.productcatalog.domain.ProductValidation
import com.openbank.productcatalog.domain.SavingsConfig
import com.openbank.productcatalog.domain.TermDepositConfig
import com.openbank.productcatalog.domain.TermsAndConditions
import com.openbank.productcatalog.domain.activate
import com.openbank.productcatalog.domain.deactivate
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.logging.Logger

/**
 * Product catalogue application service (ADR-0002 hexagonal). Persistence lives behind
 * [ProductRepository] (Postgres, ADR-0105 P1) — the in-memory seed moved to [ProductSeed] and is
 * loaded on first boot by ProductCatalogSeeder. Methods are `suspend`: the repository is reactive
 * Panache (the fleet standard) and RESTEasy Reactive supports suspend resource methods.
 */
@ApplicationScoped
class ProductCatalogService(private val repo: ProductRepository, private val clock: Clock) {

    private val log = Logger.getLogger(ProductCatalogService::class.java.name)

    suspend fun findAll(): List<Product> = repo.findAll()

    /** Resolve by canonical UUID, else by semantic code or the prod-NNN legacy alias (ADR-0105). */
    suspend fun findById(id: String): Product? {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull()
        return if (uuid != null) repo.findById(uuid) else repo.findByCode(id)
    }

    suspend fun findByCode(code: String): Product? = repo.findByCode(code)

    suspend fun create(req: ProductRequest, actorId: String): Product {
        if (repo.findByCode(req.code) != null) {
            throw DuplicateProductCodeException("Product with code '${req.code}' already exists")
        }
        val product = req.toDomain(clock)
        ProductValidation.requireValid(product)
        validateFeeWaivers(product, log)
        return repo.save(product, actorId = actorId)
    }

    suspend fun update(id: String, req: ProductRequest, actorId: String): Product {
        val existing = findById(id) ?: throw ProductNotFoundException("Product $id not found")
        require(req.code == existing.code) { "code is immutable and must remain '${existing.code}'" }
        require(req.status == null || req.status == existing.status.name) {
            "status changes must use the dedicated lifecycle operation"
        }
        require(existing.status != ProductStatus.ACTIVE) {
            "active products are immutable; deactivate or author a new revision"
        }
        req.revision?.let { expected ->
            if (expected != existing.revision) {
                throw ProductUpdateConflictException(
                    "Product ${existing.id} was modified concurrently " +
                        "(expected revision $expected, current revision ${existing.revision})",
                )
            }
        }
        val updated = req.applyTo(existing, clock)
        ProductValidation.requireValid(updated)
        validateFeeWaivers(updated, log)
        return repo.update(updated, actorId)
    }

    suspend fun activate(id: String, expectedRevision: Long? = null, actorId: String): Product {
        val p = findById(id) ?: throw ProductNotFoundException("Product $id not found")
        requireRevision(p, expectedRevision)
        val activated = p.activate(Instant.now(clock))
        if (activated === p) return p
        ProductValidation.requireValid(activated)
        validateFeeWaivers(activated, log)
        return repo.update(activated, actorId)
    }

    suspend fun deactivate(id: String, expectedRevision: Long? = null, actorId: String): Product {
        val p = findById(id) ?: throw ProductNotFoundException("Product $id not found")
        requireRevision(p, expectedRevision)
        val deactivated = p.deactivate(Instant.now(clock))
        return if (deactivated === p) p else repo.update(deactivated, actorId)
    }

    private fun requireRevision(product: Product, expectedRevision: Long?) {
        if (expectedRevision != null && expectedRevision != product.revision) {
            throw ProductUpdateConflictException(
                "Product ${product.id} was modified concurrently " +
                    "(expected revision $expectedRevision, current revision ${product.revision})",
            )
        }
    }

    /**
     * Flattens the per-product [Fee] model into a single fee schedule the admin UI renders as the
     * "Fees" pricing catalog — the catalog's own source of truth for pricing (the UI must not
     * hardcode fees). Now served from the persisted store rather than the in-memory seed.
     */
    suspend fun listFeeSchedule(): List<FeeScheduleItem> = repo.findAll()
        .flatMap { product -> product.fees.map { fee -> FeeScheduleItem.of(product, fee) } }
}

/**
 * A single line of the bank-wide fee schedule: one [Fee] of one [Product], flattened
 * with the owning product's identity/status so the admin UI can render and filter it
 * without re-fetching each product.
 */
data class FeeScheduleItem(
    val id: String,
    val code: String,
    val name: String,
    val type: String,
    val amount: Double,
    val currency: String,
    val frequency: String,
    val description: String?,
    val waivable: Boolean,
    val waiveCondition: String?,
    val waiverEvaluable: Boolean,
    val waiverRule: WaiverRuleView?,
    val productId: String,
    val productCode: String,
    val productName: String,
    val status: String,
    val updatedAt: Instant,
) {
    companion object {
        fun of(product: Product, fee: Fee): FeeScheduleItem {
            val rule = waiverRuleOf(fee)
            return FeeScheduleItem(
                // Stable composite id: product + fee, so the UI key survives re-fetch.
                id = "${product.id}:${fee.id}",
                code = feeCode(product.code, fee.name),
                name = fee.name,
                type = fee.type,
                amount = fee.amount,
                currency = fee.currency,
                frequency = fee.frequency,
                description = fee.description,
                waivable = fee.waivable,
                waiveCondition = fee.waiveCondition,
                // ADR-0138: the executable form of the waiver, derived by the shared engine.
                waiverEvaluable = rule != null,
                waiverRule = rule,
                productId = product.id,
                productCode = product.code,
                productName = product.name,
                // Fee availability tracks its owning product's lifecycle.
                status = product.status.name,
                updatedAt = product.updatedAt,
            )
        }

        /** Parses a fee's free-text waiver condition into its executable form, or null if not evaluable. */
        private fun waiverRuleOf(fee: Fee): WaiverRuleView? {
            if (!fee.waivable || fee.waiveCondition.isNullOrBlank()) return null
            return when (val p = WaiveConditionParser.parse(fee.waiveCondition)) {
                is WaivePredicate.Comparison -> WaiverRuleView(
                    attribute = p.attribute.name,
                    operator = p.operator.symbol,
                    threshold = p.threshold?.toPlainString(),
                    thresholdCurrency = p.currency,
                    textValue = p.textValue,
                )
                is WaivePredicate.Unparseable -> null
            }
        }

        /** Derives a stable, human-readable fee code, e.g. CURRENT_PERSONAL · "FX Conversion" → CURRENT_PERSONAL_FX_CONVERSION. */
        private fun feeCode(productCode: String, feeName: String): String {
            val slug = feeName.uppercase()
                .replace(Regex("[^A-Z0-9]+"), "_")
                .trim('_')
            return "${productCode}_$slug"
        }
    }
}

/**
 * The machine-executable form of a fee's waiver condition, surfaced read-only on the fee
 * schedule (ADR-0138 phase 1b). Present only when [FeeScheduleItem.waiverEvaluable] is true;
 * for numeric rules [threshold]/[thresholdCurrency] are set, for segment/currency rules
 * [textValue] is set. [threshold] is a decimal string to avoid binary-float artefacts.
 */
data class WaiverRuleView(
    val attribute: String,
    val operator: String,
    val threshold: String? = null,
    val thresholdCurrency: String? = null,
    val textValue: String? = null,
)

data class ProductRequest(
    val code: String,
    val name: String,
    val type: String,
    val currency: String,
    val status: String? = null,
    val isPublic: Boolean? = null,
    val version: String? = null,
    val validFrom: String? = null,
    val validTo: String? = null,
    val baseRate: Double? = null,
    val fee: Double? = null,
    /**
     * Collections are declared with a NULLABLE element type on purpose, because that is the truth
     * on the wire. Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check
     * the ELEMENTS of a collection, so `{"fees": [null]}` deserialises happily into a `List<Fee>`
     * holding a null, and the first element-wise read NPEs. Writing the types honestly is what
     * makes [requireFees], [requireTermsAndConditions] and [requireTags] reachable instead of
     * dead code -- they are the only way the domain object is built.
     */
    val fees: List<Fee?>? = null,
    val description: String? = null,
    val shortDescription: String? = null,
    val minBalance: Double? = null,
    val maxBalance: Double? = null,
    val cardConfig: CardConfig? = null,
    val multiCurrencyConfig: MultiCurrencyConfig? = null,
    val overdraftConfig: OverdraftConfig? = null,
    val termDepositConfig: TermDepositConfig? = null,
    val savingsConfig: SavingsConfig? = null,
    val termsAndConditions: List<TermsAndConditions?>? = null,
    val tags: List<String?>? = null,
    val eligibilitySegments: List<EligibilitySegment>? = null,
    /** Optional v1 optimistic precondition. v2 authoring requires it on every mutation. */
    val revision: Long? = null,
) {
    /** `IllegalArgumentException` is rendered as a client error by the v1 resource; no
     *  service-local mapper is added (#526). */
    fun requireFees(): List<Fee>? = fees?.mapIndexed { index, fee ->
        requireNotNull(fee) { "fees[$index] must not be null" }
    }

    fun requireTermsAndConditions(): List<TermsAndConditions>? = termsAndConditions?.mapIndexed { i, terms ->
        requireNotNull(terms) { "termsAndConditions[$i] must not be null" }
    }

    fun requireTags(): List<String>? = tags?.mapIndexed { index, tag ->
        requireNotNull(tag) { "tags[$index] must not be null" }
    }

    fun toDomain(clock: Clock) = Product(
        id = UUID.randomUUID().toString(),
        code = code,
        name = name,
        type = type,
        currency = currency,
        status = ProductStatus.DRAFT.also {
            require(status == null || status == ProductStatus.DRAFT.name) { "new products must start in DRAFT" }
        },
        isPublic = isPublic ?: true,
        version = version ?: "1.0.0",
        validFrom = validFrom?.let { LocalDate.parse(it) },
        validTo = validTo?.let { LocalDate.parse(it) },
        baseRate = baseRate ?: 0.0,
        fee = fee ?: 0.0,
        fees = requireFees() ?: emptyList(),
        description = description,
        shortDescription = shortDescription,
        minBalance = minBalance,
        maxBalance = maxBalance,
        cardConfig = cardConfig,
        multiCurrencyConfig = multiCurrencyConfig,
        overdraftConfig = overdraftConfig,
        termDepositConfig = termDepositConfig,
        savingsConfig = savingsConfig,
        termsAndConditions = requireTermsAndConditions() ?: emptyList(),
        tags = requireTags() ?: emptyList(),
        eligibilitySegments = eligibilitySegments ?: listOf(EligibilitySegment.ALL),
        createdAt = Instant.now(clock),
        updatedAt = Instant.now(clock),
    )

    @Suppress("CyclomaticComplexMethod")
    fun applyTo(existing: Product, clock: Clock) = existing.copy(
        name = name,
        type = type,
        currency = currency,
        status = status?.let { ProductStatus.valueOf(it) } ?: existing.status,
        isPublic = isPublic ?: existing.isPublic,
        version = version ?: existing.version,
        validFrom = validFrom?.let { LocalDate.parse(it) } ?: existing.validFrom,
        validTo = validTo?.let { LocalDate.parse(it) } ?: existing.validTo,
        baseRate = baseRate ?: existing.baseRate,
        fee = fee ?: existing.fee,
        fees = requireFees() ?: existing.fees,
        description = description ?: existing.description,
        shortDescription = shortDescription ?: existing.shortDescription,
        minBalance = minBalance ?: existing.minBalance,
        maxBalance = maxBalance ?: existing.maxBalance,
        cardConfig = cardConfig ?: existing.cardConfig,
        multiCurrencyConfig = multiCurrencyConfig ?: existing.multiCurrencyConfig,
        overdraftConfig = overdraftConfig ?: existing.overdraftConfig,
        termDepositConfig = termDepositConfig ?: existing.termDepositConfig,
        savingsConfig = savingsConfig ?: existing.savingsConfig,
        termsAndConditions = requireTermsAndConditions() ?: existing.termsAndConditions,
        tags = requireTags() ?: existing.tags,
        eligibilitySegments = eligibilitySegments ?: existing.eligibilitySegments,
        updatedAt = Instant.now(clock),
    )
}

class DuplicateProductCodeException(message: String) : RuntimeException(message)

class ProductUpdateConflictException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ProductNotFoundException(message: String) : RuntimeException(message)

/**
 * Runs the fee-waiver rule engine (ADR-0138) over a product's fees on write. A fee
 * flagged `waivable` with a blank condition is a hard data error and is rejected; a
 * condition that is present but not yet machine-evaluable is logged so the
 * "free text vs. executable rule" gap is visible on real data rather than silent.
 * No money is moved here — runtime fee posting is a deferred money-path phase.
 */
// CodeQL java/log-injection: product.code/fee.name/fee.waiveCondition are admin-supplied
// catalog config, logged verbatim below. Strip CR/LF so they can't forge additional log lines
// (log forging, CWE-117).
private fun String?.sanitizeForLog(): String = (this ?: "-").replace('\n', '_').replace('\r', '_')

internal fun validateFeeWaivers(product: Product, log: Logger) {
    product.fees.filter { it.waivable }.forEach { fee ->
        require(!fee.waiveCondition.isNullOrBlank()) {
            "Fee '${fee.name}' is marked waivable but has no waiver condition"
        }
        val predicate = WaiveConditionParser.parse(fee.waiveCondition)
        if (predicate is WaivePredicate.Unparseable) {
            log.warning(
                "Product '${product.code.sanitizeForLog()}' fee '${fee.name.sanitizeForLog()}' has a " +
                    "non-evaluable waiver condition (${predicate.reason.sanitizeForLog()}): " +
                    "\"${fee.waiveCondition.sanitizeForLog()}\" — fee will not be auto-waived (ADR-0138)",
            )
        }
    }
}
