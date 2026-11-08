"""电脑实际页面的 v1 写入与并发冲突验收；通过指定模块运行。"""
import tempfile
import unittest
import uuid
from pathlib import Path

import tests  # noqa: F401
from playwright.sync_api import sync_playwright
from noticehub.config import Settings
from noticehub.store import Store
from noticehub.webapp import TaskWebServer


class DesktopSyncTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(channel="msedge", headless=True)

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.store = Store(Path(temporary.name) / "desktop.sqlite3")
        self.task_id = self.store.upsert_task(task_key="desktop", summary="电脑编辑前")
        settings = Settings(web_host="127.0.0.1", web_port=0, web_token="desktop-test", data_dir=Path(temporary.name), mobile_base_url="https://sync.example.com")
        self.server = TaskWebServer(settings, self.store)
        self.assertTrue(self.server.start())
        self.addCleanup(self.server.stop)
        self.context = self.browser.new_context(viewport={"width": 1280, "height": 960})
        self.addCleanup(self.context.close)
        self.page = self.context.new_page()
        self.page.on("pageerror", lambda error: print("PAGE_ERROR", str(error), flush=True))
        # 与本组无关的安装检测和外部公告不应读取本机 QQ 或访问外部网络。
        def isolate_auxiliary(route):
            path = route.request.url.split("?", 1)[0]
            if "/api/v1/" in path or path.endswith("/api/tasks") or path.endswith("/api/meta") or route.request.method != "GET":
                route.continue_()
            else:
                route.fulfill(json={"ok": True, "items": [], "groups": [], "configured": False})
        self.page.route("**/api/**", isolate_auxiliary)
        self.page.on("dialog", lambda dialog: dialog.accept())
        self.posts = []
        self.page.on("request", lambda request: self.posts.append(request.url) if request.method == "POST" else None)
        self.page.add_init_script("localStorage.setItem('nh_setup_wizard_seen','1');localStorage.setItem('nh_star_prompt','1');")
        self.page.goto("http://127.0.0.1:%d/?token=desktop-test" % self.server.server.server_address[1])
        try:
            self.page.wait_for_function("typeof window.NhSchedule==='object' && document.querySelector('#task-%d')" % self.task_id)
        except Exception:
            print(self.page.evaluate("({sync:typeof window.NhSchedule,body:document.body.innerText.slice(-2200)})"), flush=True)
            raise

    def editor(self):
        card = self.page.locator("#task-%d" % self.task_id).first
        with self.page.expect_response(lambda response: "/api/v1/tasks/lookup?" in response.url) as response:
            card.locator('.task-edit-button').click()
        self.assertTrue(response.value.json()["ok"])
        self.page.wait_for_function("!document.querySelector('#rail-new-summary').disabled")
        return self.page.locator('#task-editor-dialog')

    def test_desktop_editor_and_legacy_controls_use_v1(self):
        card = self.editor()
        card.locator('#rail-new-summary').fill("电脑修改并同步")
        card.locator('#task-editor-notes').fill("桌面人工备注")
        card.get_by_label("仅设置日期", exact=True).check()
        card.locator('#rail-new-deadline').fill("2026-11-06")
        card.get_by_role("button", name="保存修改", exact=True).click()
        self.page.wait_for_function("document.querySelector('#task-%d .t').textContent === '电脑修改并同步'" % self.task_id)
        saved = self.store.sync_task(self.task_id)
        self.assertEqual(saved["notes"], "桌面人工备注")
        self.assertEqual(saved["deadline"], {"kind": "date", "value": "2026-11-06"})
        result = self.page.evaluate("""async taskId => {
            await api('/api/tasks/pin',{method:'POST',body:JSON.stringify({task_id:taskId,pinned:true})});
            await api('/api/tasks/'+taskId,{method:'POST',body:JSON.stringify({action:'done'})});
            return api('/api/tasks/'+taskId,{method:'POST',body:JSON.stringify({action:'reopen'})});
        }""", self.task_id)
        self.assertTrue(result["ok"])
        self.assertEqual(self.store.sync_task(self.task_id)["status"], "open")
        self.assertFalse(self.store.sync_task(self.task_id)["pinned"])
        self.page.evaluate("async id => {await api('/api/tasks/'+id,{method:'POST',body:JSON.stringify({action:'delete'})});await syncTasks()}", self.task_id)
        self.assertTrue(self.store.sync_task(self.task_id)["deleted"])
        self.assertFalse(any("/api/tasks/" in path for path in self.posts))
        self.assertTrue(any("/api/v1/mutations" in path for path in self.posts))

    def test_open_editor_does_not_overwrite_phone_change_and_pair_card_works(self):
        card = self.editor()
        card.locator('#rail-new-summary').fill("浏览器还没保存的标题")
        task = self.store.sync_task(self.task_id)
        result = self.store.sync_mutate({**self.store.sync_info(), "operation_id": str(uuid.uuid4()), "uuid": task["uuid"],
            "type": "update", "base_version": task["version"], "base": {"summary": task["summary"]}, "changes": {"summary": "手机已经保存的标题"}}, "phone")
        self.assertTrue(result["ok"])
        card.get_by_role("button", name="保存修改", exact=True).click()
        self.page.wait_for_function("document.querySelector('#task-editor-status').textContent.includes('手机或其他页面已经修改了这条日程，请刷新后核对再编辑')")
        self.assertEqual(self.store.sync_task(self.task_id)["summary"], "手机已经保存的标题")
        self.assertEqual(card.locator('#rail-new-summary').input_value(), "浏览器还没保存的标题")
        self.page.click('#rail-new-cancel')
        self.page.click('#task-editor-abandon')
        self.page.evaluate("document.body.appendChild(NhSchedule.mobileCard())")
        pairing = self.page.locator("#mobile-sync-settings")
        pairing.get_by_role("button", name="生成一次性配对二维码", exact=True).click()
        pairing.get_by_alt_text("手机日程同步配对二维码").wait_for(state="visible")
        self.assertTrue(pairing.locator("img").get_attribute("src").startswith("data:image/png;base64,"))
        self.assertEqual(pairing.get_by_label("手机同步入口").input_value(), "https://sync.example.com")


if __name__ == "__main__":
    unittest.main()
