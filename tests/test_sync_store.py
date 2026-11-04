"""同步行为契约：事务、重试、合并、删除与分页，不依赖真实手机。"""
import json
import sqlite3
import tempfile
import unittest
import uuid
from pathlib import Path

from noticehub.store import Store


class SyncStoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "tasks.sqlite3"
        self.store = Store(self.path)
        self.ident = self.store.upsert_task(task_key="source:1", summary="交报告", deadline="2026-11-06T18:00:00")

    def task(self):
        return self.store.sync_task(self.ident)

    def request(self, task=None, changes=None, kind="update"):
        task = task or self.task()
        changes = changes if changes is not None else {"summary": "交实验报告"}
        return {**self.store.sync_info(), "operation_id": str(uuid.uuid4()), "uuid": task["uuid"],
                "type": kind, "base_version": task["version"], "base": {k: task[k] for k in changes}, "changes": changes}

    def apply(self, request, actor="phone"):
        return self.store.sync_mutate(request, actor)

    def test_retry_returns_same_receipt_and_only_one_event(self):
        request = self.request()
        before = len(self.store.sync_page()["items"])
        first = self.apply(request)
        self.assertTrue(first["ok"])
        self.assertEqual(self.apply(request), first)
        self.assertEqual(len(self.store.sync_page()["items"]), before + 1)
        events = self.store.list_task_events(self.ident)
        self.assertEqual(sum(e["event"] == "managed" for e in events), 1)
        request["changes"]["summary"] = "另一个内容"
        with self.assertRaises(ValueError):
            self.apply(request)

    def test_nonoverlapping_changes_merge_and_same_field_conflicts_atomically(self):
        base = self.task()
        self.assertTrue(self.apply(self.request(base, {"notes": "电脑备注"}), "desktop")["ok"])
        self.assertTrue(self.apply(self.request(base, {"summary": "手机标题"}))["ok"])
        conflict = self.apply(self.request(base, {"summary": "旧手机标题", "notes": "不能部分提交"}))
        self.assertEqual(conflict["code"], "conflict")
        self.assertEqual(self.task()["summary"], "手机标题")
        self.assertEqual(self.task()["notes"], "电脑备注")

    def test_delete_tombstone_prevents_old_generator_resurrection(self):
        task = self.task()
        fields = {name: task[name] for name in ("summary", "notes", "deadline", "category", "status", "pinned", "urgent_override", "snooze_until")}
        request = self.request(task, fields, "delete")
        result = self.apply(request)
        self.assertTrue(result["task"]["deleted"])
        self.assertIsNone(self.store.get_task(self.ident))
        self.assertEqual(self.store.upsert_task(task_key="source:1", summary="旧提取"), 0)
        self.assertEqual(self.apply(self.request(task))["code"], "deleted")
        self.assertEqual(self.apply(request), result)

    def test_delete_conflicts_with_concurrent_edit(self):
        task = self.task()
        fields = {name: task[name] for name in ("summary", "notes", "deadline", "category", "status", "pinned", "urgent_override", "snooze_until")}
        self.apply(self.request(task, {"notes": "较新的编辑"}), "desktop")
        self.assertEqual(self.apply(self.request(task, fields, "delete"))["code"], "conflict")
        self.assertIsNotNone(self.store.get_task(self.ident))

    def test_manual_fields_survive_generation_and_legacy_correction(self):
        self.apply(self.request(changes={"summary": "人工标题", "deadline": {"kind": "date", "value": "2026-11-08"}}))
        self.store.upsert_task(task_key="source:1", summary="自动标题", deadline="2026-11-09T12:00:00", evidence="新来源")
        self.assertEqual(self.task()["summary"], "人工标题")
        self.assertEqual(self.task()["deadline"], {"kind": "date", "value": "2026-11-08"})
        self.assertEqual(self.task()["evidence"], "新来源")
        self.store.apply_task_correction(self.ident, "category", value="academic")
        self.store.upsert_task(task_key="source:1", summary="自动", category="info")
        self.assertEqual(self.task()["category"], "academic")

    def test_legacy_actions_and_clear_are_in_change_stream(self):
        version = self.task()["version"]
        self.store.apply_task_action(self.ident, "done")
        self.assertEqual(self.task()["version"], version + 1)
        self.assertEqual(self.store.clear_finished_tasks(), 1)
        self.assertTrue(self.task()["deleted"])
        self.assertTrue(self.store.sync_page()["items"][-1]["deleted"])

    def test_noop_does_not_increase_version(self):
        initial = self.task()
        self.store.upsert_task(task_key="source:1", summary="交报告", deadline="2026-11-06T18:00:00")
        self.assertEqual(self.task()["version"], initial["version"])
        self.apply(self.request(initial, {"summary": initial["summary"]}))
        self.assertEqual(self.task()["version"], initial["version"])

    def test_state_constraints_are_checked_after_merge(self):
        base = self.task()
        self.store.set_task_pinned(self.ident, True)
        with self.assertRaises(ValueError):
            self.apply(self.request(base, {"status": "done"}))
        self.assertEqual(self.task()["status"], "open")

    def test_create_uuid_and_response_loss_only_create_once(self):
        request = {**self.store.sync_info(), "operation_id": str(uuid.uuid4()), "uuid": str(uuid.uuid4()),
                   "type": "create", "changes": {"summary": "离线新建", "deadline": {"kind": "date", "value": "2026-11-02"}}}
        first = self.apply(request)
        self.assertEqual(first["task"]["version"], 1)
        self.assertEqual(self.apply(request), first)
        self.assertEqual(len(self.store.list_tasks()), 2)

    def test_pagination_fixed_upper_with_over_800_and_concurrent_writes(self):
        with self.store._connect() as c:
            c.executemany("INSERT INTO tasks(task_key,summary,created_at,updated_at) VALUES(?,?,?,?)",
                          [(f"many:{i}", str(i), "2026-11-01", "2026-11-01") for i in range(805)])
        page = self.store.sync_page(bootstrap=True, limit=100)
        upper = page["upper"]
        seen = {t["uuid"] for t in page["items"]}
        new_id = self.store.upsert_task(task_key="after-snapshot", summary="分页中新增")
        self.apply(self.request(changes={"summary": "分页中修改"}))
        while page["has_more"]:
            page = self.store.sync_page(bootstrap=True, limit=100, upper=upper, key=page["key"])
            seen.update(t["uuid"] for t in page["items"])
        self.assertEqual(len(seen), 806)
        delta = self.store.sync_page(after=upper)
        self.assertEqual(len(delta["items"]), 2)
        self.assertIn(self.store.sync_task(new_id)["uuid"], {t["uuid"] for t in delta["items"]})

    def test_transaction_rolls_back_business_and_ledger_together(self):
        before = self.task()
        with self.assertRaises(RuntimeError):
            with self.store._connect() as c:
                c.execute("UPDATE tasks SET summary='不应保存' WHERE id=?", (self.ident,))
                raise RuntimeError("中断")
        self.assertEqual(self.task(), before)

    def test_restart_requires_recalibration_but_receipt_survives(self):
        request = self.request()
        first = self.apply(request)
        epoch = self.store.sync_epoch
        self.store = Store(self.path)
        self.assertNotEqual(epoch, self.store.sync_epoch)
        self.assertEqual(self.apply(request), first)
        stale = self.request()
        stale["epoch"] = epoch
        self.assertEqual(self.apply(stale)["code"], "reset_required")

    def test_repair_new_device_identity_still_recognizes_original_receipt(self):
        request = self.request()
        accepted = self.apply(request, "old-device")
        self.assertEqual(self.apply(request, "new-device"), accepted)
        self.assertEqual(sum(e["event"] == "managed" for e in self.store.list_task_events(self.ident)), 1)

    def test_same_instant_with_different_offset_is_not_a_conflict(self):
        task = self.task()
        request = self.request(task, {"deadline": {"kind": "datetime", "value": "2026-11-10T15:00:00+09:00"}})
        equivalent = json.loads(json.dumps(task["deadline"]))
        import datetime as dt
        value = dt.datetime.fromisoformat(equivalent["value"]).astimezone(dt.timezone.utc)
        request["base"]["deadline"]["value"] = value.isoformat()
        self.assertTrue(self.apply(request)["ok"])

    def test_completed_event_is_recorded_once_for_metrics(self):
        request = self.request(changes={"status": "done", "pinned": False, "snooze_until": {"kind": "none", "value": ""}})
        self.assertTrue(self.apply(request)["ok"])
        self.apply(request)
        self.assertEqual(sum(e["event"] == "done" for e in self.store.list_task_events(self.ident)), 1)

    def test_pair_single_use_device_revoke_and_persistence(self):
        ticket = self.store.sync_pair_start("我的手机", "local")
        self.assertEqual(self.store.sync_pair_exchange(ticket["id"], ticket["secret"])["status"], "pending")
        self.store.sync_pair_approve(ticket["id"])
        pair = self.store.sync_pair_exchange(ticket["id"], ticket["secret"])
        self.assertEqual(self.store.sync_device(pair["token"]), pair["device_id"])
        with self.assertRaises(ValueError):
            self.store.sync_pair_exchange(ticket["id"], ticket["secret"])
        self.store = Store(self.path)
        self.assertEqual(self.store.sync_device(pair["token"]), pair["device_id"])
        self.store.sync_revoke(pair["device_id"])
        self.assertIsNone(self.store.sync_device(pair["token"]))


if __name__ == "__main__":
    unittest.main()
