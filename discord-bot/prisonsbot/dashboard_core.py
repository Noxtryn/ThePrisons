"""Dashboard drafts are separate from live bot configuration and never execute Discord writes."""
import copy
import json
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path

SCOPES = ('community', 'theprisons', 'sky-supra')
MODULES = {
    'roles': {'title': 'Rollen & Onboarding', 'enabled': False, 'settings': {
        'member_role': '', 'delay_minutes': 0, 'screening_required': True,
        'selection_title': 'Wähle deine Mods', 'selection_description': 'ThePrisons, Sky Supra oder beide — du entscheidest.'}},
    'messages': {'title': 'Nachrichten & Design', 'enabled': False, 'settings': {
        'channel_id': '', 'title': 'Willkommen bei Nexora', 'description': 'Deine Community für ThePrisons und Sky Supra.',
        'footer': 'Nexora • Mod Community', 'image_url': ''}},
    'tickets': {'title': 'Support & Tickets', 'enabled': False, 'settings': {
        'category_id': '1558271146938339388', 'panel_title': 'Wie können wir dir helfen?',
        'max_open': 2, 'staff_role_ids': '', 'retention_days': 30}},
    'releases': {'title': 'Downloads & Releases', 'enabled': False, 'settings': {
        'version': '', 'download_url': '', 'changelog': '', 'channel_id': '', 'release_alerts_role': ''}},
    'setup': {'title': 'Serverstruktur', 'enabled': False, 'settings': {
        'start_category': 'START HERE', 'community_category': 'COMMUNITY', 'support_category': 'SUPPORT & DEVELOPMENT',
        'channel_pattern': '💬・{name}', 'preserve_existing': True}},
}
LIMITS = {'delay_minutes': (0, 10080), 'max_open': (1, 10), 'retention_days': (1, 365)}
IDS = {'member_role', 'channel_id', 'category_id', 'release_alerts_role'}


class DraftError(ValueError):
    pass


class Conflict(DraftError):
    pass


def validate(module, document):
    if module not in MODULES or not isinstance(document, dict):
        raise DraftError('Unbekanntes Modul.')
    if set(document) != {'enabled', 'settings'} or type(document['enabled']) is not bool:
        raise DraftError('Ungültige Moduleinstellungen.')
    settings = document['settings']
    expected = MODULES[module]['settings']
    if not isinstance(settings, dict) or set(settings) != set(expected):
        raise DraftError('Die Felder stimmen nicht mit diesem Modul überein.')
    for key, default in expected.items():
        value = settings[key]
        if type(value) is not type(default):
            raise DraftError('Ungültiger Wert für ' + key)
        if isinstance(value, str):
            if len(value) > (4000 if key in ('description', 'changelog') else 500):
                raise DraftError('Text ist zu lang: ' + key)
            if key in IDS and value and not (value.isascii() and value.isdigit() and 15 <= len(value) <= 25):
                raise DraftError('Ungültige Discord-ID: ' + key)
            if key == 'staff_role_ids' and value:
                for role in value.split(','):
                    if not (role.strip().isascii() and role.strip().isdigit() and 15 <= len(role.strip()) <= 25):
                        raise DraftError('Teamrollen als kommagetrennte Discord-IDs angeben.')
            if key.endswith('_url') and value:
                from urllib.parse import urlsplit
                parsed = urlsplit(value)
                if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password:
                    raise DraftError('Eine gültige HTTPS-Adresse ist erforderlich.')
        if key in LIMITS and not LIMITS[key][0] <= value <= LIMITS[key][1]:
            raise DraftError('Wert außerhalb des erlaubten Bereichs: ' + key)
    if module == 'setup' and settings['preserve_existing'] is not True:
        raise DraftError('Bestehende Kanäle müssen erhalten bleiben.')
    return copy.deepcopy(document)


class DraftStore:
    def __init__(self, path):
        self.path = str(path)
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript('''CREATE TABLE IF NOT EXISTS dashboard_drafts (
                guild TEXT, scope TEXT, module TEXT, revision INTEGER, document TEXT,
                updated_by TEXT, updated_at REAL, PRIMARY KEY(guild,scope,module));
                CREATE TABLE IF NOT EXISTS dashboard_audit (
                id INTEGER PRIMARY KEY, guild TEXT, actor TEXT, scope TEXT, module TEXT,
                revision INTEGER, changed_fields TEXT, created_at REAL);''')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def get(self, guild, scope, module):
        if scope not in SCOPES or module not in MODULES:
            raise DraftError('Unbekannter Bereich oder unbekanntes Modul.')
        with self.connect() as db:
            row = db.execute('SELECT * FROM dashboard_drafts WHERE guild=? AND scope=? AND module=?',
                             (guild, scope, module)).fetchone()
        default = {'enabled': False, 'settings': copy.deepcopy(MODULES[module]['settings'])}
        if scope != 'community' and module == 'messages':
            default['settings']['title'] = 'ThePrisons' if scope == 'theprisons' else 'Sky Supra'
        return {'scope': scope, 'module': module, 'revision': row['revision'] if row else 0,
                'document': json.loads(row['document']) if row else default,
                'updated_at': row['updated_at'] if row else None}

    def save(self, guild, scope, module, revision, document, actor):
        if type(revision) is not int or revision < 0:
            raise DraftError('Ungültige Revision.')
        document = validate(module, document)
        self.get(guild, scope, module)  # validates scope, too
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT revision,document FROM dashboard_drafts WHERE guild=? AND scope=? AND module=?',
                             (guild, scope, module)).fetchone()
            current = row['revision'] if row else 0
            if current != revision:
                raise Conflict('Der Entwurf wurde inzwischen geändert. Bitte neu laden.')
            before = json.loads(row['document']) if row else self.get(guild, scope, module)['document']
            fields = ['settings.' + key for key, value in document['settings'].items() if before['settings'][key] != value]
            if document['enabled'] != before['enabled']:
                fields.append('enabled')
            db.execute('INSERT OR REPLACE INTO dashboard_drafts VALUES (?,?,?,?,?,?,?)',
                       (guild, scope, module, current + 1, json.dumps(document), actor, time.time()))
            db.execute('INSERT INTO dashboard_audit (guild,actor,scope,module,revision,changed_fields,created_at) VALUES (?,?,?,?,?,?,?)',
                       (guild, actor, scope, module, current + 1, json.dumps(fields), time.time()))
        return self.get(guild, scope, module)

    def audit(self, guild):
        with self.connect() as db:
            return [dict(row) for row in db.execute('SELECT * FROM dashboard_audit WHERE guild=? ORDER BY id DESC LIMIT 40', (guild,))]


def health(path, now=None):
    now = time.time() if now is None else now
    try:
        data = json.loads(Path(path).read_text(encoding='utf-8'))
        age = max(0, now - float(data['updated_at']))
        return {'status': data['status'] if age <= 90 else 'stale', 'age_seconds': round(age),
                'latency_ms': round(data['latency_seconds'] * 1000) if data.get('latency_seconds') is not None else None,
                'updated_at': data['updated_at']}
    except (OSError, ValueError, KeyError, TypeError):
        return {'status': 'unknown', 'age_seconds': None, 'latency_ms': None, 'updated_at': None}
