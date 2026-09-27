package com.openbank.statement.domain.render

import java.util.Currency

/**
 * ISO 4217 minor-unit scale shared by every statement renderer.
 *
 * Returns the currency's default fraction digits (JPY 0, EUR/CZK 2, KWD/BHD 3). Falls back to 2 when
 * the code is not a known ISO 4217 currency (`Currency.getInstance` throws) or when ISO defines no
 * minor unit (XAU, XDR, XXX report -1, which is not a usable `setScale` argument). The code is
 * upper-cased first, so a lower-case code renders like its canonical form instead of failing the
 * whole statement.
 */
internal object CurrencyScale {
    private const val FALLBACK_SCALE = 2

    fun of(currency: String): Int = runCatching { Currency.getInstance(currency.uppercase()).defaultFractionDigits }
        .getOrNull()
        ?.takeIf { it >= 0 }
        ?: FALLBACK_SCALE
}
