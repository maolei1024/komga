import argparse
import collections
import concurrent.futures
import datetime
import hashlib
import json
import pathlib
import re
import sqlite3
import urllib.parse
import xml.etree.ElementTree as xml
import zipfile


def normalize(value):
    return str(value).strip().lower()


def technical(value):
    return normalize(value).startswith(('pagesize_', 'tagsize_'))


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def tag_key(tag):
    return normalize(tag['type']) + ':' + normalize(tag['value'])


def json_classification(metadata, gallery_id, require_id=False):
    if not isinstance(metadata, dict) or not isinstance(metadata.get('tag'), list) or not all(isinstance(value, str) for value in metadata['tag']):
        return None
    match = re.search(r'/g/(\d+)', str(metadata.get('URL') or metadata.get('url') or ''))
    if require_id and (match is None or gallery_id is None):
        return None
    if match and gallery_id is not None and int(match[1]) != gallery_id:
        return None
    tags = []
    kinds = set()
    for kind, field in [('tag', 'tag'), ('artist', 'artist'), ('group', 'group'), ('character', 'character'), ('parody', 'parody'), ('genre', 'genre' if 'genre' in metadata else 'category')]:
        values = metadata.get(field)
        if isinstance(values, list) and all(isinstance(value, str) for value in values):
            kinds.add(kind)
            tags.extend({'type': kind, 'value': value.strip()} for value in values if normalize(value) not in ('', 'unknown') and not technical(value))
    return {'tags': tags, 'kinds': kinds}


def normalized_revision(source, files):
    parts = source['sourceRevision'].split('|')
    if len(parts) < 3:
        raise ValueError('Source revision is missing archive identity')
    metadata_files = sorted({normalize(name) for name in files if normalize(name) in ('comicinfo.xml', 'metadata.json')})
    return '|'.join(parts[:3] + [','.join(metadata_files)])


class Resolver:
    def __init__(self, dictionary, overrides, historical, trusted_tags):
        self.types = collections.defaultdict(set)
        self.translations = {}
        self.reverse = collections.defaultdict(set)
        self.trusted_tags = set(trusted_tags)
        for entry in dictionary + overrides:
            kind, original, translated = normalize(entry.get('t', 'tag')), normalize(entry['k']), entry['v'].strip()
            self.types[original].add(kind)
            self.translations[kind + ':' + original] = translated
            self.reverse[(kind, normalize(translated))].add(original)
        forward = {}
        original_keys = set()
        for entry in historical:
            kind = normalize(entry.get('t', 'tag'))
            if kind not in ('tag', 'parody'):
                continue
            original, translated = normalize(entry['k']), entry['v'].strip()
            forward[original] = translated
            forward.setdefault(normalize(translated), translated)
            original_keys.add(original)
        self.historical = collections.defaultdict(set)
        for original in original_keys:
            if 'tag' in self.types[original]:
                self.historical[normalize(forward[original])].add(original)

    def resolve(self, value, trusted_book_tags=()):
        token = normalize(value)
        if not token or technical(token):
            return None, 'technical'
        for kind in ('artist', 'group', 'character', 'parody', 'genre'):
            for separator in ('_', ':'):
                prefix = kind + separator
                if token.startswith(prefix):
                    original = token[len(prefix):].strip()
                    if original in ('', 'unknown'):
                        return None, 'technical'
                    candidates = self.reverse[(kind, original)]
                    if kind in self.types[original]:
                        return {'type': kind, 'value': original}, 'explicit_namespace'
                    if len(candidates) == 1:
                        return {'type': kind, 'value': next(iter(candidates))}, 'explicit_namespace'
                    return {'type': kind, 'value': original}, 'explicit_namespace'
        if 'tag' in self.types[token]:
            return {'type': 'tag', 'value': token}, 'raw_key'
        candidates = self.historical[token] or self.reverse[('tag', token)]
        book_matches = candidates & set(trusted_book_tags)
        if len(book_matches) == 1:
            return {'type': 'tag', 'value': next(iter(book_matches))}, 'classified_copy'
        if len(candidates) == 1:
            return {'type': 'tag', 'value': next(iter(candidates))}, 'dictionary_unique'
        corpus_matches = candidates & self.trusted_tags
        if len(corpus_matches) == 1:
            return {'type': 'tag', 'value': next(iter(corpus_matches))}, 'catalog_alias'
        if candidates:
            return {'type': 'tag', 'value': token}, 'localized_content'
        return None, 'unresolved'


def recover(book, archive, resolver, classified_copy=None):
    original = book['source']
    source = dict(original)
    source['fallbackTags'] = None
    evidence_valid = archive is not None and 'error' not in archive and archive.get('unchangedStat') is True and archive.get('sourceRevision') == original['sourceRevision']
    authoritative = json_classification(archive.get('json'), original.get('galleryId')) if evidence_valid else None
    if authoritative is None and classified_copy is not None:
        authoritative = {'tags': classified_copy['tags'], 'kinds': {normalize(tag['type']) for tag in classified_copy['tags']} | {'tag'}}
    if authoritative is None and evidence_valid:
        authoritative = json_classification(archive.get('retrievedJson'), original.get('galleryId'), require_id=True)
    authoritative_kinds = authoritative['kinds'] if authoritative is not None else set()
    retained = {tag_key(tag): dict(tag) for tag in original['tags'] if normalize(tag['type']) != 'tag' and normalize(tag['type']) not in authoritative_kinds}
    trusted = set()
    if authoritative is not None:
        for tag in authoritative['tags']:
            retained[tag_key(tag)] = dict(tag)
            if normalize(tag['type']) == 'tag':
                trusted.add(normalize(tag['value']))
    archive_valid = evidence_valid and archive['xml'].get('Tags') is not None
    xml_tokens = []
    if archive_valid:
        xml_tokens = (archive['xml'].get('Tags') or '').split(',')
    tokens = sorted({normalize(value) for value in xml_tokens + book['currentTags'] + original.get('unclassifiedTags', []) if normalize(value) and not technical(value)})
    unresolved = set()
    methods = collections.Counter()
    display = {}
    for token in tokens:
        tag, method = resolver.resolve(token, trusted)
        methods[method] += 1
        if tag is None:
            if method == 'unresolved':
                unresolved.add(token)
            continue
        key = tag_key(tag)
        if authoritative is not None:
            if tag['type'] == 'tag' and normalize(tag['value']) not in trusted:
                unresolved.add(token)
                continue
            if tag['type'] != 'tag' and tag['type'] in authoritative_kinds and key not in retained:
                continue
        retained[key] = tag
        display.setdefault(key, token)
    for tag in original['tags']:
        if normalize(tag['type']) == 'tag':
            normalized, method = resolver.resolve(tag['value'], trusted)
            if normalized is not None and (authoritative is None or normalize(normalized['value']) in trusted or normalized['type'] != 'tag' and normalized['type'] not in authoritative_kinds):
                retained[tag_key(normalized)] = normalized
            elif normalized is None or normalized['type'] == 'tag':
                unresolved.add(normalize(tag['value']))
    source['tags'] = [retained[key] for key in sorted(retained)]
    source['contentTagsKnown'] = authoritative is not None or not unresolved and archive_valid
    source['contentTagsFromJson'] = authoritative is not None
    source['unclassifiedTags'] = sorted(unresolved)
    if not source['contentTagsKnown']:
        source['fallbackTags'] = sorted({normalize(value) for value in book['currentTags'] if normalize(value) and not technical(value)})
    mapping = {}
    previous_mapping = json.loads(book.get('resultJson') or '{}').get('mapping', {})
    for tag in source['tags']:
        key = tag_key(tag)
        mapping[key] = display.get(key) or previous_mapping.get(key) or resolver.translations.get(key) or tag['value']
    return source, mapping, dict(methods)


def bucket(value, definition):
    return next(entry['label'].lower() for entry in definition if value >= entry['min'] and (entry['max'] is None or value <= entry['max']))


def source_json(source):
    names = ['galleryId', 'sourceRevision', 'sourceUrl', 'originalTitle', 'originalSeries', 'summary', 'pageCount', 'tags', 'contentTagsKnown', 'contentTagsFromJson', 'unclassifiedTags', 'fallbackTags']
    return compact({name: source.get(name) for name in names})


def load_books(database):
    database.row_factory = sqlite3.Row
    rows = database.execute("SELECT b.ID,b.URL,b.SERIES_ID,bm.TAGS_LOCK,s.INPUT_JSON,e.REVISION,e.RESULT_JSON FROM BOOK b JOIN BOOK_METADATA bm ON bm.BOOK_ID=b.ID JOIN BOOK_ENRICHMENT_SOURCE s ON s.BOOK_ID=b.ID JOIN BOOK_ENRICHMENT_STATE e ON e.BOOK_ID=b.ID AND e.PROCESSOR='METADATA' WHERE b.DELETED_DATE IS NULL").fetchall()
    tags = collections.defaultdict(list)
    for row in database.execute('SELECT BOOK_ID,TAG FROM BOOK_METADATA_TAG ORDER BY TAG'):
        tags[row[0]].append(row[1])
    files = collections.defaultdict(list)
    for row in database.execute('SELECT BOOK_ID,FILE_NAME FROM MEDIA_FILE'):
        files[row[0]].append(row[1])
    return [{'id': row['ID'], 'url': row['URL'], 'seriesId': row['SERIES_ID'], 'source': json.loads(row['INPUT_JSON']), 'originalJson': row['INPUT_JSON'], 'revision': row['REVISION'], 'resultJson': row['RESULT_JSON'], 'currentTags': tags[row['ID']], 'metadataFiles': files[row['ID']], 'tagsLock': row['TAGS_LOCK']} for row in rows]


def read_archive(book, media_root):
    try:
        container_path = urllib.parse.unquote(urllib.parse.urlparse(book['url']).path)
        if not container_path.startswith('/data/'):
            raise ValueError('Archive is outside the media mount')
        media_root = pathlib.Path(media_root).resolve()
        path = (media_root / container_path.removeprefix('/data/')).resolve()
        if not path.is_relative_to(media_root):
            raise ValueError('Archive is outside the media mount')
        before = path.stat()
        with zipfile.ZipFile(path, 'r') as archive:
            names = [entry.filename for entry in archive.infolist() if entry.filename.lower().endswith(('.xml', '.json', '.txt'))]
            source = None
            fields = {}
            xml_error = None
            xml_name = next((name for name in names if name.lower() == 'comicinfo.xml'), None)
            if xml_name is not None:
                try:
                    source = archive.read(xml_name)
                    document = xml.fromstring(source)
                    if document.tag.split('}')[-1] != 'ComicInfo':
                        raise ValueError('Unexpected metadata root')
                    fields = {child.tag.split('}')[-1]: child.text or '' for child in document}
                except Exception as error:
                    xml_error = type(error).__name__
            metadata = None
            metadata_error = None
            json_name = next((name for name in names if name.lower() == 'metadata.json'), None)
            if json_name is not None:
                try:
                    metadata = json.loads(archive.read(json_name))
                except Exception as error:
                    metadata_error = type(error).__name__
        after = path.stat()
        unchanged = before.st_size == after.st_size and before.st_mtime_ns == after.st_mtime_ns
        return {'bookId': book['id'], 'sourceRevision': book['source']['sourceRevision'], 'metadataFiles': names, 'fieldNames': sorted(fields), 'xml': {name: fields.get(name) for name in ['Tags', 'Writer', 'Groups', 'Characters', 'Genre', 'URL', 'NhentaiId', 'PageCount']}, 'json': metadata, 'jsonError': metadata_error, 'xmlError': xml_error, 'xmlSha256': hashlib.sha256(source).hexdigest() if source is not None else None, 'unchangedStat': unchanged}
    except Exception as error:
        return {'bookId': book['id'], 'error': type(error).__name__}


def build_plan(database, dictionary_bytes, override_bytes, historical, archives):
    books = load_books(database)
    trusted_tags = {normalize(tag['value']) for book in books if book['source'].get('contentTagsKnown') and book['source'].get('contentTagsFromJson') for tag in book['source']['tags'] if normalize(tag['type']) == 'tag'}
    copies = collections.defaultdict(list)
    for book in books:
        if book['source'].get('contentTagsKnown') and book['source'].get('contentTagsFromJson') and book['source'].get('galleryId') is not None:
            copies[book['source']['galleryId']].append(book['source'])
    archive_index = {entry['bookId']: entry for entry in archives}
    for book in books:
        evidence = archive_index.get(book['id'])
        if evidence and evidence.get('unchangedStat') is True and evidence.get('sourceRevision') == book['source']['sourceRevision']:
            for field in ['json', 'retrievedJson']:
                classified = json_classification(evidence.get(field), book['source'].get('galleryId'), require_id=field == 'retrievedJson')
                if classified is not None:
                    trusted_tags.update(normalize(tag['value']) for tag in classified['tags'] if tag['type'] == 'tag')
    resolver = Resolver(json.loads(dictionary_bytes), json.loads(override_bytes), historical, trusted_tags)
    settings = {row[0]: json.loads(row[1]) for row in database.execute("SELECT KEY,VALUE FROM SERVER_SETTINGS WHERE KEY IN ('METADATA_ENRICHMENT_PAGE_SIZE_BUCKETS','METADATA_ENRICHMENT_TAG_SIZE_BUCKETS')")}
    pages = settings['METADATA_ENRICHMENT_PAGE_SIZE_BUCKETS']
    tags = settings['METADATA_ENRICHMENT_TAG_SIZE_BUCKETS']
    dictionary_hash = hashlib.sha256(dictionary_bytes + override_bytes).hexdigest()
    changes = []
    summary = collections.Counter()
    methods = collections.Counter()
    for book in books:
        existing = book['source'].get('contentTagsKnown') or book['source'].get('fallbackTags') is not None
        if existing:
            source = dict(book['source'])
            source.setdefault('fallbackTags', None)
            mapping = json.loads(book['resultJson'] or '{}').get('mapping', {})
            method = 'cache_revision_only'
        else:
            candidates = copies.get(book['source'].get('galleryId'), [])
            identical_copies = len({tuple(sorted(tag_key(tag) for tag in source['tags'])) for source in candidates}) == 1
            original_copy = candidates[0] if candidates and identical_copies else None
            evidence = archive_index.get(book['id'])
            source, mapping, book_methods = recover(book, evidence, resolver, original_copy)
            methods.update(book_methods)
            if source['contentTagsFromJson']:
                method = 'archived_json' if evidence and json_classification(evidence.get('json'), book['source'].get('galleryId')) is not None else 'classified_copy' if original_copy is not None else 'source_api'
            else:
                method = 'dictionary_recovered' if source['contentTagsKnown'] else 'current_tags_fallback'
        source['sourceRevision'] = normalized_revision(source, book['metadataFiles'])
        payload = source_json(source)
        if existing and payload == book['originalJson']:
            continue
        summary[method] += 1
        count = len({tag_key(tag) for tag in source['tags'] if normalize(tag['type']) == 'tag'}) if source['contentTagsKnown'] else len(source['fallbackTags'])
        labels = [bucket(source['pageCount'], pages)] if source['pageCount'] > 0 else []
        labels.append(bucket(count, tags))
        result = compact({'mapping': mapping, 'preferenceBuckets': labels})
        input_hash = digest(payload + compact(pages) + compact(tags) + dictionary_hash)
        changes.append({'bookId': book['id'], 'sourceHash': digest(book['originalJson']), 'currentTagsHash': digest(compact(book['currentTags'])), 'tagsLock': book['tagsLock'], 'revision': book['revision'], 'inputJson': payload, 'inputHash': input_hash, 'resultJson': result})
    return {'version': 1, 'summary': dict(summary), 'methods': dict(methods), 'changes': changes}


def apply_plan(database, plan):
    if plan.get('version') != 1:
        raise ValueError('Unsupported recovery plan version')
    database.execute('BEGIN IMMEDIATE')
    try:
        current = {book['id']: book for book in load_books(database)}
        for change in plan['changes']:
            book = current.get(change['bookId'])
            if book is None or digest(book['originalJson']) != change['sourceHash'] or book['revision'] != change['revision'] or digest(compact(book['currentTags'])) != change['currentTagsHash'] or book['tagsLock'] != change['tagsLock']:
                raise ValueError('Book changed since inspection: ' + change['bookId'])
        now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None).isoformat(sep=' ')
        for change in plan['changes']:
            revision = change['revision'] + 1
            database.execute('UPDATE BOOK_ENRICHMENT_SOURCE SET INPUT_JSON=? WHERE BOOK_ID=?', (change['inputJson'], change['bookId']))
            database.execute("UPDATE BOOK_ENRICHMENT_STATE SET STATUS='SUCCESS',REVISION=?,INPUT_HASH=?,RESULT_JSON=?,RESULT_REVISION=?,LAST_ERROR=NULL,STARTED_DATE=NULL,COMPLETED_DATE=?,LAST_MODIFIED_DATE=? WHERE BOOK_ID=? AND PROCESSOR='METADATA'", (revision, change['inputHash'], change['resultJson'], revision, now, now, change['bookId']))
        database.commit()
    except Exception:
        database.rollback()
        raise


def main():
    parser = argparse.ArgumentParser(description='One-time recovery of previously quarantined XML tags; CBZ files are never written')
    commands = parser.add_subparsers(dest='command', required=True)
    scan = commands.add_parser('scan')
    scan.add_argument('--database', type=pathlib.Path, required=True)
    scan.add_argument('--media-root', type=pathlib.Path, required=True)
    scan.add_argument('--output', type=pathlib.Path, required=True)
    scan.add_argument('--workers', type=int, default=4)
    inspect = commands.add_parser('plan')
    inspect.add_argument('--database', type=pathlib.Path, required=True)
    inspect.add_argument('--dictionary', type=pathlib.Path, required=True)
    inspect.add_argument('--overrides', type=pathlib.Path, required=True)
    inspect.add_argument('--historical-dictionary', type=pathlib.Path, required=True)
    inspect.add_argument('--archives', type=pathlib.Path, required=True)
    inspect.add_argument('--output', type=pathlib.Path, required=True)
    apply = commands.add_parser('apply', help='Requires a consistent backup and stopped Komga writers')
    apply.add_argument('--database', type=pathlib.Path, required=True)
    apply.add_argument('--plan', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.command == 'scan':
        if not 1 <= args.workers <= 8:
            parser.error('workers must be between 1 and 8')
        with sqlite3.connect('file:' + str(args.database) + '?mode=ro', uri=True) as database:
            books = [book for book in load_books(database) if not book['source'].get('contentTagsKnown') and book['source'].get('fallbackTags') is None]
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
            archives = list(executor.map(lambda book: read_archive(book, args.media_root), books))
        args.output.write_text(json.dumps(archives, ensure_ascii=False, indent=2))
        print(json.dumps({'archives': len(archives), 'errors': sum('error' in archive for archive in archives)}))
        return
    if args.command == 'plan':
        with sqlite3.connect('file:' + str(args.database) + '?mode=ro', uri=True) as database:
            plan = build_plan(database, args.dictionary.read_bytes(), args.overrides.read_bytes(), json.loads(args.historical_dictionary.read_text()), json.loads(args.archives.read_text()))
        args.output.write_text(json.dumps(plan, ensure_ascii=False, indent=2))
    else:
        plan = json.loads(args.plan.read_text())
        with sqlite3.connect(args.database, timeout=30) as database:
            apply_plan(database, plan)
    print(json.dumps({'summary': plan['summary'], 'methods': plan['methods'], 'books': len(plan['changes'])}, ensure_ascii=False))


if __name__ == '__main__':
    main()
