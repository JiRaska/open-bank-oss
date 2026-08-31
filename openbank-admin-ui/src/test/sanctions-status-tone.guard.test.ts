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

  it("the page does not hand-roll a success fallback for the result badge", () => {
    const page = readFileSync(PAGE, "utf8")
    // Strip comments first: this file documents the old ternary in a comment, and a guard that
    // matches its own explanation of the defect is the vacuous shape this repo keeps finding.
    const code = page
      .replace(/\/\*[\s\S]*?\*\//g, "")
      .replace(/^\s*\/\/.*$/gm, "")
      .replace(/\{\s*\/\*[\s\S]*?\*\/\s*\}/g, "")
    expect(code).not.toMatch(/isPending\s*\?\s*'var\(--warning-bg\)'\s*:\s*'var\(--success-bg\)'/)
    expect(code).toContain("<StatusBadge status={c.status} />")
  })
})
