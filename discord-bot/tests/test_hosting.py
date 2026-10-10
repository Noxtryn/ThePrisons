import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from prisonsbot import hosting as H


class HostingTests(unittest.TestCase):
    def test_backoff_is_bounded(self):
        self.assertEqual(H.backoff(0), 5)
        self.assertEqual(H.backoff(1), 10)
        self.assertEqual(H.backoff(1000), 300)
    def test_single_instance_lock(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'bot.lock'
            with H.InstanceLock(target):
                with self.assertRaises(RuntimeError):
                    with H.InstanceLock(target): pass
            with H.InstanceLock(target): pass
    def test_missing_credentials_does_not_launch_process(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {}, clear=True), patch.object(H.subprocess, 'Popen') as process:
            self.assertEqual(H.supervise(tmp), 2)
            process.assert_not_called()
            self.assertEqual(json.loads((Path(tmp) / 'supervisor.json').read_text())['status'], 'configuration-required')
    def test_token_is_redacted_from_log(self):
        self.assertEqual(H.safe_log('oops SECRET\n', 'SECRET'), 'oops <hidden>')
    def test_configuration_error_stops_retry_loop(self):
        class Process:
            pid, returncode = 1, 2
            stdout = ['error SECRET']
            def poll(self): return 2
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {'DISCORD_BOT_TOKEN': 'SECRET', 'DISCORD_GUILD_ID': '123'}, clear=True):
            seen = []
            def launch(args, **kwargs):
                seen.append(args)
                self.assertNotIn('SECRET', str(args))
                return Process()
            self.assertEqual(H.supervise(tmp, process_factory=launch, sleep=lambda _: None), 2)
            self.assertEqual(len(seen), 1)
            self.assertNotIn('SECRET', (Path(tmp) / 'bot.log').read_text())
    def test_stop_request_prevents_new_process(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {'DISCORD_BOT_TOKEN': 'secret', 'DISCORD_GUILD_ID': '123'}, clear=True), patch.object(H.subprocess, 'Popen') as process:
            (Path(tmp) / 'stop.request').write_text('stop')
            self.assertEqual(H.supervise(tmp), 0)
            process.assert_not_called()
