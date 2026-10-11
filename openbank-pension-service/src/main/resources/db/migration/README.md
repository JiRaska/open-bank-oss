# pension-service Flyway migrations

There is **no `V8`**, and that is intended. No `V8` was ever committed to this module (the history
holds none); `V9` was numbered while parallel ADR-0334 slices were still allocating versions, and
the number was never used. Flyway orders by version and does not require contiguous numbers, so
the gap is harmless.

Do **not** fill it and do not renumber `V9+`: Flyway checksums every applied file, and an
out-of-order `V8` added later is refused by default (`outOfOrder=false`). The next migration takes
the next free number after the highest one present.
