"""Single-instance supervisor for the persistent Windows launcher (no token in args/logs)."""
import argparse
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import subprocess
import sys
import time
from .config import redact


def backoff(attempt):
    return min(300, 5 * 2 ** min(attempt, 6))


def safe_log(text, token):
    return redact(text, token).rstrip()


def state(path, status, **details):
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temp = target.with_suffix('.tmp')
    temp.write_text(json.dumps({'status': status, 'updated_at': time.time(), **details}), encoding='utf-8')
    temp.replace(target)


class InstanceLock:
    def __init__(self, path):
        self.path = Path(path)
    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.file = self.path.open('a+b')
        self.file.write(b'0')
        self.file.flush()
        self.file.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.file.close()
            raise RuntimeError('Nexora Core is already running') from None
        return self
    def __exit__(self, *args):
        self.file.close()


def supervise(runtime, process_factory=subprocess.Popen, sleep=time.sleep):
    runtime = Path(runtime)
    runtime.mkdir(parents=True, exist_ok=True)
    token = os.environ.get('DISCORD_BOT_TOKEN', '')
    if not token or not os.environ.get('DISCORD_GUILD_ID'):
        state(runtime / 'supervisor.json', 'configuration-required')
        return 2
    logger = logging.getLogger('nexora-supervisor')
    logger.setLevel(logging.INFO)
    handler = RotatingFileHandler(runtime / 'bot.log', maxBytes=2_000_000, backupCount=3, encoding='utf-8')
    logger.addHandler(handler)
    env = dict(os.environ, PYTHONIOENCODING='utf-8', PYTHONUNBUFFERED='1', NEXORA_HEALTH_FILE=str(runtime / 'health.json'), NEXORA_STATE_DB=str(runtime / 'nexora.sqlite3'))
    attempt = 0
    child = None
    try:
        with InstanceLock(runtime / 'bot.lock'):
            while not (runtime / 'stop.request').exists():
                state(runtime / 'supervisor.json', 'starting', pid=os.getpid())
                child = process_factory([sys.executable, '-u', '-m', 'prisonsbot', 'run'], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
                state(runtime / 'supervisor.json', 'running', pid=os.getpid(), child_pid=child.pid)
                # Poll without blocking on a quiet log pipe; logging happens in a reader thread.
                import threading
                def read_output(process):
                    for line in process.stdout:
                        logger.info(safe_log(line, token))
                reader = threading.Thread(target=read_output, args=(child,), daemon=True)
                reader.start()
                started = time.monotonic()
                while child.poll() is None and not (runtime / 'stop.request').exists():
                    sleep(1)
                if child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait()
                reader.join(timeout=5)
                code = child.returncode
                if (runtime / 'stop.request').exists():
                    break
                if code == 2:
                    state(runtime / 'supervisor.json', 'configuration-error', exit_code=code)
                    return code
                attempt = 0 if time.monotonic() - started > 300 else attempt + 1
                delay = backoff(attempt)
                logger.info('Listener exited (%s); restart in %s seconds', code, delay)
                state(runtime / 'supervisor.json', 'restarting', pid=os.getpid(), retry_in=delay)
                for _ in range(delay):
                    if (runtime / 'stop.request').exists(): break
                    sleep(1)
            state(runtime / 'supervisor.json', 'stopped')
            return 0
    except RuntimeError as exc:
        logger.info(safe_log(str(exc), token))
        return 3
    finally:
        if child and child.poll() is None:
            child.terminate()
        logger.removeHandler(handler)
        handler.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True)
    raise SystemExit(supervise(parser.parse_args().runtime))
