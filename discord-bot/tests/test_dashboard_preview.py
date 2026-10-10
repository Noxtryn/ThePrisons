import copy
import tempfile
import unittest
from pathlib import Path
from prisonsbot.dashboard_core import DraftStore, DraftError
from prisonsbot.dashboard_preview import compile_preview
from prisonsbot import config as C, nexora as N
from tests.test_nexora import state, GUILD, ROLE_IDS


class PreviewTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = DraftStore(Path(self.temp.name) / 'drafts.db')
        self.snapshot = state()
        self.cfg = C.Config({'DISCORD_GUILD_ID': GUILD})

    def draft(self, module, scope='community'):
        return self.store.get(GUILD, scope, module)

    def test_simulation_is_explicit_and_never_applyable(self):
        proposal = compile_preview(self.draft('messages'), now=1)
        self.assertTrue(proposal['simulation'])
        self.assertFalse(proposal['live_apply_available'])
        self.assertTrue(proposal['blocking'])
        self.assertEqual(proposal['revision'], 0)

    def test_staff_role_cannot_be_autorole(self):
        draft = self.draft('roles')
        draft['document']['settings']['member_role'] = ROLE_IDS['Administrator']
        with self.assertRaises(DraftError):
            compile_preview(draft, self.snapshot, self.cfg)

    def test_autorole_hierarchy_and_managed_role(self):
        draft = self.draft('roles')
        draft['document']['settings']['member_role'] = ROLE_IDS['Member']
        role = next(r for r in self.snapshot['roles'] if r['id'] == ROLE_IDS['Member'])
        role['position'] = 100
        with self.assertRaises(DraftError):
            compile_preview(draft, self.snapshot, self.cfg)
        role['position'], role['managed'] = 1, True
        with self.assertRaises(DraftError):
            compile_preview(draft, self.snapshot, self.cfg)

    def test_valid_member_role_preserves_snapshot(self):
        before = copy.deepcopy(self.snapshot)
        draft = self.draft('roles')
        draft['document']['settings']['member_role'] = ROLE_IDS['Member']
        proposal = compile_preview(draft, self.snapshot, self.cfg, now=1)
        self.assertFalse(proposal['simulation'])
        self.assertEqual(self.snapshot, before)
        self.assertEqual(proposal['changes'][0]['member_role'], ROLE_IDS['Member'])

    def test_ticket_cannot_expose_to_mod_subscribers(self):
        draft = self.draft('tickets', 'sky-supra')
        category = draft['document']['settings']['category_id']
        self.snapshot['channels'].append({'id': category, 'name': 'Support', 'type': 4})
        draft['document']['settings']['staff_role_ids'] = ROLE_IDS['Sky Supra']
        with self.assertRaises(DraftError):
            compile_preview(draft, self.snapshot, self.cfg)

    def test_release_target_cannot_cross_mods(self):
        draft = self.draft('releases', 'sky-supra')
        target = '600000000000000001'
        draft['document']['settings']['channel_id'] = target
        self.snapshot['channels'].append({'id': target, 'name': 'tp-updates', 'type': 0})
        cfg = C.Config({'DISCORD_GUILD_ID': GUILD, 'DISCORD_CHANNEL_SKY_SUPRA_UPDATES': '600000000000000002'})
        proposal = compile_preview(draft, self.snapshot, cfg)
        self.assertTrue(proposal['blocking'])
        self.assertEqual(proposal['payload']['allowed_mentions'], {'parse': []})

    def test_message_unknown_channel_rejected(self):
        draft = self.draft('messages')
        draft['document']['settings']['channel_id'] = '600000000000000001'
        with self.assertRaises(DraftError):
            compile_preview(draft, self.snapshot, self.cfg)

    def test_structure_renames_reuse_existing_id(self):
        self.snapshot['channels'].append({'id': '600000000000000001', 'name': 'COMMUNITY', 'type': 4, 'permission_overwrites': []})
        draft = self.draft('setup')
        draft['document']['settings']['community_category'] = 'NEXORA LOUNGE'
        proposal = compile_preview(draft, self.snapshot, self.cfg)
        change = next(c for c in proposal['changes'] if c['key'] == 'community')
        self.assertEqual(change['id'], '600000000000000001')
        self.assertEqual(change['body']['name'], 'NEXORA LOUNGE')
        self.assertIn('permission_matrix', proposal)
        self.assertFalse(any(c.get('action') == 'delete' for c in proposal['changes']))

    def test_invalid_channel_pattern(self):
        draft = self.draft('setup')
        draft['document']['settings']['channel_pattern'] = '{name}-{other}'
        with self.assertRaises(DraftError):
            compile_preview(draft)

    def test_revision_and_content_bind_digest(self):
        draft = self.draft('messages')
        first = compile_preview(draft, now=1)
        draft['document']['settings']['title'] = 'Neuer Titel'
        second = compile_preview(draft, now=1)
        self.assertNotEqual(first['digest'], second['digest'])
        self.store.record_preview(GUILD, first, 'owner')
        self.store.record_preview(GUILD, first, 'owner')
        self.assertEqual(len(self.store.previews(GUILD)), 1)
        self.assertEqual(self.store.previews('other'), [])
