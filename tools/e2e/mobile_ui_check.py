"""手机内置页面交互与隔离 v1 存储；真实 Room 由 Android 仪器测试覆盖。"""
import json
import tempfile
import unittest
import uuid
from pathlib import Path

from playwright.sync_api import sync_playwright
import tests  # noqa: F401 - 隔离环境与临时目录，与项目其余验收一致。
from agendum.store import Store

ROOT = Path(__file__).resolve().parents[2]


class MobileUiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(channel="msedge", headless=True)

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(Path(self.tmp.name) / "ui.sqlite3")
        self.task_id = self.store.upsert_task(task_key="ui-source", summary="交实验报告", evidence="周五下午交到实验室")
        self.base = "https://isolated-test.invalid"
        self.context = self.browser.new_context(viewport={"width": 390, "height": 844}, device_scale_factor=1)
        self.addCleanup(self.context.close)
        self.page = self.context.new_page()
        self.page.on("dialog", lambda dialog: dialog.accept())
        self.commands = []
        self.page.expose_function("testNative", self.native)
        self.page.add_init_script("""window.AndroidBridge={command:function(s){var command=JSON.parse(s);window.testNative(s).then(function(r){if(r.saved){NH_render(r.state);NH_saved(command.requestId)}else if(r.error)NH_error(r.error,command.requestId)})},refresh:function(){},exportPending:function(){},reconnect:function(){}};""")
        self.page.goto((ROOT / "android/app/src/main/assets/client.html").as_uri())
        self.render()

    def snapshot(self):
        return {"tasks": [t for t in self.store.sync_page(bootstrap=True)["items"] if not t["deleted"]],
                "conflicts": [], "pending": 0, "connected": True, "server_id": self.store.sync_info()["server_id"], "base": self.base, "lastSuccess": 1}

    def render(self, state=None):
        self.state = state or self.snapshot()
        self.page.evaluate("data => window.NH_render(data)", self.state)

    def native(self, raw):
        command = json.loads(raw)
        self.commands.append(command)
        kind = command["type"]
        if kind in {"resolve", "discard"}:
            return {"saved": False}
        task = next((t for t in self.state["tasks"] if t["uuid"] == command.get("uuid")), None)
        changes = command.get("changes", {})
        if changes.get("status") in {"done", "dismissed", "expired"}:
            changes.update(pinned=False, snooze_until={"kind": "none", "value": ""})
        fields = ("summary", "notes", "deadline", "category", "status", "pinned", "urgent_override", "snooze_until") if kind == "delete" else changes.keys()
        request = {**self.store.sync_info(), "operation_id": str(uuid.uuid4()), "uuid": task["uuid"] if task else str(uuid.uuid4()),
                   "type": kind, "base_version": task["version"] if task else 0,
                   "base": {k: task[k] for k in fields} if task else {}, "changes": changes}
        result = self.store.sync_mutate(request, 'isolated-ui-test')
        self.state = self.snapshot()
        return {"saved": result["ok"], "state": self.state, "error": result.get("error")}

    def test_edit_date_complete_restore_and_delete(self):
        page = self.page
        page.get_by_role("button", name="交实验报告", exact=True).click()
        page.locator("#edit-summary").fill("交修改后的实验报告")
        page.locator("#edit-notes").fill("先检查单位，再提交")
        page.locator("#edit-kind").select_option("date")
        page.locator("#edit-date").fill("2026-11-06")
        page.get_by_role("button", name="保存到手机", exact=True).click()
        page.get_by_role("button", name="交修改后的实验报告", exact=True).wait_for()
        saved = self.store.sync_task(self.task_id)
        self.assertEqual(saved["notes"], "先检查单位，再提交")
        self.assertEqual(saved["deadline"], {"kind": "date", "value": "2026-11-06"})
        self.assertTrue(page.get_by_role("heading", name="2026-11-06", exact=True).is_visible())
        page.locator("#list").get_by_role("button", name="完成", exact=True).click()
        page.locator("#filter").select_option("done")
        page.locator("#list").get_by_role("button", name="恢复", exact=True).wait_for()
        self.assertEqual(self.store.sync_task(self.task_id)["status"], "done")
        page.locator("#list").get_by_role("button", name="恢复", exact=True).click()
        page.locator("#filter").select_option("all")
        page.locator("#list").get_by_role("button", name="交修改后的实验报告", exact=True).wait_for()
        self.assertEqual(self.store.sync_task(self.task_id)["status"], "open")
        page.locator('#calendar-toggle').click()
        page.locator('#clear-day').click()
        output = ROOT / ".tmp-qa/mobile-sync"
        output.mkdir(parents=True, exist_ok=True)
        page.screenshot(path=str(output / "phone-list-calendar.png"), full_page=True)
        page.locator("#list").get_by_role("button", name="交修改后的实验报告", exact=True).click()
        page.locator("#delete").click()
        page.wait_for_function("document.getElementById('editor').hidden && !document.querySelector('#list .task')")
        self.assertTrue(self.store.sync_task(self.task_id)["deleted"])

    def test_conflict_choice_and_local_error_are_visible(self):
        task = self.store.sync_task(self.task_id)
        local = dict(task, summary="手机上的标题", notes="冲突后继续写的备注")
        current = dict(task, summary="电脑上的标题", version=2)
        state = self.snapshot()
        state.update(tasks=[local], pending=2, conflicts=[{"uuid": task["uuid"], "operation_id": "conflict-op", "type": "update", "local": local,
            "result": {"code": "conflict", "fields": ["summary"], "current": current}}])
        self.render(state)
        self.page.locator('.bottom [data-page="account"]').click()
        self.page.locator('.menu-row[data-page="sync"]').click()
        self.page.get_by_label("电脑当前值：电脑上的标题", exact=True).check()
        self.page.get_by_role("button", name="按选择合并提交", exact=True).click()
        self.page.wait_for_timeout(100)
        self.assertEqual(self.commands[-1]["type"], "resolve")
        self.assertFalse(self.commands[-1]["choices"]["summary"])
        self.page.evaluate("NH_error('网络暂不可用，本地编辑仍保留')")
        self.assertTrue(self.page.locator("#feedback").is_visible())
        self.assertIn("本地编辑仍保留", self.page.locator("#feedback").inner_text())


if __name__ == "__main__":
    unittest.main()
