import asyncio
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from prisonsbot import config as C, nexora as N, migration as M, community as V, cli, commands
from prisonsbot.store import Store
from prisonsbot.api import Discord
from tests.test_bot import FakeDiscord, TOKEN

GUILD = "200000000000000000"
BOT = N.APPLICATION_ID
ROLE_IDS = {name: str(300000000000000000 + i) for i, name in enumerate(N.ROLES)}
ENV = {"DISCORD_GUILD_ID": GUILD, "DISCORD_BOT_TOKEN": TOKEN, "SKY_SUPRA_REPOSITORY": "example/sky-supra",
       "DISCORD_CHANNEL_SUPPORT_DEVELOPMENT": "400000000000000000",
       **{"DISCORD_ROLE_" + k.upper().replace(' ', '_'): v for k, v in ROLE_IDS.items()},
       **{C.channel_env(mod + '-' + channel): str(500000000000000000 + i * 10 + j)
          for i, mod in enumerate(N.PROJECTS) for j, channel in enumerate(('updates', 'downloads', 'changelog'))}}


def state():
    return {"guild_id": GUILD, "guild": {"id": GUILD, "owner_id": "9"}, "bot": {"id": BOT},
            "bot_member": {"roles": [BOT]}, "onboarding": {"prompts": []},
            "roles": [{"id": GUILD, "name": "@everyone", "permissions": "0", "position": 0},
                      {"id": BOT, "name": "Nexora Core", "permissions": str(N.BOT_PERMISSIONS), "position": 100}]
                     + [{"id": ROLE_IDS[k], "name": k, "permissions": str(v), "position": i + 1} for i, (k, v) in enumerate(N.ROLES.items())],
            "channels": []}


class FakeGuild:
    def __init__(self):
        self.state = state()
        self.requests = []
    def me(self):
        return self.state['bot']
    def request(self, method, path, body=None):
        self.requests.append((method, path, body))
        if method == 'GET':
            if path.endswith('/channels'): return copy.deepcopy(self.state['channels'])
            if path.endswith('/roles'): return copy.deepcopy(self.state['roles'])
            if path.endswith('/onboarding'): return copy.deepcopy(self.state['onboarding'])
            if '/members/' in path: return copy.deepcopy(self.state['bot_member'])
            return copy.deepcopy(self.state['guild'])
        collection = self.state['roles'] if '/roles' in path else self.state['channels']
        if method == 'POST':
            result = dict(body, id=str(600000000000000000 + len(collection)))
            collection.append(result)
            return result
        result = next(x for x in collection if x['id'] == path.split('/')[-1])
        result.update(body)
        return result


class SelectionTests(unittest.TestCase):
    def test_all_four_selections_preserve_unrelated_roles(self):
        ids = {'theprisons': 'p', 'sky-supra': 's'}
        for selected in ([], ['theprisons'], ['sky-supra'], list(ids)):
            add, remove = N.selection_delta(['p', 'staff'], selected, ids)
            after = ({'p', 'staff'} | add) - remove
            self.assertEqual(after, {'staff'} | {ids[k] for k in selected})
            self.assertEqual(N.selection_delta(after, selected, ids), (set(), set()))
    def test_bad_role_mapping_or_mod_rejected(self):
        for ids, selected in [({'theprisons': 'x'}, []), ({'theprisons': 'x', 'sky-supra': 'x'}, []), ({'theprisons': 'p', 'sky-supra': 's'}, ['admin'])]:
            with self.assertRaises(C.ConfigError): N.selection_delta([], selected, ids)


class PermissionTests(unittest.TestCase):
    def test_required_visibility_matrix(self):
        matrix = M.matrix(GUILD, ROLE_IDS, BOT)
        for label, p, s in [('Nur ThePrisons', True, False), ('Nur Sky Supra', False, True), ('Beide Mods', True, True), ('Keine Mod', False, False)]:
            self.assertEqual(matrix[label], {'common': True, 'theprisons': p, 'sky-supra': s, 'staff': False, 'ticket': False})
        for label in ('Moderator', 'Administrator'):
            self.assertTrue(all(matrix[label].values()))
    def test_readonly_mod_channel(self):
        perms = {GUILD: N.ACCESS, **{ROLE_IDS[k]: v for k, v in N.ROLES.items()}}
        ow = N.overwrites(GUILD, ROLE_IDS, 'theprisons', readonly=True, bot=BOT)
        result = N.effective_permissions(GUILD, perms, [ROLE_IDS['ThePrisons']], ow)
        self.assertTrue(result & N.VIEW)
        self.assertFalse(result & N.SEND)
    def test_ticket_is_private_even_for_both_mods(self):
        spec = V.ticket_spec(C.Config(ENV), 'sky-supra', 'bug', 'requester', BOT, ROLE_IDS)
        perms = {GUILD: N.ACCESS, **{ROLE_IDS[k]: v for k, v in N.ROLES.items()}}
        for member, selected, expected in [('other', ['ThePrisons', 'Sky Supra'], False), ('requester', [], True), (BOT, [], True), ('mod', ['Moderator'], True), ('designer', ['Designer'], False)]:
            result = N.effective_permissions(GUILD, perms, [ROLE_IDS[k] for k in selected], spec['permission_overwrites'], member)
            self.assertEqual(bool(result & N.VIEW), expected)
        self.assertIn('sky-supra:bug', spec['topic'])
    def test_member_override_and_admin_precedence(self):
        ow = N.overwrites(GUILD, ROLE_IDS, 'ticket', requester='x')
        self.assertTrue(N.effective_permissions(GUILD, {GUILD: 0}, [], ow, 'x') & N.VIEW)
        self.assertTrue(N.effective_permissions(GUILD, {GUILD: 8}, [], ow, 'y') & N.VIEW)


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
    def cfg(self):
        return C.Config({'DISCORD_GUILD_ID': GUILD, 'NEXORA_MIGRATION_APPROVED': 'true', 'NEXORA_STATE_DB': str(Path(self.tmp.name) / 'state.db')})
    def test_preview_and_apply_repeat_are_idempotent(self):
        fake = FakeGuild()
        planned = M.plan(fake.state, self.cfg())
        self.assertFalse(planned['blocking'])
        self.assertEqual(fake.requests, [])
        result = M.apply(fake, self.cfg(), planned, planned['digest'])
        self.assertEqual(result['verification']['changes'], [])
        self.assertFalse(any(m == 'DELETE' for m, _, _ in fake.requests))
        count = len([x for x in fake.requests if x[0] != 'GET'])
        next_plan = M.plan(fake.state, self.cfg())
        M.apply(fake, self.cfg(), next_plan, next_plan['digest'])
        self.assertEqual(len([x for x in fake.requests if x[0] != 'GET']), count)
    def test_apply_requires_both_approval_gates(self):
        fake = FakeGuild()
        planned = M.plan(fake.state, self.cfg())
        for cfg, approval in [(C.Config({'DISCORD_GUILD_ID': GUILD}), planned['digest']), (self.cfg(), 'wrong')]:
            with self.assertRaises(C.ConfigError): M.apply(fake, cfg, planned, approval)
        self.assertEqual(fake.requests, [])
    def test_stale_or_tampered_plan_rejected(self):
        fake = FakeGuild()
        planned = M.plan(fake.state, self.cfg())
        fake.state['guild']['name'] = 'changed'
        with self.assertRaises(C.ConfigError): M.apply(fake, self.cfg(), planned, planned['digest'])
        planned['changes'] = []
        with self.assertRaises(C.ConfigError): M.apply(fake, self.cfg(), planned, planned['digest'])
        self.assertFalse(any(x[0] != 'GET' for x in fake.requests))
    def test_legacy_channel_id_is_preserved_and_unrelated_channel_untouched(self):
        fake = FakeGuild()
        fake.state['channels'] = [{'id': '700000000000000001', 'name': 'announcements', 'type': 0}, {'id': '700000000000000002', 'name': 'unrelated', 'type': 0}]
        planned = M.plan(fake.state, self.cfg())
        self.assertEqual(planned['channels']['theprisons-updates'], '700000000000000001')
        self.assertFalse(any(c['id'] == '700000000000000002' for c in planned['changes']))
    def test_ambiguous_channels_block(self):
        fake = FakeGuild()
        fake.state['channels'] = [{'id': str(i), 'name': 'announcements', 'type': 0} for i in (1, 2)]
        with self.assertRaises(C.ConfigError): M.plan(fake.state, self.cfg())
    def test_bot_administrator_is_blocked(self):
        fake = FakeGuild()
        fake.state['roles'][1]['permissions'] = '8'
        planned = M.plan(fake.state, self.cfg())
        self.assertTrue(planned['blocking'])
        with self.assertRaises(C.ConfigError): M.apply(fake, self.cfg(), planned, planned['digest'])
    def test_missing_guild_or_wrong_snapshot(self):
        with self.assertRaises(C.ConfigError): M.plan(state(), C.Config({}))
        with self.assertRaises(C.ConfigError): M.plan(state(), C.Config({'DISCORD_GUILD_ID': '900000000000000000'}))
    def test_member_admin_permission_blocks_privacy_matrix(self):
        fake = FakeGuild()
        next(r for r in fake.state['roles'] if r['name'] == 'Member')['permissions'] = '8'
        self.assertTrue(M.plan(fake.state, self.cfg())['blocking'])
    def test_overwrite_order_does_not_cause_repeat_changes(self):
        fake = FakeGuild()
        planned = M.plan(fake.state, self.cfg())
        M.apply(fake, self.cfg(), planned, planned['digest'])
        for channel in fake.state['channels']:
            channel['permission_overwrites'].reverse()
        self.assertEqual(M.plan(fake.state, self.cfg())['changes'], [])


class CommunityTests(unittest.TestCase):
    def data(self, mod):
        return {'mod': mod, 'tag_name': 'v1.0.0', 'body': 'Changes', 'html_url': 'https://github.com/example/repo/releases/tag/v1.0.0', 'assets': []}
    def test_mod_release_routing_and_repeat(self):
        fake = FakeDiscord()
        api = Discord(TOKEN, fake)
        cfg = C.Config(ENV)
        for mod in N.PROJECTS:
            V.post_release(api, cfg, mod, self.data(mod), log=lambda *_: None)
        self.assertEqual(len(fake.posts()), 6)
        for mod in N.PROJECTS:
            V.post_release(api, cfg, mod, self.data(mod), log=lambda *_: None)
        self.assertEqual(len(fake.posts()), 6)
        self.assertEqual(len(fake.channels), 6)
    def test_missing_destinations_fail_before_any_post(self):
        fake = FakeDiscord()
        with self.assertRaises(C.ConfigError): V.post_release(Discord(TOKEN, fake), C.Config({}), 'sky-supra', self.data('sky-supra'))
        self.assertEqual(fake.requests, [])
    def test_release_mod_mismatch_rejected(self):
        with self.assertRaises(C.ConfigError): V.release_messages(C.Config(ENV), 'theprisons', self.data('sky-supra'))
    def test_dry_run_never_contacts_discord(self):
        fake = FakeDiscord()
        V.post_release(Discord(TOKEN, fake), C.Config({}), 'sky-supra', self.data('sky-supra'), dry_run=True, log=lambda *_: None)
        self.assertEqual(fake.requests, [])
    def test_release_cache_is_separate(self):
        V._cache.clear()
        for mod in N.PROJECTS:
            data = V.release_data(C.Config(ENV), mod, fetch=lambda m=mod: self.data(m))
            self.assertEqual(data['mod'], mod)
        self.assertEqual(len(V._cache), 2)
    def test_sky_supra_without_repository_is_unreleased(self):
        self.assertIsNone(V.release_data(C.Config({}), 'sky-supra'))
        self.assertIn('nicht verfügbar', V.answer(C.Config({}), 'download', 'sky-supra')['description'])
    def test_panel_is_idempotent_and_controls_survive(self):
        from prisonsbot.publish import sync_messages
        fake = FakeDiscord()
        api = Discord(TOKEN, fake)
        desired = [N.mods_message(C.Config(ENV))]
        sync_messages(api, '123', desired, log=lambda *_: None)
        sync_messages(api, '123', desired, log=lambda *_: None)
        self.assertEqual(len(fake.posts()), 1)
        self.assertEqual(len(fake.edits()), 0)
        self.assertEqual(len(desired[0]['payload']['components']), 2)
    def test_existing_legacy_release_is_not_reposted_to_new_channels(self):
        from prisonsbot import context as X, release as R
        from tests.test_bot import ENV as OLD_ENV
        fake = FakeDiscord()
        api = Discord(TOKEN, fake)
        cfg = C.Config(dict(ENV, DISCORD_CHANNEL_ANNOUNCEMENTS=OLD_ENV['DISCORD_CHANNEL_ANNOUNCEMENTS'], DISCORD_CHANNEL_CHANGELOG=OLD_ENV['DISCORD_CHANNEL_CHANGELOG']))
        R.post_release(api, cfg, X.build(cfg, X.release_from_repo('1.2.1')), '1.2.1', log=lambda *_: None)
        first = len(fake.posts())
        data = dict(self.data('theprisons'), tag_name='v1.2.1', body='English\n---\nDeutsch')
        V.post_release(api, cfg, 'theprisons', data, log=lambda *_: None)
        self.assertEqual(len(fake.posts()), first + 1, 'only the new download channel gets a post')
    def test_old_marker_in_target_is_edited_in_place(self):
        from prisonsbot.publish import sync_messages
        fake = FakeDiscord()
        api = Discord(TOKEN, fake)
        cfg = C.Config(ENV)
        desired = V.release_messages(cfg, 'theprisons', self.data('theprisons'))['updates']
        old = copy.deepcopy(desired[0])
        old['key'] = 'release-v1.0.0'
        old['payload']['embeds'][0]['footer']['text'] = 'ThePrisons · release-v1.0.0'
        sync_messages(api, '123', [old], log=lambda *_: None)
        sync_messages(api, '123', desired, log=lambda *_: None)
        self.assertEqual(len(fake.posts()), 1)
        self.assertEqual(len(fake.edits()), 1)
    def test_live_cli_writes_fail_closed_without_approval(self):
        out = []
        self.assertEqual(cli.main(['register'], env=ENV, out=out.append), 2)
        self.assertIn('explicit approval', out[0])
    def test_store_prevents_parallel_or_uncertain_repeat(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(Path(tmp) / 'state.db')
            self.assertIsNone(store.reserve('key'))
            with self.assertRaises(C.ConfigError): Store(Path(tmp) / 'state.db').reserve('key')
            store.complete('key', '42')
            self.assertEqual(store.reserve('key'), '42')
    def test_private_ticket_repeat_and_missing_config(self):
        fake = FakeGuild()
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(Path(tmp) / 'state.db')
            first = V.create_ticket(fake, C.Config(ENV), 'theprisons', 'bug', 'requester', store)
            second = V.create_ticket(fake, C.Config(ENV), 'theprisons', 'bug', 'requester', store)
            self.assertEqual(first, second)
            self.assertEqual(sum(x[0] == 'POST' for x in fake.requests), 1)
            with self.assertRaises(C.ConfigError): V.create_ticket(fake, C.Config({}), 'sky-supra', 'bug', 'other', store)


class ListenerTests(unittest.IsolatedAsyncioTestCase):
    async def test_persistent_controls_and_command_schema(self):
        from prisonsbot.runner import build_client
        client, tree = build_client(C.Config(ENV))
        await client.setup_hook()
        self.assertEqual(len(client.persistent_views), 1)
        view = client.persistent_views[0]
        self.assertTrue(view.is_persistent())
        self.assertEqual(len(view.children), 4)
        self.assertEqual({c.name for c in tree.get_commands()}, {c['name'] for c in commands.registration_payload()})
        by_name = {c.name: c.to_dict(tree) for c in tree.get_commands()}
        for name in ('download', 'changelog', 'support', 'bug', 'suggest'):
            self.assertTrue(by_name[name]['options'][0]['required'])
        self.assertEqual({c['name'] for c in by_name['setup']['options']}, {'preview', 'apply'})
        await client.close()


class WorkflowAndHistoryTests(unittest.TestCase):
    def test_complete_history_reaches_old_message(self):
        calls = []
        def transport(method, url, headers, body):
            calls.append(url)
            page = [{'id': str(300 - i)} for i in range(100)] if len(calls) == 1 else [{'id': '1'}]
            return 200, json.dumps(page).encode()
        self.assertEqual(len(Discord(TOKEN, transport).channel_messages('1')), 101)
        self.assertTrue(calls[1].endswith('&before=201'))
    def test_workflow_rejects_public_writes_and_shell_inputs(self):
        import os
        from prisonsbot import workflow
        with patch.dict(os.environ, {'ACTION': 'register', 'DRY_RUN': 'false'}, clear=True):
            with self.assertRaises(C.ConfigError): workflow.main()
        with patch.dict(os.environ, {'ACTION': 'publish', 'NAMES': 'faq; curl example.com'}, clear=True):
            with self.assertRaises(C.ConfigError): workflow.main()
    def test_workflow_defaults_to_offline_preview(self):
        import os
        from prisonsbot import workflow
        with patch.dict(os.environ, {'ACTION': 'panel'}, clear=True), patch.object(cli, 'main', return_value=0) as mocked:
            self.assertEqual(workflow.main(), 0)
            mocked.assert_called_once_with(['panel', '--dry-run'])
    def test_register_payload_has_privileged_setup_and_required_mod(self):
        by_name = {c['name']: c for c in commands.registration_payload()}
        self.assertEqual(by_name['setup']['default_member_permissions'], '32')
        for name in ('download', 'changelog', 'support', 'bug', 'suggest'):
            self.assertTrue(by_name[name]['options'][0]['required'])


if __name__ == '__main__': unittest.main()
