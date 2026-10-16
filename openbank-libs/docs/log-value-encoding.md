# Encoding caller-controlled values in logs

The shared `com.openbank.libs.security` helpers have different contracts. Choose
one for the representation being written; neither decides whether a field is
appropriate to log.

## One-line display: `sanitizeForLog`

`String?.sanitizeForLog()` maps null to `-` and replaces every ISO control
character (C0, DEL and C1), Unicode line separator U+2028 and paragraph separator
U+2029 with `_`. Printable non-ASCII text remains intact. The replacement is
lossy: different original values can produce the same output. It is suitable for
one-line operational display, not for reconstructing an original identifier or
preserving audit-field identity. It does not escape spaces or equals signs and
therefore does not establish a `key=value` field boundary.

## One structured field: `logfmtValue`

`String?.logfmtValue()` encodes one value in a `key=value` message payload:

- Null is the unquoted `-` absent-field placeholder.
- A nonempty token containing only ASCII letters, digits, `.`, `_`, `:`, `/`,
  `@`, `+` and `-` remains bare, except that a literal single dash is quoted.
- Empty strings, spaces, equals signs and other characters cause double quoting.
  A literal dash becomes `"-"` and an empty value becomes `""`, so neither is
  confused with absence.
- Within quotes, backslash and double quote are escaped. Every control or line
  separator covered by `sanitizeForLog` is written as `\uXXXX`; printable
  non-ASCII characters are preserved inside the quoted value.

This representation is lossless for the original non-null string when decoded
with the corresponding escapes. It prevents the value from adding fields or
physical lines; it does not alter the field name chosen by the caller. A JSON log
encoder still leaves an embedded `key=value` payload inside a message string,
so JSON encoding alone does not replace this field-level contract.

The plain-token decision uses JDK `Pattern.matches` with the same full-string
ASCII character class. The helper does not cache a compiled `Pattern` and this
call performs regular-expression compilation/matching for each non-null,
non-dash input. This implementation choice preserves the encoding semantics; it
is not a throughput improvement or a benchmark result. Keep the existing
null/dash, quoting, control-character and round-trip regressions when changing it.

## Privacy and verification limits

Encoding is not redaction, anonymization or permission to log a value. Both
helpers retain printable personal or confidential content. Minimize fields at
the call site, preserve required audit attribution through its intended storage
path, and apply the log platform's access, retention and export controls.

Local encoding tests establish their asserted character and field-boundary
behavior. They do not prove collector delivery, access-control enforcement or
native CodeQL finding closure. A changed sink or sanitizer requires the actual
native scan result; do not infer closure from a helper name or implementation
shape alone. The separate `HoneytokenFilter` helper has a stricter allow-list
and truncation contract and is not replaced by either shared representation.
