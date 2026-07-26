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

fm_extract() {
  awk '
    NR == 1 {
      if ($0 != "---") { exit 3 }   # no front-matter block at all
      next
    }
    $0 == "---" { closed = 1; exit 0 }
    # Blank lines and comments inside the block are tolerated but carry nothing.
    /^[ \t]*$/ { next }
    /^[ \t]*#/ { next }
    {
      p = index($0, ":")
      # A key must be flat and left-anchored. Anything else (indentation => nesting,
      # "- " => a block sequence, no colon => a stray line or a block scalar
      # continuation) is malformed under this schema and is reported, not guessed at.
      if (p < 2 || $0 ~ /^[ \t]/ || $0 ~ /^-/) {
        print "!malformed\t" $0
        next
      }
      k = substr($0, 1, p - 1)
      if (k !~ /^[a-z][a-z0-9-]*$/) { print "!malformed\t" $0; next }
      v = substr($0, p + 1)
      sub(/^[ \t]+/, "", v)
      sub(/[ \t]+$/, "", v)
      print k "\t" v
    }
    END { if (!closed) exit 4 }     # opening --- but never closed
  ' "$1"
}

fm_field() {
  awk -F'\t' -v k="$2" '$1 == k { print $2; exit }' <<< "$1"
}

# 0 = present, 1 = genuinely absent, 2 = the blob could not be read. The caller MUST
# distinguish 1 from 2: only 1 is a statement about the ADR.
fm_has() {
  local rc=0
  awk -F'\t' -v k="$2" '$1 == k { found = 1; exit } END { exit !found }' <<< "$1" || rc=$?
  if (( rc > 1 )); then
    echo "::error title=ADR front-matter::fm_has: could not READ front-matter while looking for key '$2' (scanner exited $rc). This is a tool failure, NOT a missing key." >&2
    return 2
  fi
  return "$rc"
}

# `[a, b]` -> one element per line. `[]` -> no output. Elements are trimmed; empty
# elements (a trailing comma, `[ , ]`) are dropped rather than emitted as blanks,
# so callers can count lines and get the real element count.
fm_list() {
  sed -E 's/^\[//; s/\]$//' <<< "$1" \
    | tr ',' '\n' \
    | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//; s/^"//; s/"$//' \
    | grep -v '^$' || true
}

# One layer of surrounding double quotes, and `\"` back to `"`. Left alone if the
# value is not quoted, so the caller can tell the difference and reject it.
fm_unquote() {
  sed -E 's/^"//; s/"$//; s/\\"/"/g' <<< "$1"
}
