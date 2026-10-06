# Device list scope

The subject-access path can return the complete list of one party's device tokens. Customer 360 uses a separate database-bounded newest slice, ordered by creation time and ID, rather than loading the full set and trimming it in memory. Flyway V18 adds an index for this party/recent-device read. A device token is sensitive credential material and must not be copied into Context projection or its audit disclosure list.
