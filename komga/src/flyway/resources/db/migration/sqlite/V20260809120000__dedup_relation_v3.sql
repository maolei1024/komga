CREATE TABLE DEDUP_RELATION_NEW
(
    ID                       varchar  NOT NULL PRIMARY KEY,
    LIBRARY_ID               varchar  NOT NULL,
    BOOK_LOW_ID              varchar  NOT NULL,
    BOOK_HIGH_ID             varchar  NOT NULL,
    LOW_CONTENT_GENERATION   varchar  NOT NULL,
    HIGH_CONTENT_GENERATION  varchar  NOT NULL,
    RELATION_TYPE            varchar  NOT NULL,
    CONTAINED_BOOK_ID        varchar  NULL,
    CONTAINER_BOOK_ID        varchar  NULL,
    COVER_DISTANCE           integer  NULL,
    EVIDENCE_JSON            varchar  NOT NULL DEFAULT '{}',
    FEATURE_SCHEMA_VERSION   integer  NOT NULL,
    CLASSIFIER_RULE_VERSION  integer  NOT NULL,
    CREATED_DATE             datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    LAST_MODIFIED_DATE       datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (BOOK_LOW_ID, BOOK_HIGH_ID),
    CHECK (BOOK_LOW_ID < BOOK_HIGH_ID),
    CHECK (RELATION_TYPE IN ('COVER_CANDIDATE', 'EXACT_FILE', 'SAME_PAGE_SEQUENCE', 'CONTAINED_IN', 'AMBIGUOUS', 'NO_MATCH')),
    FOREIGN KEY (BOOK_LOW_ID) REFERENCES BOOK (ID) ON DELETE CASCADE,
    FOREIGN KEY (BOOK_HIGH_ID) REFERENCES BOOK (ID) ON DELETE CASCADE,
    FOREIGN KEY (LIBRARY_ID) REFERENCES LIBRARY (ID) ON DELETE CASCADE
);

DROP TABLE DEDUP_RELATION;
ALTER TABLE DEDUP_RELATION_NEW RENAME TO DEDUP_RELATION;

CREATE INDEX IDX__DEDUP_RELATION__LIBRARY_TYPE
    ON DEDUP_RELATION (LIBRARY_ID, RELATION_TYPE);

-- Relation and work rows are derived. Preserve expensive cover/page/archive features,
-- manual KEEP_BOTH decisions, clusters, and resolution audit history.
DELETE FROM DEDUP_WORK;

-- ExecuteDedupResolution Tasks live in the separate Tasks database and can survive a restart.
-- Freeze queued automatic resolutions in the main database so those stale Tasks become no-ops.
-- A partially deleted resolution remains explicitly retryable by an administrator.
UPDATE DEDUP_CLUSTER
SET STATUS = CASE
                 WHEN EXISTS (SELECT 1
                              FROM DEDUP_RESOLUTION_MEMBER M
                              WHERE M.RESOLUTION_ID = DEDUP_CLUSTER.LAST_RESOLUTION_ID
                                AND M.STATE IN ('DELETED', 'KOMGA_SAVED', 'GORSE_CONFIRMED', 'COMPLETED'))
                     THEN 'NEEDS_ATTENTION'
                 ELSE 'UNPROCESSED'
             END,
    REOPEN_REASON = 'AUTO_RESOLUTION_PAUSED_FOR_RELATION_V3',
    LAST_MODIFIED_DATE = CURRENT_TIMESTAMP
WHERE STATUS = 'PROCESSING'
  AND LAST_RESOLUTION_ID IN (SELECT ID
                             FROM DEDUP_RESOLUTION
                             WHERE STATE = 'PROCESSING'
                               AND ACTOR_ID = 'system:dedup-auto');

UPDATE DEDUP_RESOLUTION
SET STATE = CASE
                WHEN EXISTS (SELECT 1
                             FROM DEDUP_RESOLUTION_MEMBER M
                             WHERE M.RESOLUTION_ID = DEDUP_RESOLUTION.ID
                               AND M.STATE IN ('DELETED', 'KOMGA_SAVED', 'GORSE_CONFIRMED', 'COMPLETED'))
                    THEN 'PARTIALLY_COMPLETED'
                ELSE 'NEEDS_ATTENTION'
            END,
    RESULT_JSON = '{"code":"AUTO_RESOLUTION_PAUSED_FOR_RELATION_V3"}',
    LAST_MODIFIED_DATE = CURRENT_TIMESTAMP
WHERE STATE = 'PROCESSING'
  AND ACTOR_ID = 'system:dedup-auto';

-- The rebuilt evidence must be observed before physical deletion is explicitly re-enabled.
UPDATE DEDUP_LIBRARY_SETTINGS
SET AUTO_RESOLVE_SUGGESTIONS = false,
    LAST_MODIFIED_DATE = CURRENT_TIMESTAMP;

-- Seed every configured Library, including paused or disabled Libraries. Their work remains
-- queued until the Library is enabled and unpaused. Live Book events retain higher priority.
INSERT INTO DEDUP_WORK (ID, LIBRARY_ID, TYPE, TARGET_KEY, PRIORITY)
SELECT 'dedup-v3-scan-' || B.ID,
       B.LIBRARY_ID,
       'SCAN_BOOK',
       B.ID,
       0
FROM BOOK B
JOIN DEDUP_LIBRARY_SETTINGS S ON S.LIBRARY_ID = B.LIBRARY_ID
WHERE B.DELETED_DATE IS NULL
  AND (lower(B.URL) LIKE '%.cbz' OR lower(B.URL) LIKE '%.cbz?%');

INSERT INTO DEDUP_WORK (ID, LIBRARY_ID, TYPE, TARGET_KEY, PRIORITY)
SELECT 'dedup-v3-rebuild-' || S.LIBRARY_ID,
       S.LIBRARY_ID,
       'REBUILD_CLUSTERS',
       '',
       -1
FROM DEDUP_LIBRARY_SETTINGS S;
