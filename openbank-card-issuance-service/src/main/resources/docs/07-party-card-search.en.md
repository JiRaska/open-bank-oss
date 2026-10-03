# Bounded party card reads

Customer 360 reads the newest cards for one exact party through a bounded repository path (1–100 rows). Graph reads have a separate bounded path (1–200 rows). Both order by `created_at DESC, id DESC` so equal timestamps have a stable order, and reject out-of-range limits instead of turning an investigation into an unbounded scan. Flyway V14 adds the party/recent-card index used by these queries. A party-scoped card result is an evidence reference; any account ID on it must not be treated as proof of account ownership without a source check.
