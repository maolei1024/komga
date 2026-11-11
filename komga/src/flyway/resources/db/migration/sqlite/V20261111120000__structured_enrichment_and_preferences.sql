CREATE TABLE BOOK_ENRICHMENT_SOURCE
(
    BOOK_ID varchar NOT NULL PRIMARY KEY REFERENCES BOOK(ID) ON DELETE CASCADE,
    INPUT_JSON text NOT NULL
);

INSERT INTO BOOK_ENRICHMENT_SOURCE (BOOK_ID, INPUT_JSON)
SELECT s.BOOK_ID, json_set(json_remove(s.INPUT_JSON, '$.existingTags', '$.legacyProcessed'),
    '$.tags', json(coalesce((SELECT json_group_array(json_object('type', json_extract(value, '$.type'), 'value', json_extract(value, '$.value')))
        FROM json_each(s.INPUT_JSON, '$.tags')), '[]')),
    '$.contentTagsKnown', json('true'), '$.contentTagsFromJson', json('true'),
    '$.sourceUrl', 'https://nhentai.net/g/' || json_extract(s.INPUT_JSON, '$.galleryId') || '/',
    '$.unclassifiedTags', json(coalesce((SELECT json_group_array(t.TAG) FROM BOOK_METADATA_TAG t
        WHERE t.BOOK_ID = s.BOOK_ID AND lower(t.TAG) NOT LIKE 'pagesize_%' AND lower(t.TAG) NOT LIKE 'tagsize_%'
        AND NOT EXISTS (SELECT 1 FROM json_each(s.RESULT_JSON, '$.mapping') m WHERE lower(m.value) = lower(t.TAG))
        AND NOT EXISTS (SELECT 1 FROM json_each(s.INPUT_JSON, '$.tags') r WHERE lower(json_extract(r.value, '$.value')) = lower(t.TAG))), '[]')))
FROM BOOK_ENRICHMENT_STATE s WHERE s.PROCESSOR = 'TAG_TRANSLATION';

INSERT OR IGNORE INTO BOOK_ENRICHMENT_SOURCE (BOOK_ID, INPUT_JSON)
SELECT b.ID, json_object(
    'sourceRevision', '', 'originalTitle', coalesce(bm.TITLE, ''), 'originalSeries', '',
    'summary', coalesce(bm.SUMMARY, ''), 'pageCount', coalesce(m.PAGE_COUNT, 0),
    'contentTagsKnown', json('false'),
    'tags', json(coalesce((SELECT json_group_array(json_object('type', 'artist', 'value', a.NAME))
        FROM BOOK_METADATA_AUTHOR a WHERE a.BOOK_ID = b.ID AND lower(trim(a.NAME)) NOT IN ('', 'unknown')), '[]')),
    'unclassifiedTags', json(coalesce((SELECT json_group_array(t.TAG) FROM BOOK_METADATA_TAG t
        WHERE t.BOOK_ID = b.ID AND lower(t.TAG) NOT LIKE 'pagesize_%' AND lower(t.TAG) NOT LIKE 'tagsize_%'), '[]'))
)
FROM BOOK b LEFT JOIN BOOK_METADATA bm ON bm.BOOK_ID = b.ID LEFT JOIN MEDIA m ON m.BOOK_ID = b.ID;

DELETE FROM BOOK_ENRICHMENT_STATE WHERE PROCESSOR IN ('TAG_TRANSLATION', 'PAGE_SIZE', 'TAG_SIZE');

DELETE FROM BOOK_METADATA_TAG WHERE lower(TAG) LIKE 'pagesize_%' OR lower(TAG) LIKE 'tagsize_%';
DELETE FROM SERIES_METADATA_TAG WHERE lower(TAG) LIKE 'pagesize_%' OR lower(TAG) LIKE 'tagsize_%';
DELETE FROM BOOK_METADATA_AGGREGATION_TAG WHERE lower(TAG) LIKE 'pagesize_%' OR lower(TAG) LIKE 'tagsize_%';

INSERT INTO BOOK_ENRICHMENT_STATE
    (BOOK_ID, PROCESSOR, STATUS, REVISION, INPUT_HASH, INPUT_JSON, CREATED_DATE, LAST_MODIFIED_DATE)
SELECT BOOK_ID, 'METADATA', 'STALE', 1, '', INPUT_JSON, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM BOOK_ENRICHMENT_SOURCE;

INSERT INTO BOOK_ENRICHMENT_STATE
    (BOOK_ID, PROCESSOR, STATUS, REVISION, INPUT_HASH, INPUT_JSON, RESULT_JSON, RESULT_REVISION, CREATED_DATE, LAST_MODIFIED_DATE)
SELECT s.BOOK_ID, 'AI_TITLE', 'SUCCESS', 1, '', s.INPUT_JSON, json_object('title', bm.TITLE), 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM BOOK_ENRICHMENT_SOURCE s JOIN BOOK_METADATA bm ON bm.BOOK_ID = s.BOOK_ID
WHERE true
ON CONFLICT (BOOK_ID, PROCESSOR) DO UPDATE SET
    STATUS = 'SUCCESS', RESULT_JSON = excluded.RESULT_JSON, RESULT_REVISION = BOOK_ENRICHMENT_STATE.REVISION;

ALTER TABLE BOOK_ENRICHMENT_STATE DROP COLUMN INPUT_JSON;

DELETE FROM SERVER_SETTINGS WHERE KEY = 'METADATA_ENRICHMENT_BOOTSTRAP_COMPLETED';

CREATE TABLE GORSE_PREFERENCE_PROFILE
(
    USER_ID varchar NOT NULL PRIMARY KEY,
    PROFILE_JSON text NOT NULL,
    BATCH_ID varchar,
    STATUS varchar NOT NULL DEFAULT 'DRAFT',
    ITEM_IDS_JSON text NOT NULL DEFAULT '[]',
    LAST_ERROR text,
    PROCESSED integer NOT NULL DEFAULT 0,
    TOTAL integer NOT NULL DEFAULT 0,
    APPLIED_PROFILE_JSON text,
    PREVIOUS_PROFILE_JSON text,
    UPDATED_DATE datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
);
