#!/usr/bin/env bash
# check-clock-injection.sh — ADR-0100 Layer 1 gate
#
# Enforces that domain/ and application/ layers of money-path services do NOT
# call wall-clock APIs directly (Instant.now(), LocalDateTime.now(),
# LocalDate.now(), System.currentTimeMillis()). All time reads must go through
# an injected Clock / ClockProvider so that DST/timezone/leap-second tests can
# control time deterministically.
#
# Refs: ADR-0100 (DST harness), Issue #1612
#
# Usage: bash .github/scripts/check-clock-injection.sh
# Exit:  0 = clean, 1 = violations found

set -euo pipefail
# --- self-test ------------------------------------------------------------------------
# ADR-0100: money-path domain/application code must take time from an injected Clock, never
# from the wall clock, or accounting-day boundaries and value dates cannot be tested and
# cannot be replayed.
#
# The gate shipped with no falsification AND with a hand-written scope of 8 services against
# rules.yaml's 23 — so it reported clean about two thirds of the money path it never looked
# at. Both are fixed in the same pass; this harness is what keeps them fixed.
if [ "${1:-}" = "--self-test" ]; then
  set +e
  td=$(mktemp -d); trap 'rm -rf "$td"' EXIT
  fails=0

  # The fixture repo needs its own rules.yaml, because the scope is DERIVED from it now — a
  # self-test that pointed at the real one would silently drift with the fleet.
  mkdir -p "$td/openbank-libs/governance"
  printf 'money_path_services:\n  - openbank-fixture-service\n' > "$td/openbank-libs/governance/rules.yaml"

  put() { mkdir -p "$(dirname "$1")"; printf '%b' "$2" > "$1"; }
  run() { (cd "$td" && bash "$OLDPWD/$0" 2>&1); }
  expect() { # expect <label> <want-rc> [substring]
    local label="$1" want="$2" sub="${3:-}" out rc
    out=$(run); rc=$?
    if [ "$rc" -ne "$want" ]; then
      echo "::error::self-test: $label — expected rc=$want, got rc=$rc: $out" >&2; fails=$((fails+1))
    elif [ -n "$sub" ] && ! printf '%s' "$out" | grep -qF -- "$sub"; then
      echo "::error::self-test: $label — rc right, reason wrong (no '$sub'): $out" >&2; fails=$((fails+1))
    fi
  }
  reset() { rm -rf "$td/openbank-fixture-service"; }
  K="$td/openbank-fixture-service/src/main/kotlin/com/openbank/fixture"

  # THE DEFECT, in the domain layer.
  reset; put "$K/domain/Money.kt" 'class Money { fun at() = Instant.now() }\n'
  expect "Instant.now() in domain is FLAGGED" 1 "VIOLATION"

  # ...and in application, the other watched layer.
  reset; put "$K/application/Svc.kt" 'class Svc { fun at() = LocalDate.now() }\n'
  expect "LocalDate.now() in application is FLAGGED" 1 "VIOLATION"

  # Multiplication is executable Kotlin, not a KDoc comment. The old unanchored
  # `grep -v '[[:space:]]\*'` discarded this entire line and reported clean.
  reset; put "$K/domain/Money.kt" 'class Money { fun at() = System.currentTimeMillis() * 2 }\n'
  expect "a wall-clock call followed by multiplication is FLAGGED" 1 "VIOLATION"

  reset; put "$K/domain/Money.kt" 'class Money { fun at() = "https://example.invalid" to System.currentTimeMillis() }\n'
  expect "a wall-clock call after a URL literal is FLAGGED" 1 "VIOLATION"

  reset; put "$K/domain/Money.kt" 'class Money { val docs = "https://example.invalid/Instant.now()" }\n'
  expect "a clock-call spelling inside a URL literal is clean" 0 "OK: no direct wall-clock"

  reset; put "$K/domain/Money.kt" 'class Money { val docs = "literal \\" Instant.now()" }\n'
  expect "an escaped quote does not expose string text" 0 "OK: no direct wall-clock"

  # Kotlin interpolation is literal test data.
  reset
  # shellcheck disable=SC2016
  put "$K/domain/Money.kt" 'class Money { val time = "${Instant.now()}" }\n'
  expect "a clock call inside ordinary string interpolation is FLAGGED" 1 "VIOLATION"

  reset; put "$K/domain/Money.kt" 'class Money { val docs = """text\nInstant.now()\ntext""" }\n'
  expect "clock-call spelling inside a multiline raw string is clean" 0 "OK: no direct wall-clock"

  # Kotlin interpolation is literal test data.
  reset
  # shellcheck disable=SC2016
  put "$K/domain/Money.kt" 'class Money { val time = """${Instant.now()}""" }\n'
  expect "a clock call inside raw string interpolation is FLAGGED" 1 "VIOLATION"

  reset; mkdir -p "$(dirname "$K/domain/Money.kt")"
  cat > "$K/domain/Money.kt" <<'KOTLIN'
class Money { val quote = '"'; fun at() = Instant.now() }
KOTLIN
  expect "a double quote in a char literal does not hide executable code" 1 "VIOLATION"

  reset; mkdir -p "$(dirname "$K/domain/Money.kt")"
  cat > "$K/domain/Money.kt" <<'KOTLIN'
class Money { val quote = '\"'; val slash = '\\'; fun at() = Instant.now() }
KOTLIN
  expect "escaped quote and backslash chars do not hide executable code" 1 "VIOLATION"

  reset; mkdir -p "$(dirname "$K/domain/Money.kt")"
  cat > "$K/domain/Money.kt" <<'KOTLIN'
class Money { val quote = '\''; fun at() = Instant.now() }
KOTLIN
  expect "an escaped single quote char does not hide executable code" 1 "VIOLATION"

  reset; mkdir -p "$(dirname "$K/domain/Money.kt")"
  cat > "$K/domain/Money.kt" <<'KOTLIN'
class Money { val quote = '"'; val docs = "Instant.now()" }
KOTLIN
  expect "a char quote does not expose clock-call text in a later string" 0 "OK: no direct wall-clock"

  reset; mkdir -p "$(dirname "$K/domain/Money.kt")"
  cat > "$K/domain/Money.kt" <<'KOTLIN'
class Money { val time = "${if (true) '}' else Instant.now()}" }
KOTLIN
  expect "a char brace inside interpolation does not hide a clock call" 1 "VIOLATION"

  reset; mkdir -p "$(dirname "$K/domain/Money.kt")"
  cat > "$K/domain/Money.kt" <<'KOTLIN'
class Money { val `path//segment` = 1; fun at() = Instant.now() }
KOTLIN
  expect "a slash pair in a backtick identifier does not hide code" 1 "VIOLATION"

  reset; mkdir -p "$(dirname "$K/domain/Money.kt")"
  cat > "$K/domain/Money.kt" <<'KOTLIN'
class Money { val `Instant.now()` = 1 }
KOTLIN
  expect "clock-call text inside a backtick identifier is clean" 0 "OK: no direct wall-clock"

  reset; put "$K/domain/Money.kt" 'class Money { fun at() = Instant . now() }\n'
  expect "spaces around the member-access dot still FLAG a clock call" 1 "VIOLATION"

  reset; put "$K/domain/Money.kt" '/* comment */ fun at() = System.currentTimeMillis()\n'
  expect "code after a same-line block comment is FLAGGED" 1 "VIOLATION"

  reset; put "$K/domain/Money.kt" '/* comment\n */ fun at() = System.currentTimeMillis()\n'
  expect "code after a multiline block comment is FLAGGED" 1 "VIOLATION"

  reset; put "$K/domain/Money.kt" 'fun at() = System.currentTimeMillis() /* comment */\n'
  expect "code before a same-line block comment is FLAGGED" 1 "VIOLATION"

  # The documented fix must be clean, or the gate blocks the shape ADR-0100 requires.
  reset; put "$K/domain/Money.kt" 'class Money(private val clock: Clock) { fun at() = Instant.now(clock) }\n'
  expect "Instant.now(clock) is clean" 0 "OK: no direct wall-clock"

  # SCOPE by LAYER: infrastructure is where a real clock legitimately lives.
  reset; put "$K/infrastructure/Adapter.kt" 'class Adapter { fun at() = Instant.now() }\n'
  expect "the same call in infrastructure is out of scope" 0 "OK: no direct wall-clock"

  # SCOPE by SERVICE: a service absent from rules.yaml is not money-path.
  reset
  mkdir -p "$td/openbank-other-service/src/main/kotlin/com/openbank/other/domain"
  printf 'class X { fun at() = Instant.now() }\n' > "$td/openbank-other-service/src/main/kotlin/com/openbank/other/domain/X.kt"
  put "$K/domain/Money.kt" 'class Money(private val clock: Clock) { fun at() = Instant.now(clock) }\n'
  expect "a non-money-path service is out of scope" 0 "OK: no direct wall-clock"
  rm -rf "$td/openbank-other-service"

  # PROSE: the ADR-0100 fix comments name the very calls being banned.
  reset; put "$K/domain/Money.kt" 'class Money {\n  // never call Instant.now() here — inject a Clock (ADR-0100)\n  fun at() = Instant.now(clock)\n}\n'
  expect "the call named in a comment is not a hit" 0 "OK: no direct wall-clock"

  reset; put "$K/domain/Money.kt" 'class Money {\n  /* never call Instant.now() here */\n  fun at() = Instant.now(clock)\n}\n'
  expect "the call named in a block-comment opener is not a hit" 0 "OK: no direct wall-clock"

  reset; put "$K/domain/Money.kt" '/* Instant.now() */ fun at() = Instant.now(clock)\n'
  expect "a comment-only clock call beside injected code is clean" 0 "OK: no direct wall-clock"

  reset; put "$K/domain/Money.kt" '/* first line\n * Instant.now() is forbidden here\n */ fun at() = Instant.now(clock)\n'
  expect "a multiline comment-only clock call beside injected code is clean" 0 "OK: no direct wall-clock"

  reset; put "$K/domain/Money.kt" '/* outer /* inner */ Instant.now() */ fun at() = Instant.now(clock)\n'
  expect "a nested block comment with a clock-call spelling is clean" 0 "OK: no direct wall-clock"

  reset; put "$K/domain/Money.kt" 'fun at() = Instant.now(clock) /* not System.currentTimeMillis() */\n'
  expect "a trailing comment-only clock call is clean" 0 "OK: no direct wall-clock"

  # DERIVATION: an empty money_path_services must fail, not silently scan nothing. This is the
  # defect the derivation replaced — a short list read as a clean fleet.
  printf 'money_path_services: []\n' > "$td/openbank-libs/governance/rules.yaml"
  # The MESSAGE, not just the exit code: without the guard, `set -u` kills the script on the
  # empty array expansion, which is also non-zero. A crash and a verdict are both "red", and a
  # test that cannot tell them apart passes while the guard it checks is gone.
  expect "an empty money-path list FAILS with a stated reason" 1 "the scope moved"
  printf 'money_path_services:\n  - openbank-fixture-service\n' > "$td/openbank-libs/governance/rules.yaml"

  if [ "$fails" -gt 0 ]; then echo "self-test FAILED ($fails case(s))" >&2; exit 1; fi
  echo "self-test ok: clock-injection gate is falsifiable (30 cases, scope derived from rules.yaml)"
  exit 0
fi


# DERIVED from rules.yaml, never hand-kept here. The list used to be eight names written into
# this file while `rules.yaml: money_path_services` held 23 — so FIFTEEN money-path services
# were unwatched, and the gate reported "no direct wall-clock calls found in money-path
# services" about a scope that excluded two thirds of them. That is this repo's own rule about
# a gate whose SCOPE is a hand-kept list of the thing it checks: it reads as passing when the
# list is short, never as unchecked. Measured when widening it: zero new violations, so the
# fifteen were clean and this costs no debt — but nothing had established that.
MONEY_PATH_SERVICES=()
while IFS= read -r svc; do
  MONEY_PATH_SERVICES+=("$svc")
done < <(python3 -c '
import sys, yaml
doc = yaml.safe_load(open("openbank-libs/governance/rules.yaml")) or {}
svcs = doc.get("money_path_services") or []
if not svcs:
    sys.stderr.write("rules.yaml: money_path_services is empty or missing\n")
    sys.exit(1)
print("\n".join(svcs))
')

if [ "${#MONEY_PATH_SERVICES[@]}" -eq 0 ]; then
  # An empty scope is not "nothing to check" — it is the gate not running. Reporting a pass
  # here is exactly the shape the derivation above exists to remove.
  echo "::error::check-clock-injection: derived ZERO money-path services from rules.yaml —" \
       "the scope moved, the gate did not."
  exit 1
fi


# Layers that must never call wall-clock APIs directly
TARGET_LAYERS=(domain application)

VIOLATIONS=0
SCANNED=0

for svc in "${MONEY_PATH_SERVICES[@]}"; do
  for layer in "${TARGET_LAYERS[@]}"; do
    search_dir="${svc}/src/main/kotlin"
    # The layer directory can sit at any depth under the kotlin source root
    # (e.g. com/openbank/ledger/domain/), so we use find + grep per file.
    if [ ! -d "$search_dir" ]; then
      continue
    fi

    while IFS= read -r -d '' file; do
      # Check whether the file lives under a domain/ or application/ path segment
      if ! echo "$file" | grep -qE "/${layer}/"; then
        continue
      fi

      # Strip comments and quoted text, but retain executable string templates.
      # Keep the raw-string state across lines and code after `*/` on a line.
      matches=$(awk '
        function executable_code(line,    out, i, c, next_c, three) {
          out = ""
          escaped = 0
          for (i = 1; i <= length(line); i++) {
            c = substr(line, i, 1)
            next_c = substr(line, i + 1, 1)
            three = substr(line, i, 3)
            if (block_comment > 0) {
              if (c == "/" && next_c == "*") { block_comment++; i++ }
              else if (c == "*" && next_c == "/") { block_comment--; i++ }
              continue
            }
            if (string_mode != "") {
              if (string_mode == "normal" || string_mode == "char") {
                if (escaped) { escaped = 0; continue }
                if (c == "\\") { escaped = 1; continue }
              }
              if ((string_mode == "normal" || string_mode == "raw") && c == "$" && next_c == "{") {
                template_level++
                template_depth[template_level] = 1
                template_return[template_level] = string_mode
                string_mode = ""
                out = out " "
                i++
                continue
              }
              if (string_mode == "raw" && three == "\"\"\"") {
                string_mode = ""
                out = out " "
                i += 2
              } else if (string_mode == "normal" && c == "\"") {
                string_mode = ""
                out = out " "
              } else if (string_mode == "char" && c == "\047") {
                string_mode = ""
                out = out " "
              } else if (string_mode == "backtick" && c == "`") {
                string_mode = ""
                out = out " "
              }
              continue
            }
            if (three == "\"\"\"") { string_mode = "raw"; out = out " "; i += 2; continue }
            if (c == "\"") { string_mode = "normal"; out = out " "; continue }
            if (c == "\047") { string_mode = "char"; out = out " "; continue }
            if (c == "`") { string_mode = "backtick"; out = out " "; continue }
            if (c == "/" && next_c == "*") { block_comment = 1; i++; continue }
            if (c == "/" && next_c == "/") break
            if (template_level > 0) {
              if (c == "{") template_depth[template_level]++
              if (c == "}") {
                template_depth[template_level]--
                if (template_depth[template_level] == 0) {
                  string_mode = template_return[template_level]
                  delete template_depth[template_level]
                  delete template_return[template_level]
                  template_level--
                  out = out " "
                  continue
                }
              }
            }
            out = out c
          }
          return out
        }
        {
          code = executable_code($0)
          if (code ~ /(Instant|LocalDateTime|LocalDate)[[:space:]]*[.][[:space:]]*now[[:space:]]*[(][[:space:]]*[)]|System[[:space:]]*[.][[:space:]]*currentTimeMillis[[:space:]]*[(][[:space:]]*[)]/)
            print FNR ":" $0
        }
      ' "$file")
      SCANNED=$((SCANNED + 1))
      if [ -n "$matches" ]; then
        echo "VIOLATION [$svc/$layer] $file"
        echo "$matches"
        echo "---"
        VIOLATIONS=$((VIOLATIONS + 1))
      fi
    done < <(find "$search_dir" -type f -name "*.kt" -print0)
  done
done

echo "SUBJECTS=$SCANNED"

if [ "$VIOLATIONS" -gt 0 ]; then
  echo ""
  echo "ERROR: $VIOLATIONS file(s) contain direct wall-clock calls in domain/application layers."
  echo "Inject a Clock or ClockProvider instead (ADR-0100, Refs #1612)."
  exit 1
fi

echo "OK: no direct wall-clock calls found in domain/application layers of money-path services."
exit 0
