"""Durable cross-process reservations. Uncertain writes fail closed until reconciled."""
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from .config import ConfigError


class Store:
    def __init__(self, path):
        self.path = str(path)
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS operations (key TEXT PRIMARY KEY, status TEXT NOT NULL, object_id TEXT)")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=30)
        try:
            with db:
                yield db
        finally:
            db.close()

    def reserve(self, key):
        with self.connect() as db:
            cursor = db.execute("INSERT OR IGNORE INTO operations VALUES (?, 'pending', NULL)", (key,))
            if cursor.rowcount:
                return None
            status, object_id = db.execute("SELECT status, object_id FROM operations WHERE key=?", (key,)).fetchone()
            if status != "complete":
                raise ConfigError("Operation is pending/uncertain; reconcile before retry: " + key)
            return object_id

    def complete(self, key, object_id):
        with self.connect() as db:
            db.execute("UPDATE operations SET status='complete', object_id=? WHERE key=?", (str(object_id), key))
