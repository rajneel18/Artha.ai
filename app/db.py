import os
import sqlite3
from contextlib import contextmanager

DB_PATH = os.environ.get("ARTHA_DB", os.path.join(os.path.dirname(__file__), "..", "data", "artha.db"))

SCHEMA = """
CREATE TABLE IF NOT EXISTS transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    date TEXT NOT NULL,
    amount REAL NOT NULL,
    kind TEXT NOT NULL DEFAULT 'debit',
    merchant TEXT NOT NULL DEFAULT 'Unknown',
    category TEXT NOT NULL DEFAULT 'Other',
    note TEXT,
    source TEXT NOT NULL DEFAULT 'manual',
    raw_hash TEXT UNIQUE,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_tx_date ON transactions(date);
CREATE TABLE IF NOT EXISTS merchant_rules (
    merchant TEXT PRIMARY KEY,
    category TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    pattern TEXT NOT NULL,
    match_type TEXT NOT NULL DEFAULT 'contains',   -- contains | regex | merchant
    category TEXT NOT NULL,
    min_amount REAL,
    max_amount REAL,
    priority INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS allowance_history (
    start_date TEXT PRIMARY KEY,
    amount REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS subscriptions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    merchant_key TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    amount REAL NOT NULL,
    period TEXT NOT NULL DEFAULT 'monthly',        -- weekly | monthly | quarterly | yearly
    category TEXT,
    last_date TEXT,
    next_date TEXT,
    status TEXT NOT NULL DEFAULT 'active',         -- active | suggested | cancelled | ignored
    source TEXT NOT NULL DEFAULT 'detected'
);
CREATE TABLE IF NOT EXISTS splits (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tx_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    person TEXT NOT NULL,
    amount REAL NOT NULL,
    settled INTEGER NOT NULL DEFAULT 0,
    settled_at TEXT
);
"""

# Columns added after the first release; applied with ALTER TABLE on existing databases.
TX_COLUMNS = {
    "my_share": "REAL",               # your part of a split expense (NULL = whole amount)
    "needs_review": "INTEGER NOT NULL DEFAULT 0",
    "review_reason": "TEXT",          # uncategorized | unknown_merchant | duplicate | cancelled_subscription | low_confidence
    "duplicate_of": "INTEGER",
    "is_recurring": "INTEGER NOT NULL DEFAULT 0",
}


def init_db(path: str | None = None) -> None:
    path = path or DB_PATH
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with sqlite3.connect(path) as conn:
        conn.executescript(SCHEMA)
        have = {r[1] for r in conn.execute("PRAGMA table_info(transactions)")}
        for col, ddl in TX_COLUMNS.items():
            if col not in have:
                conn.execute(f"ALTER TABLE transactions ADD COLUMN {col} {ddl}")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_tx_review ON transactions(needs_review)")
    try:
        os.chmod(path, 0o600)  # financial data: owner read/write only
    except OSError:
        pass


@contextmanager
def connect(path: str | None = None):
    conn = sqlite3.connect(path or DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA secure_delete = ON")  # deleted rows are overwritten, not just unlinked
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()
