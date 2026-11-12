"""日程同步账本。脏记录触发器覆盖旧写入口，账本与业务写入同事务提交。"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import secrets
import sqlite3
import uuid
from typing import Any


def dumps(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


SCHEMA = """
CREATE TABLE IF NOT EXISTS sync_records (
    uid TEXT PRIMARY KEY, task_id INTEGER UNIQUE NOT NULL, task_key TEXT UNIQUE NOT NULL,
    version INTEGER NOT NULL, data TEXT NOT NULL, manual_fields TEXT NOT NULL DEFAULT '[]',
    deleted INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS sync_changes (
    seq INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL, version INTEGER NOT NULL,
    data TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS sync_changes_uid ON sync_changes(uid, seq);
CREATE TABLE IF NOT EXISTS sync_dirty (task_id INTEGER PRIMARY KEY, task_key TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS sync_receipts (
    actor TEXT NOT NULL, operation_id TEXT NOT NULL, request_hash TEXT NOT NULL,
    response TEXT NOT NULL, PRIMARY KEY(actor, operation_id)
);
CREATE TABLE IF NOT EXISTS sync_devices (
    id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT UNIQUE NOT NULL,
    created_at TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS sync_pairings (
    id TEXT PRIMARY KEY, secret_hash TEXT NOT NULL, code TEXT NOT NULL,
    name TEXT NOT NULL, source TEXT NOT NULL, expires REAL NOT NULL,
    approved INTEGER NOT NULL DEFAULT 0, consumed INTEGER NOT NULL DEFAULT 0
);
CREATE TRIGGER IF NOT EXISTS sync_insert AFTER INSERT ON tasks BEGIN
    INSERT OR IGNORE INTO sync_dirty VALUES (NEW.id, NEW.task_key);
END;
CREATE TRIGGER IF NOT EXISTS sync_update AFTER UPDATE ON tasks BEGIN
    INSERT OR IGNORE INTO sync_dirty VALUES (NEW.id, NEW.task_key);
END;
CREATE TRIGGER IF NOT EXISTS sync_delete AFTER DELETE ON tasks BEGIN
    INSERT OR IGNORE INTO sync_dirty VALUES (OLD.id, OLD.task_key);
END;
"""

EDITABLE = frozenset({"summary", "notes", "deadline", "category", "status", "pinned", "urgent_override", "snooze_until"})
STATES = frozenset({"candidate", "open", "done", "dismissed", "expired"})
ZONE = dt.timezone(dt.timedelta(hours=8))


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def deadline_out(raw: str, kind: str = "") -> dict[str, Any]:
    if not raw:
        return {"kind": "none", "value": ""}
    if kind == "date" or (not kind and len(raw) == 10):
        try:
            return {"kind": "date", "value": dt.date.fromisoformat(raw[:10]).isoformat()}
        except ValueError:
            return {"kind": "none", "value": ""}
    try:
        parsed = dt.datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return {"kind": "none", "value": ""}
    if parsed.tzinfo is None:
        parsed = parsed.astimezone()
    return {"kind": "datetime", "value": parsed.isoformat(timespec="seconds")}


def deadline_in(value: Any) -> tuple[str, str]:
    if not isinstance(value, dict) or set(value) != {"kind", "value"}:
        raise ValueError("截止时间必须包含 kind 和 value")
    kind, text = value["kind"], value["value"]
    if not isinstance(text, str):
        raise ValueError("截止时间必须是文本")
    if kind == "none" and text == "":
        return "", "none"
    if kind == "date" and len(text) == 10:
        return dt.date.fromisoformat(text).isoformat(), "date"
    if kind == "datetime":
        parsed = dt.datetime.fromisoformat(text.replace("Z", "+00:00"))
        if parsed.tzinfo is not None:
            return parsed.astimezone().replace(tzinfo=None).isoformat(timespec="seconds"), "datetime"
    raise ValueError("请选择有效日期或带时区的时刻")


def same_field(field: str, left: Any, right: Any) -> bool:
    if field in {"deadline", "snooze_until"} and isinstance(left, dict) and isinstance(right, dict):
        if left.get("kind") == right.get("kind") == "datetime":
            try:
                return dt.datetime.fromisoformat(left["value"].replace("Z", "+00:00")) == dt.datetime.fromisoformat(right["value"].replace("Z", "+00:00"))
            except (ValueError, KeyError, AttributeError):
                return False
    return left == right


def project(row: sqlite3.Row, uid: str, version: int) -> dict[str, Any]:
    r = dict(row)
    return {
        "uuid": uid, "id": r["id"], "version": version, "deleted": False,
        "summary": r["summary"], "notes": r.get("notes", ""),
        "deadline": deadline_out(r["deadline"], r.get("deadline_kind", "")),
        "category": r["category"], "status": r["status"], "pinned": bool(r["pinned"]),
        "urgent_override": None if r["urgent_override"] is None else bool(r["urgent_override"]),
        "snooze_until": deadline_out(r["snooze_until"]),
        "groups": json.loads(r["groups"]), "details": json.loads(r["details"]),
        "evidence": r["evidence"], "sender": r["sender"], "source": r["source"],
        "created_at": r["created_at"], "updated_at": r["updated_at"],
        "last_reminded_at": r["last_reminded_at"], "remind_count": r["remind_count"],
    }


def initialize(connection: sqlite3.Connection) -> None:
    connection.executescript(SCHEMA)
    connection.execute("INSERT OR IGNORE INTO meta(key,value) VALUES('sync_server_id',?)", (str(uuid.uuid4()),))
    connection.execute("INSERT OR IGNORE INTO sync_dirty SELECT id, task_key FROM tasks WHERE id NOT IN (SELECT task_id FROM sync_records)")
    # 老纠错记录是能够确认人工接管的依据，不猜测缺失的历史。
    for row in connection.execute("SELECT task_id,detail FROM task_events WHERE event='corrected'").fetchall():
        try:
            detail = json.loads(row["detail"])
            name = {"deadline": "deadline", "clear_deadline": "deadline", "category": "category", "not_urgent": "category"}.get(detail.get("type"))
            if name:
                flush(connection)
                protect(connection, row["task_id"], [name])
        except (ValueError, TypeError):
            continue
    flush(connection)


def flush(connection: sqlite3.Connection) -> None:
    dirty_rows = connection.execute("SELECT * FROM sync_dirty ORDER BY task_id").fetchall()
    if not dirty_rows:
        return
    for dirty in dirty_rows:
        old = connection.execute("SELECT * FROM sync_records WHERE task_id=?", (dirty["task_id"],)).fetchone()
        row = connection.execute("SELECT * FROM tasks WHERE id=?", (dirty["task_id"],)).fetchone()
        if old is None and row is None:
            continue
        uid = old["uid"] if old else str(uuid.uuid4())
        version = old["version"] + 1 if old else 1
        data = project(row, uid, version) if row else {"uuid": uid, "version": version, "deleted": True}
        if old:
            previous, compare = json.loads(old["data"]), dict(data)
            for field in ("version", "updated_at"):
                previous.pop(field, None)
                compare.pop(field, None)
            if previous == compare:
                continue
        text = dumps(data)
        connection.execute(
            "INSERT INTO sync_records(uid,task_id,task_key,version,data,deleted) VALUES(?,?,?,?,?,?) "
            "ON CONFLICT(uid) DO UPDATE SET version=excluded.version,data=excluded.data,deleted=excluded.deleted",
            (uid, dirty["task_id"], dirty["task_key"], version, text, int(row is None)),
        )
        connection.execute("INSERT INTO sync_changes(uid,version,data) VALUES(?,?,?)", (uid, version, text))
    connection.execute("DELETE FROM sync_dirty")


def protect(connection: sqlite3.Connection, task_id: int, fields: list[str]) -> None:
    row = connection.execute("SELECT manual_fields FROM sync_records WHERE task_id=?", (task_id,)).fetchone()
    if row:
        fields = sorted(set(json.loads(row[0])) | set(fields))
        connection.execute("UPDATE sync_records SET manual_fields=? WHERE task_id=?", (dumps(fields), task_id))


class SyncStoreMixin:
    def sync_info(self) -> dict[str, Any]:
        return {"protocol": 1, "server_id": self.meta_get("sync_server_id"), "epoch": self.sync_epoch,
                "timezone": "Asia/Shanghai"}

    def sync_page(self, *, after: int = 0, limit: int = 200, upper: int | None = None, bootstrap: bool = False, key: str = "") -> dict[str, Any]:
        if after < 0 or not 1 <= limit <= 500 or (upper is not None and upper < 0):
            raise ValueError("无效同步游标或分页大小")
        with self._connect() as c:
            c.execute("BEGIN")
            current = c.execute("SELECT COALESCE(MAX(seq),0) FROM sync_changes").fetchone()[0]
            bound = current if upper is None else upper
            if bound > current or after > current:
                raise ValueError("同步游标已失效，请重新校准")
            if bootstrap:
                rows = c.execute(
                    "SELECT s.* FROM sync_changes s JOIN (SELECT uid,MAX(seq) AS seq FROM sync_changes WHERE seq<=? GROUP BY uid) last "
                    "ON s.seq=last.seq WHERE s.uid>? ORDER BY s.uid LIMIT ?", (bound, key, limit + 1)).fetchall()
            else:
                rows = c.execute("SELECT * FROM sync_changes WHERE seq>? AND seq<=? ORDER BY seq LIMIT ?", (after, bound, limit + 1)).fetchall()
            more = len(rows) > limit
            rows = rows[:limit]
            return {**self.sync_info_cached(c), "ok": True, "upper": bound, "has_more": more,
                    "cursor": (rows[-1]["seq"] if rows and more and not bootstrap else bound),
                    "key": rows[-1]["uid"] if rows and bootstrap else "",
                    "items": [json.loads(row["data"]) for row in rows]}

    def sync_info_cached(self, c: sqlite3.Connection) -> dict[str, Any]:
        return {"server_id": c.execute("SELECT value FROM meta WHERE key='sync_server_id'").fetchone()[0], "epoch": self.sync_epoch}

    def sync_task(self, task_id: int) -> dict[str, Any] | None:
        with self._connect() as c:
            row = c.execute("SELECT data FROM sync_records WHERE task_id=?", (task_id,)).fetchone()
            return json.loads(row[0]) if row else None

    def sync_mutate(self, request: dict[str, Any], actor: str) -> dict[str, Any]:
        if not isinstance(request, dict):
            raise ValueError("操作必须是对象")
        op = request.get("operation_id")
        uid = request.get("uuid")
        if not isinstance(op, str) or not 1 <= len(op) <= 100 or not isinstance(uid, str):
            raise ValueError("缺少有效的操作 ID 或日程 UUID")
        try:
            uuid.UUID(uid)
        except (ValueError, AttributeError) as error:
            raise ValueError("无效日程 UUID") from error
        signature = hashlib.sha256(dumps(request).encode()).hexdigest()
        with self._connect() as c:
            c.execute("BEGIN IMMEDIATE")
            # 同一主人重新配对后 actor 会变化，已提交的手机请求仍须认原回执。
            receipt = c.execute("SELECT * FROM sync_receipts WHERE operation_id=?", (op,)).fetchone()
            if receipt:
                if receipt["request_hash"] != signature:
                    raise ValueError("同一个操作 ID 不能用于不同内容")
                return json.loads(receipt["response"])
            if request.get("epoch") != self.sync_epoch or request.get("server_id") != self.sync_info_cached(c)["server_id"]:
                return {"ok": False, "code": "reset_required", **self.sync_info_cached(c)}
            result = self._sync_apply(c, request)
            result["operation_id"] = op
            c.execute("INSERT INTO sync_receipts VALUES(?,?,?,?)", (actor, op, signature, dumps(result)))
            return result

    def _sync_apply(self, c: sqlite3.Connection, request: dict[str, Any]) -> dict[str, Any]:
        uid, kind = request["uuid"], request.get("type")
        row = c.execute("SELECT * FROM sync_records WHERE uid=?", (uid,)).fetchone()
        current = json.loads(row["data"]) if row else None
        changes, base = request.get("changes", {}), request.get("base", {})
        if kind not in {"create", "update", "delete"} or not isinstance(changes, dict) or not isinstance(base, dict):
            raise ValueError("无效日程操作")
        if set(changes) - EDITABLE:
            raise ValueError("包含不允许修改的字段")
        if kind == "create" and row:
            return {"ok": False, "code": "conflict", "fields": ["deleted" if row["deleted"] else "uuid"], "current": current}
        if kind != "create" and (row is None or row["deleted"]):
            if kind == "delete" and row:
                return {"ok": True, "task": current}
            return {"ok": False, "code": "deleted", "current": current}
        if kind != "create":
            version = request.get("base_version")
            if type(version) is not int or version < 1 or version > row["version"]:
                raise ValueError("缺少有效基础版本")
            fields = list(changes)
            if kind == "delete":
                fields = sorted(EDITABLE)
            if set(fields) - set(base):
                raise ValueError("必须提供待修改字段的原值")
            conflicts = [f for f in fields if not same_field(f, current.get(f), base.get(f)) and (kind == "delete" or not same_field(f, current.get(f), changes[f]))]
            if conflicts:
                return {"ok": False, "code": "conflict", "fields": conflicts, "current": current}
        if kind == "delete":
            c.execute("DELETE FROM task_events WHERE task_id=?", (row["task_id"],))
            c.execute("DELETE FROM tasks WHERE id=?", (row["task_id"],))
        else:
            merged = dict(current or {"summary": "", "notes": "", "deadline": {"kind": "none", "value": ""},
                                      "category": "action", "status": "open", "pinned": False,
                                      "urgent_override": None, "snooze_until": {"kind": "none", "value": ""}})
            merged.update(changes)
            columns = self._sync_validate(merged)
            if current and current["status"] == merged["status"]:
                columns.pop("done_at", None)
            if current is None or current["category"] != merged["category"]:
                columns["importance"] = {"urgent": 5, "action": 4, "academic": 3, "info": 3}[merged["category"]]
            if kind == "create":
                stamp = dt.datetime.now().isoformat(timespec="seconds")
                columns.update(task_key="manual:" + uid, source="manual", confidence=1.0, created_at=stamp, updated_at=stamp)
                names = list(columns)
                c.execute("INSERT INTO tasks(%s) VALUES(%s)" % (",".join(names), ",".join("?" for _ in names)), list(columns.values()))
                task_id = c.execute("SELECT last_insert_rowid()").fetchone()[0]
                c.execute("INSERT INTO sync_records(uid,task_id,task_key,version,data) VALUES(?,?,?,?,?)", (uid, task_id, columns["task_key"], 0, "{}"))
            else:
                task_id = row["task_id"]
                columns["updated_at"] = dt.datetime.now().isoformat(timespec="seconds")
                c.execute("UPDATE tasks SET %s WHERE id=?" % ",".join(f"{name}=?" for name in columns), [*columns.values(), task_id])
            flush(c)
            protect(c, task_id, list(changes))
            if current is None or any(current.get(f) != merged.get(f) for f in EDITABLE):
                self._add_event(c, task_id, "managed", {"operation": request["operation_id"], "fields": list(changes)}, utc_now())
                stamp = dt.datetime.now().isoformat(timespec="seconds")
                if current is None:
                    self._add_event(c, task_id, "created", {"status": merged["status"], "reason": "手机或网页手动新建"}, stamp)
                elif current["status"] != merged["status"]:
                    event = {"done": "done", "dismissed": "dismissed", "open": "confirmed" if current["status"] == "candidate" else "reopened"}.get(merged["status"], "status_changed")
                    self._add_event(c, task_id, event, {"previous": current["status"]}, stamp)
                if current and current["snooze_until"] != merged["snooze_until"]:
                    self._add_event(c, task_id, "snoozed", {"until": columns.get("snooze_until", "")}, stamp)
        flush(c)
        fresh = c.execute("SELECT data FROM sync_records WHERE uid=?", (uid,)).fetchone()
        return {"ok": True, "task": json.loads(fresh[0])}

    @staticmethod
    def _sync_validate(task: dict[str, Any]) -> dict[str, Any]:
        summary, notes = task["summary"], task["notes"]
        if not isinstance(summary, str) or not summary.strip() or len(summary.strip()) > 200:
            raise ValueError("待办内容请填写 1 到 200 字")
        if not isinstance(notes, str) or len(notes) > 10000:
            raise ValueError("备注请控制在 10000 字以内")
        if task["status"] not in STATES or task["category"] not in {"urgent", "action", "academic", "info"}:
            raise ValueError("无效状态或分类")
        if type(task["pinned"]) is not bool or (task["urgent_override"] is not None and type(task["urgent_override"]) is not bool):
            raise ValueError("无效置顶或紧急标记")
        if task["status"] in {"done", "dismissed", "expired"} and task["pinned"]:
            raise ValueError("已结束的待办不能置顶，请先取消置顶或恢复待办")
        deadline, deadline_kind = deadline_in(task["deadline"])
        snooze, snooze_kind = deadline_in(task["snooze_until"])
        if snooze_kind == "date" or (snooze and task["status"] not in {"open", "candidate"}):
            raise ValueError("稍后提醒需要未完成待办和具体时刻")
        return {"summary": summary.strip(), "notes": notes, "deadline": deadline, "deadline_kind": deadline_kind,
                "category": task["category"], "status": task["status"], "pinned": int(task["pinned"]),
                "urgent_override": None if task["urgent_override"] is None else int(task["urgent_override"]),
                "snooze_until": snooze, "done_at": utc_now() if task["status"] == "done" else ""}

    def sync_device(self, token: str) -> str | None:
        if not token:
            return None
        with self._connect() as c:
            row = c.execute("SELECT id FROM sync_devices WHERE token_hash=? AND revoked=0", (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
            return row[0] if row else None

    def sync_devices(self) -> list[dict[str, Any]]:
        with self._connect() as c:
            return [dict(r) for r in c.execute("SELECT id,name,created_at,revoked FROM sync_devices ORDER BY created_at")]

    def sync_revoke(self, device_id: str) -> None:
        with self._connect() as c:
            c.execute("UPDATE sync_devices SET revoked=1 WHERE id=?", (device_id,))

    def sync_pair_start(self, name: str, source: str, *, approved: bool = False) -> dict[str, Any]:
        import time
        now = time.time()
        with self._connect() as c:
            c.execute("BEGIN IMMEDIATE")
            count = c.execute("SELECT COUNT(*) FROM sync_pairings WHERE source=? AND expires>?", (source, now - 300)).fetchone()[0]
            if count >= 3 and not approved:
                raise ValueError("配对请求过多，请稍后重试")
            ident, secret, code = str(uuid.uuid4()), secrets.token_urlsafe(32), f"{secrets.randbelow(1000000):06d}"
            c.execute("INSERT INTO sync_pairings(id,secret_hash,code,name,source,expires,approved) VALUES(?,?,?,?,?,?,?)",
                      (ident, hashlib.sha256(secret.encode()).hexdigest(), code, name[:80], source, now + 300, int(approved)))
            return {"ok": True, "id": ident, "secret": secret, "code": code, "expires_in": 300}

    def sync_pair_pending(self) -> list[dict[str, Any]]:
        import time
        with self._connect() as c:
            return [dict(r) for r in c.execute("SELECT id,code,name FROM sync_pairings WHERE expires>? AND approved=0 AND consumed=0", (time.time(),))]

    def sync_pair_approve(self, ident: str) -> None:
        import time
        with self._connect() as c:
            c.execute("UPDATE sync_pairings SET approved=1 WHERE id=? AND expires>? AND consumed=0", (ident, time.time()))

    def sync_pair_exchange(self, ident: str, secret: str) -> dict[str, Any]:
        import time
        with self._connect() as c:
            c.execute("BEGIN IMMEDIATE")
            row = c.execute("SELECT * FROM sync_pairings WHERE id=? AND secret_hash=? AND consumed=0 AND expires>?",
                            (ident, hashlib.sha256(secret.encode()).hexdigest(), time.time())).fetchone()
            if row is None:
                raise ValueError("配对信息无效或已过期，请重新配对")
            if not row["approved"]:
                return {"ok": True, "status": "pending"}
            token, device_id = secrets.token_urlsafe(32), str(uuid.uuid4())
            c.execute("INSERT INTO sync_devices(id,name,token_hash,created_at) VALUES(?,?,?,?)",
                      (device_id, row["name"], hashlib.sha256(token.encode()).hexdigest(), utc_now()))
            c.execute("UPDATE sync_pairings SET consumed=1 WHERE id=?", (ident,))
            return {"ok": True, "status": "approved", "token": token, "device_id": device_id, **self.sync_info_cached(c)}
