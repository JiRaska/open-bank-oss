# shellcheck shell=bash
# -----------------------------------------------------------------------------
# The ONE ADR front-matter parser. Sourced by docs/adr/gen-index.sh and by
# .github/scripts/check-adr-registry.sh — deliberately shared, because the whole
# point of the schema (docs/adr/SCHEMA.md) is that the generator and the validator
# can never disagree about what a field says. Two parsers is how the previous
# three-conventions-and-a-fallback-regex situation came about.
#
# Not a YAML engine, on purpose: the schema is restricted to flat `key: value`
# lines and inline `[a, b]` flow sequences precisely so this can stay ~40 lines of
# awk and run in seconds on a docs-only PR with no runtime to install.
#
# Contract:
#   fm_extract FILE   -> stdout: one `key<TAB>value` line per front-matter key,
#                        in file order. Exit 0 = parsed. Exit 3 = no front-matter
#                        block (missing opening `---`). Exit 4 = unterminated block.
#                        Malformed lines are emitted as `!malformed<TAB><the line>`
#                        so the CALLER decides whether that is fatal — the validator
#                        reports it, the generator degrades gracefully.
#   fm_field FM KEY   -> stdout: the value for KEY out of a captured fm_extract
#                        blob, or empty. Distinguishes absent from empty via
#                        fm_has.
#   fm_has   FM KEY   -> exit 0 if KEY is present at all (even with an empty value),
#                        1 if it is genuinely absent, and 2 if the blob could not be
#                        READ (the scanner itself failed). 1 and 2 must never be
#                        collapsed — see "NEVER PIPE INTO AN EARLY-EXIT CONSUMER".
#   fm_list  VALUE    -> stdout: one element per line for an inline `[a, b]`
#                        sequence; nothing at all for `[]`.
#   fm_unquote VALUE  -> stdout: VALUE with one layer of surrounding double quotes
#                        removed and `\"` unescaped.
#
# NEVER PIPE INTO AN EARLY-EXIT CONSUMER (the defect this shape exists to prevent)
# ------------------------------------------------------------------------------
# These helpers used to be `printf '%s\n' "$FM" | awk '... { exit }'`. The awk side
# exits on the FIRST match, closing the read end of the pipe while printf may still
# be writing — printf takes SIGPIPE, and because every caller runs under
# `set -o pipefail`, the pipeline's status becomes 141. The caller's
# `fm_has "$fm" "$k" || err "... missing required key '$k'."` then reports a key that
# is demonstrably PRESENT as missing. Observed on PR #2543 (run 30195145028) against
# 0107-convert-pocket-balance-to-primary.md, a file whose `authors` key is right
# there, on a branch touching no ADR at all; the tell in the log is
# `lib-frontmatter.sh: line 65: printf: write error: Broken pipe` immediately above
# the bogus `::error::`. Note the failure DIRECTION: not a crash, but a specific,
# plausible, actionable-looking accusation against a correct file — the worst
# possible output, because someone "fixes" the ADR or learns to ignore the gate.
#
# So: read the blob with a here-string (`<<< "$1"`), never a pipe. A here-string is
# a regular file descriptor, so an early `exit` in awk cannot signal the writer,
# and there is no pipeline for `pipefail` to poison. Any awk status above 1 is a
# READ failure and is surfaced as such, never as a schema verdict.
# -----------------------------------------------------------------------------

# The line grammar, as ONE awk function shared verbatim by both readers below —
# the per-file one the validator uses, and the whole-fleet one the generator uses.
# Whatever a key/value line means, it means the same thing to both. That is the
# invariant this file exists to hold, and the reason the batch reader is not a second
# parser. `emit(k, v)` is supplied by each reader.
FM_AWK_GRAMMAR='
function fm_line(line,   p, k, v) {
  # Blank lines and comments inside the block are tolerated but carry nothing.
  if (line ~ /^[ \t]*$/) return
  if (line ~ /^[ \t]*#/) return
  p = index(line, ":")
  # A key must be flat and left-anchored. Anything else (indentation => nesting,
  # "- " => a block sequence, no colon => a stray line or a block scalar
  # continuation) is malformed under this schema and is reported, not guessed at.
  if (p < 2 || line ~ /^[ \t]/ || line ~ /^-/) { emit("!malformed", line); return }
  k = substr(line, 1, p - 1)
  if (k !~ /^[a-z][a-z0-9-]*$/) { emit("!malformed", line); return }
  v = substr(line, p + 1)
  sub(/^[ \t]+/, "", v)
  sub(/[ \t]+$/, "", v)
  emit(k, v)
}'

fm_extract() {
  awk "$FM_AWK_GRAMMAR"'
    function emit(k, v) { print k "\t" v }
    NR == 1 {
      if ($0 != "---") { exit 3 }   # no front-matter block at all
      next
    }
    $0 == "---" { closed = 1; exit 0 }
    { fm_line($0) }
    END { if (!closed) exit 4 }     # opening --- but never closed
  ' "$1"
}

# The whole fleet in ONE awk process. Emits `FILE<TAB>key<TAB>value` in file order,
# then two synthetic keys per file:
#   !h1      the first `# ` line, verbatim (the generator derives the title from it)
#   !status  `ok`, or `3` / `4` — the same conditions fm_extract exits 3 and 4 on.
# The caller decides what a bad status means, exactly as with fm_extract.
#
# WHY THIS EXISTS: gen-index.sh ran fm_extract once per ADR and grepped each file for
# its H1 — 454 processes for 227 ADRs. Once the helpers below stopped forking, that
# was ALL that remained of its runtime (9 s + 9 s of 24 s, measured). Same parse, two
# processes instead of 454.
#
# Unlike fm_extract this never exits early: one malformed file must not truncate the
# other 226. It also reads each file to the end, because the H1 follows the block.
# No gawk extensions (no ENDFILE) — macOS ships BSD awk.
fm_extract_many() {
  awk "$FM_AWK_GRAMMAR"'
    function emit(k, v) { print FILENAME "\t" k "\t" v }
    function flush() {
      if (prev == "") return
      if (st == "ok" && !closed) st = "4"
      print prev "\t!h1\t" h1
      print prev "\t!status\t" st
    }
    FNR == 1 {
      flush()
      prev = FILENAME; h1 = ""; closed = 0; inblock = 0; st = "ok"
      if ($0 == "---") { inblock = 1 } else { st = "3" }
      next
    }
    {
      if (inblock) {
        if ($0 == "---") { inblock = 0; closed = 1; next }
        fm_line($0)
        next
      }
      if (h1 == "" && substr($0, 1, 2) == "# ") h1 = $0
    }
    END { flush() }
  ' "$@"
}

# PURE BASH BELOW THIS LINE — no awk, sed, tr or grep. That is a performance property
# with a correctness consequence, so it is worth stating plainly.
#
# These four are called ~14 times per ADR by gen-index.sh and ~10 times by
# check-adr-registry.sh, and every one of them used to fork: `fm_list` alone was a
# four-stage pipeline, so one ADR cost roughly thirty processes. Measured over 40 ADRs,
# field extraction was 88% of gen-index.sh's runtime; reading every file was most of
# the rest. They are pure string operations over a blob already in memory — nothing
# here ever needed a process.
#
# The PARSING is still done once, by the shared awk grammar above; these only slice its
# output. There is still exactly one implementation of the schema.
#
# It also removes the SIGPIPE class described above by construction rather than by
# discipline: with no pipes and no external readers, there is no early-exit consumer to
# signal a writer, so no caller can be handed a 141 dressed up as a verdict.

fm_field() {
  local line
  while IFS= read -r line; do
    if [[ "${line%%$'\t'*}" == "$2" ]]; then
      # Only the FIRST tab separates key from value; a value may contain tabs.
      printf '%s\n' "${line#*$'\t'}"
      return 0
    fi
  done <<< "$1"
  return 0
}

# 0 = present, 1 = genuinely absent. The third state — 2, "the blob could not be READ"
# — is now unreachable BY CONSTRUCTION: there is no scanner left to fail, so a tool
# failure can no longer be mistaken for a missing key. Callers keep handling 2
# (check-adr-registry.sh does); it costs nothing and documents a distinction that must
# never be collapsed if a reader is ever reintroduced here.
fm_has() {
  local line
  while IFS= read -r line; do
    [[ "${line%%$'\t'*}" == "$2" ]] && return 0
  done <<< "$1"
  return 1
}

# `[a, b]` -> one element per line. `[]` -> no output. Elements are trimmed; empty
# elements (a trailing comma, `[ , ]`) are dropped rather than emitted as blanks,
# so callers can count lines and get the real element count.
fm_list() {
  local v=$1 elem parts=()
  v=${v#'['}
  v=${v%']'}
  local IFS=','
  # `read -ra`, not `for elem in $v`: unquoted word-splitting also glob-expands, so an
  # element containing * or ? would silently become a list of filenames.
  read -ra parts <<< "$v"
  IFS=$' \t\n'
  # `${parts[@]+"${parts[@]}"}`, not a bare `"${parts[@]}"`: under `set -u`, bash 3.2
  # (the macOS default) treats an EMPTY array's expansion as unbound and aborts, and
  # every `[]` field in the fleet hits that. Worth recording how it was caught, because
  # it very nearly was not: the abort happened inside a command substitution before
  # gen-index.sh wrote anything, so the committed artefacts were left untouched — and
  # the byte-identical check therefore PASSED, comparing the originals against
  # themselves. A generator that produces nothing looks exactly like one that
  # reproduces its input. Always check the run reached its last line.
  for elem in ${parts[@]+"${parts[@]}"}; do
    elem="${elem#"${elem%%[![:space:]]*}"}"   # ltrim
    elem="${elem%"${elem##*[![:space:]]}"}"   # rtrim
    elem=${elem#\"}
    elem=${elem%\"}
    [[ -n "$elem" ]] && printf '%s\n' "$elem"
  done
  return 0
}

# One layer of surrounding double quotes, and `\"` back to `"`. Left alone if the
# value is not quoted, so the caller can tell the difference and reject it.
fm_unquote() {
  local v=$1
  v=${v#\"}
  v=${v%\"}
  printf '%s\n' "${v//\\\"/\"}"
}
