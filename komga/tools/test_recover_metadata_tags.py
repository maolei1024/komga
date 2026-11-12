import hashlib
import json
import pathlib
import sqlite3
import tempfile
import unittest
import zipfile

from recover_metadata_tags import Resolver, apply_plan, build_plan, compact, read_archive, recover


DICTIONARY = [
    {'t': 'tag', 'k': 'blue eyes', 'v': '蓝眼'},
    {'t': 'tag', 'k': 'blue eye', 'v': '蓝眼'},
    {'t': 'tag', 'k': 'green eyes', 'v': '绿眼'},
    {'t': 'tag', 'k': 'emerald eyes', 'v': '绿眼'},
    {'t': 'tag', 'k': 'knight', 'v': '骑士'},
    {'t': 'parody', 'k': 'knight', 'v': 'Knight Game'},
    {'t': 'artist', 'k': 'author one', 'v': '作者甲'},
]


def source():
    return {'galleryId': 123, 'sourceRevision': '2026-01-01T00:00|123|', 'sourceUrl': 'https://example.com/g/123', 'originalTitle': 'Preserved title', 'originalSeries': 'Preserved series', 'summary': 'Preserved summary', 'pageCount': 30, 'tags': [{'type': 'artist', 'value': 'author one'}], 'contentTagsKnown': False, 'contentTagsFromJson': False, 'unclassifiedTags': ['蓝眼', 'Knight Game']}


def archive(tags='蓝眼,Knight Game'):
    return {'bookId': 'book', 'sourceRevision': source()['sourceRevision'], 'unchangedStat': True, 'xml': {'Tags': tags}}


class TagRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.resolver = Resolver(DICTIONARY, [], DICTIONARY, {'blue eyes', 'green eyes', 'emerald eyes'})
        self.book = {'id': 'book', 'url': 'file:/data/book.cbz', 'source': source(), 'currentTags': ['蓝眼', 'Knight Game'], 'resultJson': '{}'}

    def test_historical_untyped_parody_translation_recovers_the_content_key(self):
        recovered, mapping, methods = recover(self.book, archive(), self.resolver)
        self.assertTrue(recovered['contentTagsKnown'])
        self.assertIn({'type': 'tag', 'value': 'knight'}, recovered['tags'])
        self.assertIn({'type': 'tag', 'value': 'blue eyes'}, recovered['tags'])
        self.assertIn({'type': 'artist', 'value': 'author one'}, recovered['tags'])
        self.assertEqual(recovered['originalTitle'], self.book['source']['originalTitle'])
        self.assertEqual(mapping['tag:knight'], 'knight game')
        self.assertEqual(methods['catalog_alias'], 1)

    def test_ambiguous_content_identity_keeps_the_original_label(self):
        self.assertEqual(self.resolver.resolve('绿眼'), ({'type': 'tag', 'value': '绿眼'}, 'localized_content'))

    def test_classified_copy_resolves_aliases_before_catalog_preferences(self):
        copy = {'tags': [{'type': 'tag', 'value': 'blue eye'}]}
        recovered, _, _ = recover(self.book, archive(), self.resolver, copy)
        self.assertIn({'type': 'tag', 'value': 'blue eye'}, recovered['tags'])
        self.assertNotIn({'type': 'tag', 'value': 'blue eyes'}, recovered['tags'])
        self.assertTrue(recovered['contentTagsFromJson'])

    def test_archived_json_takes_priority_over_old_xml_and_preserves_unresolved_notes(self):
        evidence = dict(archive('artist_作者甲,蓝眼,Manual note'), json={'URL': 'https://example.com/g/123', 'tag': ['blue eye'], 'artist': ['new author'], 'group': [], 'character': [], 'category': ['manga']})
        recovered, _, _ = recover(self.book, evidence, self.resolver)
        self.assertTrue(recovered['contentTagsKnown'])
        self.assertTrue(recovered['contentTagsFromJson'])
        self.assertEqual([tag['value'] for tag in recovered['tags'] if tag['type'] == 'tag'], ['blue eye'])
        self.assertEqual([tag['value'] for tag in recovered['tags'] if tag['type'] == 'artist'], ['new author'])
        self.assertIn('manual note', recovered['unclassifiedTags'])

    def test_stale_or_different_gallery_json_is_not_trusted(self):
        for evidence in [dict(archive(), sourceRevision='outdated', json={'tag': ['blue eye']}), dict(archive(), json={'URL': 'https://example.com/g/456', 'tag': ['blue eye']})]:
            recovered, _, _ = recover(self.book, evidence, self.resolver)
            self.assertFalse(recovered['contentTagsFromJson'])

    def test_source_api_requires_matching_identity_and_is_lower_priority_than_embedded_json(self):
        evidence = dict(archive(), retrievedJson={'URL': 'https://example.com/g/123', 'tag': ['blue eye']})
        recovered, _, _ = recover(self.book, evidence, self.resolver)
        self.assertTrue(recovered['contentTagsFromJson'])
        self.assertEqual([tag['value'] for tag in recovered['tags'] if tag['type'] == 'tag'], ['blue eye'])
        evidence['json'] = {'tag': ['knight']}
        recovered, _, _ = recover(self.book, evidence, self.resolver)
        self.assertEqual([tag['value'] for tag in recovered['tags'] if tag['type'] == 'tag'], ['knight'])
        for metadata in [{'tag': ['blue eye']}, {'URL': 'https://example.com/g/456', 'tag': ['blue eye']}]:
            recovered, _, _ = recover(self.book, dict(archive(), retrievedJson=metadata), self.resolver)
            self.assertFalse(recovered['contentTagsFromJson'])

    def test_unresolved_tokens_are_preserved_and_counted_only_by_fallback(self):
        self.book['currentTags'] += ['Unmapped', 'UNMAPPED', 'pageSize_31-50', 'tagSize_50+']
        recovered, _, _ = recover(self.book, archive(), self.resolver)
        self.assertFalse(recovered['contentTagsKnown'])
        self.assertEqual(recovered['unclassifiedTags'], ['unmapped'])
        self.assertEqual(len(recovered['fallbackTags']), 3)
        self.assertNotIn({'type': 'tag', 'value': 'unmapped'}, recovered['tags'])

    def test_explicit_author_namespace_never_becomes_a_content_tag(self):
        self.assertEqual(self.resolver.resolve('artist_作者甲')[0], {'type': 'artist', 'value': 'author one'})
        self.assertIsNone(self.resolver.resolve('artist_Unknown')[0])

    def test_missing_or_stale_xml_does_not_claim_complete_recovery(self):
        for evidence in [None, {'bookId': 'book', 'error': 'BadZipFile'}, dict(archive(), sourceRevision='outdated')]:
            recovered, _, _ = recover(self.book, evidence, self.resolver)
            self.assertFalse(recovered['contentTagsKnown'])
            self.assertEqual(len(recovered['fallbackTags']), 2)

    def test_archive_inspection_does_not_rewrite_cbz_and_rejects_escape(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'book.cbz'
            with zipfile.ZipFile(path, 'w') as target:
                target.writestr('ComicInfo.xml', '<ComicInfo><Tags>蓝眼</Tags><Writer>author one</Writer></ComicInfo>')
                target.writestr('001.jpg', b'image bytes')
            before = hashlib.sha256(path.read_bytes()).hexdigest()
            result = read_archive(self.book, directory)
            self.assertTrue(result['unchangedStat'])
            self.assertEqual(result['xml']['Tags'], '蓝眼')
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), before)
            self.assertIn('error', read_archive(dict(self.book, url='file:/data/../../etc/passwd'), directory))

    def test_valid_json_can_recover_independently_of_missing_or_damaged_xml(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'book.cbz'
            for xml_value in [None, 'broken']:
                with zipfile.ZipFile(path, 'w') as target:
                    target.writestr('metadata.json', json.dumps({'tag': ['blue eye'], 'artist': ['author one']}))
                    if xml_value is not None:
                        target.writestr('ComicInfo.xml', xml_value)
                evidence = read_archive(self.book, directory)
                recovered, _, _ = recover(self.book, evidence, self.resolver)
                self.assertTrue(recovered['contentTagsKnown'])
                self.assertTrue(recovered['contentTagsFromJson'])


class RecoveryTransactionTest(unittest.TestCase):
    def setUp(self):
        self.database = sqlite3.connect(':memory:')
        self.database.executescript("CREATE TABLE BOOK (ID TEXT PRIMARY KEY,URL TEXT,SERIES_ID TEXT,DELETED_DATE TEXT); CREATE TABLE BOOK_METADATA (BOOK_ID TEXT PRIMARY KEY,TAGS_LOCK INTEGER); CREATE TABLE BOOK_METADATA_TAG (BOOK_ID TEXT,TAG TEXT); CREATE TABLE BOOK_ENRICHMENT_SOURCE (BOOK_ID TEXT PRIMARY KEY,INPUT_JSON TEXT); CREATE TABLE BOOK_ENRICHMENT_STATE (BOOK_ID TEXT,PROCESSOR TEXT,REVISION INTEGER,RESULT_JSON TEXT,STATUS TEXT,INPUT_HASH TEXT,RESULT_REVISION INTEGER,LAST_ERROR TEXT,STARTED_DATE TEXT,COMPLETED_DATE TEXT,LAST_MODIFIED_DATE TEXT); CREATE TABLE SERVER_SETTINGS (KEY TEXT,VALUE TEXT); CREATE TABLE READ_PROGRESS (BOOK_ID TEXT,PAGE INTEGER); CREATE TABLE MEDIA_FILE (BOOK_ID TEXT,FILE_NAME TEXT);")
        self.database.execute("INSERT INTO BOOK VALUES ('book','file:/data/book.cbz','series',NULL)")
        self.database.execute("INSERT INTO BOOK_METADATA VALUES ('book',1)")
        self.database.executemany("INSERT INTO BOOK_METADATA_TAG VALUES ('book',?)", [('蓝眼',), ('Knight Game',)])
        self.database.execute("INSERT INTO BOOK_ENRICHMENT_SOURCE VALUES ('book',?)", (compact(source()),))
        self.database.execute("INSERT INTO BOOK_ENRICHMENT_STATE (BOOK_ID,PROCESSOR,REVISION,RESULT_JSON,STATUS,INPUT_HASH,RESULT_REVISION) VALUES ('book','METADATA',3,'{}','SUCCESS','old',3)")
        self.database.execute("INSERT INTO BOOK_ENRICHMENT_STATE (BOOK_ID,PROCESSOR,REVISION,RESULT_JSON,STATUS,INPUT_HASH,RESULT_REVISION) VALUES ('book','AI_TITLE',7,'{}','STALE','title',7)")
        self.database.execute("INSERT INTO READ_PROGRESS VALUES ('book',12)")
        self.database.execute("INSERT INTO MEDIA_FILE VALUES ('book','ComicInfo.xml')")
        self.database.executemany('INSERT INTO SERVER_SETTINGS VALUES (?,?)', [('METADATA_ENRICHMENT_PAGE_SIZE_BUCKETS', compact([{'min': 1, 'max': None, 'label': 'pageSize_1+'}])), ('METADATA_ENRICHMENT_TAG_SIZE_BUCKETS', compact([{'min': 0, 'max': 5, 'label': 'tagSize_0-5'}, {'min': 6, 'max': None, 'label': 'tagSize_6+'}]))])
        self.database.commit()

    def tearDown(self):
        self.database.close()

    def plan(self):
        return build_plan(self.database, compact(DICTIONARY).encode(), b'[]', DICTIONARY, [archive()])

    def test_application_preserves_ids_manual_tags_reading_and_ai_state(self):
        plan = self.plan()
        before = {table: self.database.execute('SELECT * FROM ' + table).fetchall() for table in ['BOOK', 'BOOK_METADATA', 'BOOK_METADATA_TAG', 'READ_PROGRESS']}
        ai_before = self.database.execute("SELECT * FROM BOOK_ENRICHMENT_STATE WHERE PROCESSOR='AI_TITLE'").fetchone()
        apply_plan(self.database, plan)
        for table, rows in before.items():
            self.assertEqual(self.database.execute('SELECT * FROM ' + table).fetchall(), rows)
        self.assertEqual(self.database.execute("SELECT * FROM BOOK_ENRICHMENT_STATE WHERE PROCESSOR='AI_TITLE'").fetchone(), ai_before)
        metadata = self.database.execute("SELECT STATUS,REVISION,RESULT_REVISION FROM BOOK_ENRICHMENT_STATE WHERE PROCESSOR='METADATA'").fetchone()
        self.assertEqual(tuple(metadata), ('SUCCESS', 4, 4))
        self.assertEqual(self.plan()['changes'], [])

    def test_stale_plan_is_rejected_without_partial_updates(self):
        plan = self.plan()
        self.database.execute("INSERT INTO BOOK_METADATA_TAG VALUES ('book','Manual edit')")
        self.database.commit()
        with self.assertRaises(ValueError):
            apply_plan(self.database, plan)
        self.assertFalse(json.loads(self.database.execute('SELECT INPUT_JSON FROM BOOK_ENRICHMENT_SOURCE').fetchone()[0])['contentTagsKnown'])


if __name__ == '__main__':
    unittest.main()
