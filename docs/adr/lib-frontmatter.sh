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
#   fm_has   FM KEY   -> exit 0 if KEY is present at all (even with an empty value).
#   fm_list  VALUE    -> stdout: one element per line for an inline `[a, b]`
#                        sequence; nothing at all for `[]`.
#   fm_unquote VALUE  -> stdout: VALUE with one layer of surrounding double quotes
#                        removed and `\"` unescaped.
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
  printf '%s\n' "$1" | awk -F'\t' -v k="$2" '$1 == k { print $2; exit }'
}

fm_has() {
  printf '%s\n' "$1" | awk -F'\t' -v k="$2" '$1 == k { found = 1; exit } END { exit !found }'
}

# `[a, b]` -> one element per line. `[]` -> no output. Elements are trimmed; empty
# elements (a trailing comma, `[ , ]`) are dropped rather than emitted as blanks,
# so callers can count lines and get the real element count.
fm_list() {
  printf '%s\n' "$1" \
    | sed -E 's/^\[//; s/\]$//' \
    | tr ',' '\n' \
    | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//; s/^"//; s/"$//' \
    | grep -v '^$' || true
}

# One layer of surrounding double quotes, and `\"` back to `"`. Left alone if the
# value is not quoted, so the caller can tell the difference and reject it.
fm_unquote() {
  printf '%s\n' "$1" | sed -E 's/^"//; s/"$//; s/\\"/"/g'
}
