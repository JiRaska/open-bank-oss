// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.domain.render

import com.openbank.statement.domain.model.StatementModel
import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Renders a [StatementModel] to ISO 20022 **camt.053.001.08** (bank-to-customer statement) XML.
 *
 * Pure projection (ADR-0035 §C): every value comes from the model, nothing is re-derived or
 * re-queried. **Deterministic** (ADR-0035 §F): all timestamps derive from [StatementModel.closedAt],
 * never the wall clock, so re-rendering a closed period is byte-identical. Amounts are serialised at
 * scale 2 with a dot decimal separator per ISO 20022.
 */
object Camt053Renderer {

    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE
    private val DATETIME = DateTimeFormatter.ISO_INSTANT

    fun render(model: StatementModel): String {
        val createdAt = DATETIME.format(model.closedAt.atZone(ZoneOffset.UTC).toInstant())
        val msgId = messageId(model)
        val stmtId = "STMT-${model.legalSequenceNumber}"

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08">""").append('\n')
        sb.append("  <BkToCstmrStmt>\n")
        // Group header
        sb.append("    <GrpHdr>\n")
        sb.append("      <MsgId>").append(msgId).append("</MsgId>\n")
        sb.append("      <CreDtTm>").append(createdAt).append("</CreDtTm>\n")
        sb.append("    </GrpHdr>\n")
        // Statement
        sb.append("    <Stmt>\n")
        sb.append("      <Id>").append(stmtId).append("</Id>\n")
        sb.append("      <ElctrncSeqNb>").append(model.electronicSequenceNumber).append("</ElctrncSeqNb>\n")
        sb.append("      <LglSeqNb>").append(model.legalSequenceNumber).append("</LglSeqNb>\n")
        sb.append("      <CreDtTm>").append(createdAt).append("</CreDtTm>\n")
        sb.append("      <FrToDt>\n")
        sb.append("        <FrDtTm>").append(DATE.format(model.periodFrom)).append("T00:00:00Z</FrDtTm>\n")
        sb.append("        <ToDtTm>").append(DATE.format(model.periodTo)).append("T23:59:59Z</ToDtTm>\n")
        sb.append("      </FrToDt>\n")
        sb.append("      <Acct>\n")
        sb.append("        <Id><IBAN>").append(esc(model.iban)).append("</IBAN></Id>\n")
        sb.append("        <Ccy>").append(model.currency).append("</Ccy>\n")
        sb.append("        <Ownr><Nm>").append(esc(model.holderName)).append("</Nm></Ownr>\n")
        sb.append("      </Acct>\n")
        // Opening booked balance (OPBD) and closing booked balance (CLBD)
        appendBalance(sb, "OPBD", model.openingBalance.amount, model.currency, DATE.format(model.periodFrom))
        appendBalance(sb, "CLBD", model.closingBalance.amount, model.currency, DATE.format(model.periodTo))
        // Entries
        for (e in model.entries) {
            sb.append("      <Ntry>\n")
            sb.append("        <Amt Ccy=\"").append(e.currency).append("\">")
                .append(money(e.amount)).append("</Amt>\n")
            sb.append("        <CdtDbtInd>").append(e.creditDebit.name).append("</CdtDbtInd>\n")
            sb.append("        <Sts><Cd>BOOK</Cd></Sts>\n")
            sb.append("        <BookgDt><Dt>").append(DATE.format(e.bookingDate)).append("</Dt></BookgDt>\n")
            sb.append("        <ValDt><Dt>").append(DATE.format(e.valueDate)).append("</Dt></ValDt>\n")
            sb.append("        <AcctSvcrRef>").append(esc(e.entryRef)).append("</AcctSvcrRef>\n")
            sb.append("        <NtryDtls><TxDtls><RmtInf><Ustrd>")
                .append(esc(e.description)).append("</Ustrd></RmtInf></TxDtls></NtryDtls>\n")
            sb.append("      </Ntry>\n")
        }
        sb.append("    </Stmt>\n")
        sb.append("  </BkToCstmrStmt>\n")
        sb.append("</Document>\n")
        return sb.toString()
    }

    /** Deterministic message id: pocket + legal sequence, no random / no clock. */
    private fun messageId(model: StatementModel): String =
        "STMT-${model.accountId}-${model.currency}-${model.legalSequenceNumber}"

    private fun appendBalance(sb: StringBuilder, code: String, amount: BigDecimal, ccy: String, date: String) {
        val cdi = if (amount.signum() < 0) "DBIT" else "CRDT"
        sb.append("      <Bal>\n")
        sb.append("        <Tp><CdOrPrtry><Cd>").append(code).append("</Cd></CdOrPrtry></Tp>\n")
        sb.append("        <Amt Ccy=\"").append(ccy).append("\">").append(money(amount.abs())).append("</Amt>\n")
        sb.append("        <CdtDbtInd>").append(cdi).append("</CdtDbtInd>\n")
        sb.append("        <Dt><Dt>").append(date).append("</Dt></Dt>\n")
        sb.append("      </Bal>\n")
    }

    private fun money(v: BigDecimal): String = v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
