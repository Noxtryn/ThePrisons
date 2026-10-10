import copy
import json
import tempfile
import time
import unittest
from pathlib import Path
from prisonsbot.dashboard_core import DraftStore, DraftError, Conflict, MODULES, health


class DraftTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = DraftStore(Path(self.temp.name) / 'drafts.sqlite3')

    def test_mod_and_guild_isolation(self):
        original = self.store.get('guild', 'theprisons', 'roles')
        changed = copy.deepcopy(original['document'])
        changed['settings']['selection_title'] = 'ThePrisons abonnieren'
        self.store.save('guild', 'theprisons', 'roles', 0, changed, 'owner')
        self.assertEqual(self.store.get('guild', 'sky-supra', 'roles')['revision'], 0)
        self.assertEqual(self.store.get('other', 'theprisons', 'roles')['revision'], 0)
        self.assertEqual(DraftStore(self.store.path).get('guild', 'theprisons', 'roles')['revision'], 1)

    def test_conflicting_writers_do_not_overwrite(self):
        doc = self.store.get('guild', 'community', 'roles')['document']
        self.store.save('guild', 'community', 'roles', 0, doc, 'first')
        with self.assertRaises(Conflict):
            self.store.save('guild', 'community', 'roles', 0, doc, 'second')
        self.assertEqual(len(self.store.audit('guild')), 1)

    def test_validation_cannot_smuggle_secrets_or_unsafe_urls(self):
        for key, value in [('image_url', 'javascript:alert(1)'), ('channel_id', 'wrong')]:
            doc = self.store.get('guild', 'community', 'messages')['document']
            doc['settings'][key] = value
            with self.assertRaises(DraftError):
                self.store.save('guild', 'community', 'messages', 0, doc, 'owner')
        doc = self.store.get('guild', 'community', 'messages')['document']
        doc['settings']['token'] = 'secret'
        with self.assertRaises(DraftError):
            self.store.save('guild', 'community', 'messages', 0, doc, 'owner')

    def test_no_deleting_existing_channels(self):
        doc = self.store.get('guild', 'community', 'setup')['document']
        doc['settings']['preserve_existing'] = False
        with self.assertRaises(DraftError):
            self.store.save('guild', 'community', 'setup', 0, doc, 'owner')

    def test_boolean_not_accepted_as_integer(self):
        doc = self.store.get('guild', 'community', 'roles')['document']
        doc['settings']['delay_minutes'] = True
        with self.assertRaises(DraftError):
            self.store.save('guild', 'community', 'roles', 0, doc, 'owner')

    def test_unknown_scope_and_module(self):
        for scope, module in [('other', 'roles'), ('community', 'unknown')]:
            with self.assertRaises(DraftError):
                self.store.get('guild', scope, module)

    def test_health_staleness_and_missing_file(self):
        path = Path(self.temp.name) / 'health.json'
        self.assertEqual(health(path)['status'], 'unknown')
        path.write_text(json.dumps({'status': 'online', 'updated_at': 100, 'latency_seconds': .1}))
        self.assertEqual(health(path, 110)['latency_ms'], 100)
        self.assertEqual(health(path, 200)['status'], 'stale')


try:
    from fastapi.testclient import TestClient
    from prisonsbot.dashboard import Settings, create_app
    WEB_AVAILABLE = True
except ImportError:
    WEB_AVAILABLE = False


@unittest.skipUnless(WEB_AVAILABLE, 'Optional requirements-dashboard.txt not installed')
class DashboardHTTPTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.cfg = Settings(runtime=self.temp.name, demo=True)
        self.app = create_app(self.cfg)
        self.client = TestClient(self.app, base_url=self.cfg.origin)
        self.addCleanup(self.client.close)

    def login_demo(self):
        response = self.client.post('/auth/demo', headers={'Origin': self.cfg.origin})
        self.assertEqual(response.status_code, 200)
        return self.client.get('/api/me').json()['csrf']

    def test_authentication_required_and_no_live_apply_route(self):
        self.assertEqual(self.client.get('/api/status').status_code, 401)
        self.assertEqual(self.client.post('/api/apply').status_code, 404)

    def test_missing_oauth_secret_and_demo_disabled(self):
        other = TestClient(create_app(Settings(runtime=self.temp.name)), base_url=self.cfg.origin)
        self.addCleanup(other.close)
        self.assertEqual(other.get('/auth/login').status_code, 503)
        self.assertEqual(other.post('/auth/demo', headers={'Origin': self.cfg.origin}).status_code, 403)

    def test_csrf_host_and_origin_boundaries(self):
        csrf = self.login_demo()
        body = {'revision': 0, 'document': self.client.get('/api/drafts/community/roles').json()['document']}
        path = '/api/drafts/community/roles'
        self.assertEqual(self.client.put(path, json=body).status_code, 403)
        self.assertEqual(self.client.put(path, json=body, headers={'Origin': 'https://evil.example', 'X-CSRF-Token': csrf}).status_code, 403)
        self.assertEqual(self.client.get('/api/me', headers={'Host': 'evil.example'}).status_code, 400)
        self.assertEqual(self.client.put(path, json=body, headers={'Origin': self.cfg.origin, 'X-CSRF-Token': csrf}).status_code, 200)
        self.assertEqual(self.client.put(path, json=body, headers={'Origin': self.cfg.origin, 'X-CSRF-Token': csrf}).status_code, 409)

    def test_expiry_and_logout(self):
        csrf = self.login_demo()
        self.assertEqual(self.client.post('/auth/logout', headers={'Origin': self.cfg.origin, 'X-CSRF-Token': csrf}).status_code, 200)
        self.assertEqual(self.client.get('/api/me').status_code, 401)
        self.login_demo()
        for session in self.app.state.sessions.values():
            session['expires'] = 0
        self.assertEqual(self.client.get('/api/me').status_code, 401)

    def test_oauth_bound_state_and_permission_revocation(self):
        permissions = {'value': 32}
        def provider(path, token=None, form=None):
            if path == '/oauth2/token':
                return {'access_token': 'test-secret-not-for-browser'}
            if path == '/users/@me':
                return {'id': '123', 'username': 'Tester'}
            return [{'id': self.cfg.guild, 'permissions': str(permissions['value'])}]
        cfg = Settings(runtime=self.temp.name, secret='local-test-secret')
        app = create_app(cfg, provider)
        client = TestClient(app, base_url=cfg.origin)
        self.addCleanup(client.close)
        from urllib.parse import parse_qs, urlsplit
        response = client.get('/auth/login', follow_redirects=False)
        state = parse_qs(urlsplit(response.headers['location']).query)['state'][0]
        attacker = TestClient(app, base_url=cfg.origin)
        self.addCleanup(attacker.close)
        self.assertEqual(attacker.get('/auth/callback', params={'state': state, 'code': 'fake'}).status_code, 400)
        self.assertEqual(client.get('/auth/callback', params={'state': state, 'code': 'fake'}).status_code, 200)
        self.assertEqual(client.get('/auth/callback', params={'state': state, 'code': 'fake'}).status_code, 400)
        me = client.get('/api/me').json()
        self.assertTrue(me['can_edit'])
        self.assertNotIn('test-secret-not-for-browser', json.dumps(me))
        permissions['value'] = 0
        self.assertEqual(client.get('/api/drafts/community/roles').status_code, 403)
        permissions['value'] = 32
        body = {'revision': 0, 'document': app.state.store.get(cfg.guild, 'community', 'roles')['document']}
        self.assertEqual(client.put('/api/drafts/community/roles', json=body, headers={'Origin': cfg.origin, 'X-CSRF-Token': me['csrf']}).status_code, 200)

    def test_demo_storage_separate_and_security_headers(self):
        self.login_demo()
        self.assertFalse(self.client.get('/api/server').json()['available'])
        self.assertTrue((Path(self.temp.name) / 'dashboard-demo.sqlite3').exists())
        self.assertFalse((Path(self.temp.name) / 'dashboard.sqlite3').exists())
        response = self.client.get('/')
        self.assertIn("frame-ancestors 'none'", response.headers['content-security-policy'])
        self.assertEqual(response.headers['cache-control'], 'no-store')

    def test_server_adapter_cannot_write(self):
        from prisonsbot.dashboard import ReadOnlyDiscord
        from prisonsbot.config import ConfigError
        class Transport:
            def request(self, *args):
                return args
        readonly = ReadOnlyDiscord(Transport())
        self.assertEqual(readonly.me(), ('GET', '/users/@me'))
        for method in ('POST', 'PATCH', 'PUT', 'DELETE'):
            with self.assertRaises(ConfigError):
                readonly.request(method, '/guilds/test')
        with self.assertRaises(ConfigError):
            readonly.request('GET', '/guilds/test', {'value': 'unexpected'})


if __name__ == '__main__':
    unittest.main()
