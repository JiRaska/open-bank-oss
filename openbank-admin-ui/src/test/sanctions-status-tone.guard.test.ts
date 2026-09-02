import { describe, it, expect } from "vitest"
import { readFileSync } from "node:fs"
import { join } from "node:path"
import { statusTone } from "@/components/ui/tone"

/**
 * The sanctions table hand-rolled its Result badge as
 *
 *     isHit ? danger : isPending ? warning : success
 *
 * so `ESCALATED` — a real `SanctionsCheck` status that `isHighRisk()` treats as high risk, and
 * which `tone.ts` maps to `danger` — rendered GREEN. So did any status the page had not been
 * taught. On a sanctions screening surface that is the worst direction for the error to run:
 * the reviewer sees a pass for a check that was escalated.
 *
 * This guard pins the two properties that matter, and deliberately asserts them at different
 * levels. The first is behavioural (what `statusTone` resolves), because that is the invariant
 * worth keeping. The second is structural (the page must not re-introduce a local ternary),
 * because a page can satisfy `statusTone` in one place and hand-roll a badge in another — which
 * is exactly what happened here while `tone.ts` was already correct.
 */

const PAGE = join(__dirname, "..", "app", "sanctions", "page.tsx")

/** Strip comments before any structural match: this file documents the defects it guards against
 *  in comments, and a guard that matches its own explanation proves nothing. */
function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/^\s*\/\/.*$/gm, "")
}

describe("sanctions result badge", () => {
  it("never resolves a non-clear status to success", () => {
    // Every status the domain can produce, from SanctionsCheck.kt / openapi.yaml.
    for (const status of ["HIT", "POTENTIAL_HIT", "ESCALATED"]) {
      expect(statusTone(status)).not.toBe("success")
    }
    expect(statusTone("ESCALATED")).toBe("danger")
  })

  it("resolves an unknown status to neutral, never success", () => {
    // The property that makes this safe by default: a status added to the backend before the UI
    // learns about it must not render as a green tick.
    expect(statusTone("SOME_STATUS_THE_UI_HAS_NEVER_SEEN")).toBe("neutral")
    expect(statusTone(undefined)).toBe("neutral")
    expect(statusTone("")).toBe("neutral")
  })

  it("only CLEAR and WHITELISTED may render the CLEAR RECORD headline", () => {
    // The screening panel gated on `status === 'HIT'` alone, so POTENTIAL_HIT, ESCALATED and
    // anything unrecognised rendered a green box, a tick, and the literal text CLEAR RECORD.
    // A false textual assertion that a screened name is clean is worse than a wrong colour, and
    // POTENTIAL_HIT is directly producible by the endpoint this panel renders.
    const code = stripComments(readFileSync(PAGE, "utf8"))

    // Asserted positively, on purpose. My first attempt used a negative regex for the old shape
    // (`status === 'HIT' ? t('SHODA NALEZENA' … ČISTÝ ZÁZNAM`) and it matched the FIXED code too,
    // because the two strings stay within a few lines of each other either way — an assertion
    // that fails against both worlds discriminates nothing. These three do discriminate: the old
    // code contains none of them.
    expect(code).toContain("screenResult.status === 'CLEAR'")
    expect(code).toContain("screenResult.status === 'WHITELISTED'")
    expect(code).toMatch(/REVIEW REQUIRED/)
  })

  it("the page does not hand-roll a success fallback for the result badge", () => {
    const page = readFileSync(PAGE, "utf8")
    // Strip comments first: this file documents the old ternary in a comment, and a guard that
    // matches its own explanation of the defect is the vacuous shape this repo keeps finding.
    const code = stripComments(page)
    expect(code).not.toMatch(/isPending\s*\?\s*'var\(--warning-bg\)'\s*:\s*'var\(--success-bg\)'/)
    expect(code).toContain("<StatusBadge status={c.status} />")
  })
})
