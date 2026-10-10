"""Loopback-only dashboard: real OAuth login and versioned drafts, no Discord writes."""
import argparse
import asyncio
import hashlib
import json
import os
import secrets
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from fastapi import FastAPI, HTTPException, Request as WebRequest
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from .dashboard_core import DraftStore, DraftError, Conflict, MODULES, SCOPES, health
from .config import Config, ConfigError
from .api import Discord
from . import migration

API = 'https://discord.com/api/v10'
ASSETS = Path(__file__).resolve().parent / 'dashboard_assets'


@dataclass
class Settings:
    origin: str = 'http://127.0.0.1:8765'
    guild: str = '1557507246848483431'
    application: str = '1557515544230371338'
    secret: str = ''
    runtime: str = 'state'
    demo: bool = False
    health_file: str = 'state/health.json'


class ReadOnlyDiscord:
    def __init__(self, api):
        self.api = api

    def request(self, method, path, body=None):
        if method != 'GET' or body is not None:
            raise ConfigError('Dashboard-Serveransicht erlaubt ausschließlich lesende Zugriffe.')
        return self.api.request(method, path)

    def me(self):
        return self.request('GET', '/users/@me')


def discord(path, token=None, form=None):
    headers = {'User-Agent': 'NexoraControl/1.0'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    body = urlencode(form).encode() if form is not None else None
    if body:
        headers['Content-Type'] = 'application/x-www-form-urlencoded'
    with urlopen(Request(API + path, data=body, headers=headers), timeout=12) as response:
        return json.load(response)


def create_app(settings=None, provider=discord, bot_config=None, bot_api=None):
    cfg = settings or Settings(secret=os.environ.get('DISCORD_OAUTH_CLIENT_SECRET', ''))
    if cfg.origin != 'http://127.0.0.1:8765':
        raise ValueError('Diese erste Version unterstützt ausschließlich http://127.0.0.1:8765.')
    store = DraftStore(Path(cfg.runtime) / ('dashboard-demo.sqlite3' if cfg.demo else 'dashboard.sqlite3'))
    sessions, pending = {}, {}
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    app.state.store = store
    app.state.sessions = sessions
    bot_cfg = bot_config or Config()
    readonly = ReadOnlyDiscord(bot_api or Discord(bot_cfg.get('DISCORD_BOT_TOKEN', '')))
    inventory_cache = {'data': None, 'expires': 0}
    inventory_lock = asyncio.Lock()

    async def find_guild(token):
        after = None
        while True:
            path = '/users/@me/guilds?limit=200' + ('&after=' + after if after else '')
            guilds = await asyncio.to_thread(provider, path, token)
            found = next((g for g in guilds if g['id'] == cfg.guild), None)
            if found or len(guilds) < 200:
                return found
            after = guilds[-1]['id']

    def prune():
        for collection in (sessions, pending):
            for key, value in list(collection.items()):
                if value['expires'] < time.time():
                    collection.pop(key, None)

    @app.middleware('http')
    async def boundaries(request, call_next):
        if request.headers.get('host') != '127.0.0.1:8765':
            return JSONResponse({'detail': 'Ungültiger Host.'}, status_code=400)
        prune()
        response = await call_next(request)
        response.headers.update({'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
            'Referrer-Policy': 'no-referrer', 'X-Frame-Options': 'DENY',
            'Content-Security-Policy': "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' https:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"})
        return response

    async def identity(request):
        sid = request.cookies.get('nexora_session', '')
        session = sessions.get(hashlib.sha256(sid.encode()).hexdigest())
        if not session or session['expires'] < time.time():
            raise HTTPException(401, 'Bitte mit Discord anmelden.')
        if not session.get('demo'):
            # Recheck guild permissions on every protected request, including reads.
            try:
                member = await find_guild(session['token'])
                if not member:
                    raise HTTPException(403, 'Du bist nicht Mitglied dieses Servers.')
                permissions = int(member.get('permissions', 0))
                session['can_edit'] = bool(member.get('owner') or permissions & (8 | 32))
            except HTTPException:
                raise
            except Exception:
                raise HTTPException(503, 'Discord-Rechte konnten nicht geprüft werden.') from None
        return session

    async def editor(request):
        session = await identity(request)
        if not session['can_edit']:
            raise HTTPException(403, 'Server verwalten oder Administrator erforderlich.')
        if request.headers.get('origin') != cfg.origin or not secrets.compare_digest(request.headers.get('x-csrf-token', ''), session['csrf']):
            raise HTTPException(403, 'Ungültige Anfrage. Seite neu laden.')
        return session

    def set_session(user, token='', demo=False):
        sid = secrets.token_urlsafe(32)
        sessions[hashlib.sha256(sid.encode()).hexdigest()] = {'user': {'id': user['id'], 'username': user['username']},
            'token': token, 'expires': time.time() + 3600, 'csrf': secrets.token_urlsafe(32), 'can_edit': demo, 'demo': demo}
        response = RedirectResponse('/', status_code=303)
        response.set_cookie('nexora_session', sid, httponly=True, samesite='lax', max_age=3600)
        response.delete_cookie('nexora_oauth')
        return response

    @app.get('/auth/login')
    async def login():
        if not cfg.secret:
            raise HTTPException(503, 'Discord-Login noch nicht eingerichtet. Client Secret lokal konfigurieren.')
        state, cookie = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        pending[state] = {'cookie': hashlib.sha256(cookie.encode()).hexdigest(), 'expires': time.time() + 300}
        url = 'https://discord.com/oauth2/authorize?' + urlencode({'client_id': cfg.application, 'response_type': 'code',
            'redirect_uri': cfg.origin + '/auth/callback', 'scope': 'identify guilds', 'state': state})
        response = RedirectResponse(url)
        response.set_cookie('nexora_oauth', cookie, httponly=True, samesite='lax', max_age=300)
        return response

    @app.get('/auth/callback')
    async def callback(request: WebRequest, state: str = '', code: str = ''):
        flow = pending.get(state)
        cookie = hashlib.sha256(request.cookies.get('nexora_oauth', '').encode()).hexdigest()
        if not flow or flow['expires'] < time.time() or not secrets.compare_digest(flow['cookie'], cookie) or not code:
            raise HTTPException(400, 'Anmeldung ungültig oder abgelaufen. Bitte erneut anmelden.')
        pending.pop(state, None)
        try:
            tokens = await asyncio.to_thread(provider, '/oauth2/token', form={'client_id': cfg.application,
                'client_secret': cfg.secret, 'grant_type': 'authorization_code', 'code': code,
                'redirect_uri': cfg.origin + '/auth/callback'})
            token = tokens['access_token']
            user = await asyncio.to_thread(provider, '/users/@me', token)
            if not await find_guild(token):
                raise HTTPException(403, 'Dieses Dashboard gehört zur Nexora Community.')
            return set_session(user, token)
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(502, 'Discord-Anmeldung fehlgeschlagen. Bitte erneut versuchen.') from None

    @app.post('/auth/demo')
    async def demo(request: WebRequest):
        if not cfg.demo or request.headers.get('origin') != cfg.origin:
            raise HTTPException(403, 'Demo ist nicht verfügbar.')
        return set_session({'id': 'local-demo', 'username': 'Lokale Demo'}, demo=True)

    @app.post('/auth/logout')
    async def logout(request: WebRequest):
        session = await identity(request)
        if request.headers.get('origin') != cfg.origin or not secrets.compare_digest(request.headers.get('x-csrf-token', ''), session['csrf']):
            raise HTTPException(403, 'Ungültige Anfrage.')
        sid = request.cookies.get('nexora_session', '')
        sessions.pop(hashlib.sha256(sid.encode()).hexdigest(), None)
        response = JSONResponse({'ok': True})
        response.delete_cookie('nexora_session')
        return response

    @app.get('/api/public')
    async def public():
        return {'login_configured': bool(cfg.secret), 'demo_available': cfg.demo}

    @app.get('/api/me')
    async def me(request: WebRequest):
        session = await identity(request)
        return {'user': session['user'], 'can_edit': session['can_edit'], 'csrf': session['csrf'],
                'demo': session['demo'], 'guild_id': cfg.guild}

    @app.get('/api/status')
    async def status(request: WebRequest):
        await identity(request)
        return {'health': health(cfg.health_file), 'live_changes_enabled': False, 'modules': MODULES, 'scopes': SCOPES}

    @app.get('/api/drafts/{scope}/{module}')
    async def get_draft(scope: str, module: str, request: WebRequest):
        session = await identity(request)
        if not session['can_edit']:
            raise HTTPException(403, 'Konfiguration ist dem Verwaltungsteam vorbehalten.')
        try:
            return store.get(cfg.guild, scope, module)
        except DraftError as exc:
            raise HTTPException(400, str(exc)) from None

    @app.put('/api/drafts/{scope}/{module}')
    async def put_draft(scope: str, module: str, request: WebRequest):
        session = await editor(request)
        raw = await request.body()
        if len(raw) > 32768:
            raise HTTPException(413, 'Entwurf zu groß.')
        try:
            body = json.loads(raw)
            if not isinstance(body, dict) or set(body) != {'revision', 'document'}:
                raise DraftError('Ungültiger Entwurf.')
            return store.save(cfg.guild, scope, module, body['revision'], body['document'], session['user']['id'])
        except Conflict as exc:
            raise HTTPException(409, str(exc)) from None
        except (DraftError, ValueError, KeyError, TypeError) as exc:
            raise HTTPException(400, str(exc) if isinstance(exc, DraftError) else 'Ungültiger Entwurf.') from None

    @app.get('/api/audit')
    async def audit(request: WebRequest):
        session = await identity(request)
        if not session['can_edit']:
            raise HTTPException(403, 'Zugriff nur für das Verwaltungsteam.')
        return store.audit(cfg.guild)

    @app.get('/api/server')
    async def server(request: WebRequest):
        session = await identity(request)
        if not session['can_edit']:
            raise HTTPException(403, 'Serveransicht nur für das Verwaltungsteam.')
        if session['demo']:
            return {'available': False, 'reason': 'Die lokale Demo liest keine Discord-Serverdaten.', 'roles': [], 'channels': []}
        if not bot_cfg.has_token():
            return {'available': False, 'reason': 'Bot-Zugang für die lesende Serveransicht fehlt.', 'roles': [], 'channels': []}
        if bot_cfg.get('DISCORD_GUILD_ID') != cfg.guild:
            raise HTTPException(503, 'Serverkonfiguration stimmt nicht mit dem Dashboard überein.')
        async with inventory_lock:
            if inventory_cache['expires'] > time.time():
                return inventory_cache['data']
            try:
                snap = await asyncio.to_thread(migration.snapshot, readonly, bot_cfg)
                # Snapshot/plan reuse the bot's existing permission and ID checks.
                try:
                    plan = migration.plan(snap, bot_cfg)
                    plan_error = None
                except ConfigError as exc:
                    plan, plan_error = None, str(exc)
                data = {'available': True, 'captured_at': time.time(),
                    'roles': [{'id': r['id'], 'name': r['name'], 'position': r.get('position', 0), 'managed': r.get('managed', False)} for r in snap['roles']],
                    'channels': [{'id': c['id'], 'name': c['name'], 'type': c['type']} for c in snap['channels']],
                    'plan': plan, 'plan_error': plan_error}
                inventory_cache.update(data=data, expires=time.time() + 30)
                return data
            except Exception:
                raise HTTPException(503, 'Serverdaten konnten nicht gelesen werden. Bot-Zugang und Rechte prüfen.') from None

    @app.get('/')
    async def index():
        return FileResponse(ASSETS / 'index.html')

    app.mount('/assets', StaticFiles(directory=ASSETS), name='assets')
    return app


def main():
    parser = argparse.ArgumentParser(description='Lokales Nexora Control Dashboard')
    parser.add_argument('--demo', action='store_true', help='Separate lokale Demo-Datenbank; keine Discord-Anmeldung nötig')
    parser.add_argument('--runtime', default='state')
    parser.add_argument('--health-file', default='state/health.json')
    args = parser.parse_args()
    import uvicorn
    cfg = Settings(secret=os.environ.get('DISCORD_OAUTH_CLIENT_SECRET', ''), runtime=args.runtime,
                   demo=args.demo, health_file=args.health_file)
    uvicorn.run(create_app(cfg), host='127.0.0.1', port=8765, access_log=False)


if __name__ == '__main__':
    main()
