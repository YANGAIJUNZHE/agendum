"""桌面工作区：分类导航、折叠排序、跨页入口和日程操作的浏览器契约。"""
from __future__ import annotations

import datetime
import os
import unittest
import uuid

from tests import open_authed_page
from tests import test_ui_browser as browser_fixture

try:  # pragma: no cover - 运行环境缺 playwright 时整类跳过
    from playwright.sync_api import sync_playwright
    HAVE_PLAYWRIGHT = True
except ImportError:
    HAVE_PLAYWRIGHT = False

REQUIRE_BROWSER = os.environ.get("NH_REQUIRE_BROWSER") == "1"
if REQUIRE_BROWSER and not HAVE_PLAYWRIGHT:  # pragma: no cover
    raise RuntimeError(
        "NH_REQUIRE_BROWSER=1 但没装 playwright：先 pip install -r requirements-dev.txt。"
        "这道检查不允许被 skip 掉。")

TOKEN = browser_fixture.TOKEN


@unittest.skipUnless(HAVE_PLAYWRIGHT, "playwright 未安装（pip install -r requirements-dev.txt）")
class DesktopWorkspaceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        browser_fixture.QrViewportBrowserTest.setUpClass()
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(channel="msedge", headless=True)

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()
        browser_fixture.QrViewportBrowserTest.tearDownClass()

    def setUp(self):
        self.fixture = browser_fixture.QrViewportBrowserTest()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.setUp()
        self.context = self.browser.new_context(viewport={"width": 1440, "height": 1000}, reduced_motion="reduce")
        self.addCleanup(self.context.close)
        self.page = self.context.new_page()
        self.errors = []
        self.page.on("pageerror", lambda error: self.errors.append(str(error)))
        self.page.route("**/api/napcat/status", self.fixture._napcat_status_stub)
        self.page.route("**/api/napcat/qrcode**", self.fixture._napcat_qrcode_stub)

    def open(self):
        open_authed_page(self.context, self.page, self.fixture.base, TOKEN)
        self.page.wait_for_selector(".rail-panel-toggle")

    def settings(self, category=None, page=None):
        self.page.click("#tab-settings-button")
        self.page.wait_for_selector(".settings-shell")
        if category:
            self.page.get_by_role("button", name=category, exact=True).click()
        if page:
            self.page.get_by_role("button", name=page, exact=True).click()

    def tearDown(self):
        self.assertEqual(self.errors, [], "页面不应抛出 JavaScript 异常")

    def test_settings_navigation_retains_draft_without_fetching_or_saving(self):
        self.open()
        self.settings("通知与提取", "AI 摘要")
        self.page.wait_for_function("document.querySelector('#llm-provider').options.length > 0")
        calls = []
        self.page.on("request", lambda request: calls.append((request.method, request.url)))
        self.page.locator("#llm-key").fill("draft-secret-never-store")
        self.page.get_by_role("button", name="消息来源", exact=True).click()
        self.page.get_by_role("button", name="微信", exact=True).click()
        self.page.locator("#wx-dir-input").fill("D:\\临时微信目录")
        self.page.get_by_role("button", name="通知与提取", exact=True).click()
        self.assertTrue(self.page.locator('#settings-page-llm').is_visible())
        self.assertEqual(self.page.locator("#llm-key").input_value(), "draft-secret-never-store")
        self.page.get_by_role("button", name="消息来源", exact=True).click()
        self.assertTrue(self.page.locator('#settings-page-wechat').is_visible())
        self.assertEqual(self.page.locator("#wx-dir-input").input_value(), "D:\\临时微信目录")
        self.assertEqual(self.page.locator(".settings-page:visible").count(), 1)
        self.assertTrue(self.page.locator(".summary").is_hidden())
        self.assertTrue(self.page.locator(".side-rail").is_hidden())
        self.assertFalse(any(method == "POST" or url.endswith("/api/meta") for method, url in calls), calls)
        self.assertNotIn("draft-secret", self.page.evaluate("JSON.stringify(localStorage)"))

    def test_sidebar_keyboard_reorder_collapse_and_reload(self):
        self.open()
        self.page.locator("#pinned-panel .rail-panel-toggle").click()
        self.assertTrue(self.page.locator("#pinned-panel-body").is_hidden())
        self.page.locator(".rail-customize").click()
        self.page.locator('#rail-actions [data-move="up"]').focus()
        self.page.keyboard.press("Enter")
        self.assertEqual(self.page.locator(".side-rail > section").first.get_attribute("id"), "rail-actions")
        self.page.reload()
        self.page.wait_for_selector(".rail-panel-toggle")
        self.assertEqual(self.page.locator(".side-rail > section").first.get_attribute("id"), "rail-actions")
        self.assertTrue(self.page.locator("#pinned-panel-body").is_hidden())
        self.page.locator(".rail-customize").click()
        self.page.get_by_role("button", name="恢复默认", exact=True).click()
        self.assertEqual(self.page.locator(".side-rail > section").first.get_attribute("id"), "pinned-panel")
        self.assertTrue(self.page.locator("#pinned-panel-body").is_visible())
        self.assertTrue(self.page.locator("#rail-summary-body").is_hidden())

    def test_main_navigation_keyboard_tracks_orientation(self):
        self.open()
        self.assertEqual(self.page.locator('.tabs').get_attribute('aria-orientation'), 'vertical')
        self.page.locator('#tab-tasks-button').focus()
        self.page.keyboard.press('ArrowDown')
        self.assertEqual(self.page.locator('.tabs [aria-selected=true]').get_attribute('data-tab'), 'notices')
        self.page.keyboard.press('ArrowUp')
        self.assertEqual(self.page.locator('.tabs [aria-selected=true]').get_attribute('data-tab'), 'tasks')
        self.page.set_viewport_size({'width': 390, 'height': 844})
        self.page.wait_for_function("document.querySelector('.tabs').getAttribute('aria-orientation') === 'horizontal'")
        self.page.locator('#tab-tasks-button').focus()
        self.page.keyboard.press('ArrowRight')
        self.assertEqual(self.page.locator('.tabs [aria-selected=true]').get_attribute('data-tab'), 'notices')

    def test_home_list_and_calendar_share_filtered_tasks(self):
        store = self.fixture.server.store
        today = datetime.date.today().isoformat()
        store.upsert_task(task_key='late', summary='今天晚些时候', deadline=today + ' 21:00')
        store.upsert_task(task_key='early', summary='今天先完成', deadline=today + ' 09:00')
        store.upsert_task(task_key='undated', summary='没有日期的事项')
        store.upsert_task(task_key='done', summary='已完成的事项', deadline=today + ' 12:00', status='done')
        self.open()
        self.page.wait_for_selector('#task-list-panel .task')
        self.assertTrue(self.page.locator('#task-list-panel').is_visible())
        self.assertTrue(self.page.locator('#calendar-panel').is_hidden())
        self.assertEqual(self.page.locator('#task-view-list').get_attribute('aria-pressed'), 'true')
        self.assertEqual(self.page.locator('#task-date-filter').input_value(), 'all')
        self.assertEqual(self.page.locator('#task-state-filter').input_value(), 'pending')
        self.assertEqual(self.page.locator('#task-sort').input_value(), 'deadline')
        text = self.page.locator('#task-list-panel').inner_text()
        self.assertLess(text.index('今天先完成'), text.index('今天晚些时候'))
        self.assertLess(text.index('今天晚些时候'), text.index('没有日期的事项'))
        self.assertNotIn('已完成的事项', text)
        self.assertEqual(self.page.locator('#workspace-overview, #upcoming-carousel, #headline, #subline').count(), 0)
        self.page.select_option('#task-date-filter', 'today')
        self.assertNotIn('没有日期的事项', self.page.locator('#task-list-panel').inner_text())
        self.page.click('#task-view-calendar')
        self.assertTrue(self.page.locator('#task-list-panel').is_hidden())
        self.assertTrue(self.page.locator('#calendar-panel').is_visible())
        self.assertEqual(self.page.locator('#task-view-calendar').get_attribute('aria-pressed'), 'true')
        calendar = self.page.locator('#calendar').inner_text()
        self.assertIn('今天先完成', calendar)
        self.assertIn('今天晚些时候', calendar)
        self.assertNotIn('已完成的事项', calendar)
        self.page.select_option('#task-state-filter', 'done')
        self.assertIn('已完成的事项', self.page.locator('#calendar').inner_text())
        self.assertNotIn('今天先完成', self.page.locator('#calendar').inner_text())
        self.page.click('#task-view-list')
        self.assertIn('已完成的事项', self.page.locator('#task-list-panel').inner_text())
        self.assertNotIn('今天先完成', self.page.locator('#task-list-panel').inner_text())
        self.page.select_option('#task-state-filter', 'pending')
        self.page.select_option('#task-date-filter', 'undated')
        self.assertIn('没有日期的事项', self.page.locator('#task-list-panel').inner_text())
        self.assertNotIn('今天先完成', self.page.locator('#task-list-panel').inner_text())
        self.page.select_option('#task-date-filter', 'all')
        self.page.fill('#task-search', '今天先')
        self.assertIn('今天先完成', self.page.locator('#task-list-panel').inner_text())
        self.assertNotIn('今天晚些时候', self.page.locator('#task-list-panel').inner_text())
        self.page.click('#task-view-calendar')
        self.assertIn('今天先完成', self.page.locator('#calendar').inner_text())
        self.assertNotIn('今天晚些时候', self.page.locator('#calendar').inner_text())
        original_month = self.page.locator('#calendar-title').inner_text()
        self.page.click('#cal-prev')
        self.assertNotEqual(self.page.locator('#calendar-title').inner_text(), original_month)
        self.page.click('#cal-today')
        self.assertEqual(self.page.locator('#calendar-title').inner_text(), original_month)
        self.page.click('#task-reset-filters')
        self.assertEqual(self.page.locator('#task-search').input_value(), '')
        self.assertEqual(self.page.locator('#task-date-filter').input_value(), 'all')
        self.assertEqual(self.page.locator('#task-state-filter').input_value(), 'pending')
        self.assertIn('今天先完成', self.page.locator('#calendar').inner_text())
        self.assertIn('今天晚些时候', self.page.locator('#calendar').inner_text())
        self.assertTrue(self.page.locator('#tasks-clear-done').is_visible())

    def test_calendar_and_pinned_links_reveal_task_in_list(self):
        store = self.fixture.server.store
        task_id = store.upsert_task(task_key='link-target', summary='定位这条日程',
                                    deadline=datetime.date.today().isoformat() + ' 18:00')
        store.set_task_pinned(task_id, True)
        self.open()
        self.page.wait_for_selector('#task-list-panel .task')
        self.page.click('#task-view-calendar')
        self.page.locator('#calendar .calendar-day[data-has-events=true]').click()
        self.page.locator('#calendar-detail .calendar-task-link', has_text='定位这条日程').click()
        target = self.page.locator('#task-' + str(task_id))
        self.assertTrue(target.is_visible())
        self.assertTrue(target.evaluate('el => document.activeElement === el'))
        self.page.select_option('#task-state-filter', 'done')
        self.assertEqual(target.count(), 0)
        self.page.locator("#pinned-panel a[href^='#task-']", has_text='定位这条日程').click()
        self.assertTrue(target.is_visible())
        self.assertTrue(target.evaluate('el => document.activeElement === el'))
        self.assertEqual(self.page.locator('#task-state-filter').input_value(), 'all')

    def test_sidebar_drag_reorders_and_bad_preferences_recover(self):
        self.open()
        self.page.locator(".rail-customize").click()
        self.page.locator("#rail-summary .rail-drag").drag_to(self.page.locator("#pinned-panel .panel-heading"))
        self.assertEqual(self.page.locator(".side-rail > section").first.get_attribute("id"), "rail-summary")
        self.page.reload()
        self.page.wait_for_selector(".rail-panel-toggle")
        self.assertEqual(self.page.locator(".side-rail > section").first.get_attribute("id"), "rail-summary")
        self.page.evaluate("localStorage.setItem('nh_workspace_v1', '{broken')")
        self.page.reload()
        self.page.wait_for_selector(".rail-panel-toggle")
        self.assertEqual(self.page.locator(".side-rail > section").count(), 7)
        self.assertEqual(self.page.locator(".side-rail > section").first.get_attribute("id"), "pinned-panel")

    def test_inbox_latest_filter_wins_and_promoted_task_is_reachable(self):
        task_id = self.fixture.server.store.upsert_task(task_key='inbox-target', summary='由通知转成的事项')
        self.open()
        self.page.click('#tab-inbox-button')
        self.page.wait_for_function("!document.querySelector('#inbox-message').textContent.includes('正在读取')")
        self.assertFalse(self.page.locator('#history-tools').evaluate('el => el.open'))
        self.page.evaluate("""() => {
            window.savedApi = window.api; window.inboxRequests = [];
            window.api = function(path, options) {
                if (path.startsWith('/api/inbox?')) return new Promise(resolve => window.inboxRequests.push({path, resolve}));
                if (path === '/api/inbox/promote') return Promise.resolve({ok:true, task_id: %d});
                return window.savedApi(path, options);
            };
        }""" % task_id)
        self.page.fill('#inbox-search', '旧词')
        self.page.click('#inbox-search-button')
        self.page.fill('#inbox-search', '新词')
        self.page.click('#inbox-search-button')
        self.page.wait_for_function('window.inboxRequests.length === 2')
        self.page.evaluate("""() => {
            const item = content => ({msg_id:content, content, verdict:'notice', method:'llm', reason:'测试'});
            const payload = content => ({ok:true, items:[item(content)], total:1,
                counts:{notice:1,suspect:0,noise:0,promoted:0}});
            window.inboxRequests[1].resolve(payload('新结果'));
            setTimeout(() => window.inboxRequests[0].resolve(payload('旧结果')), 30);
        }""")
        self.page.wait_for_function("document.querySelector('#inbox-items').textContent.includes('新结果')")
        self.page.wait_for_timeout(100)
        self.assertEqual(self.page.locator('#inbox-items .inbox-item').count(), 1)
        self.assertNotIn('旧结果', self.page.locator('#inbox-items').inner_text())
        self.assertIn('新结果', self.page.locator('#inbox-items .inbox-preview').inner_text())
        self.assertIn('显示 1 / 1 条', self.page.locator('#inbox-message').inner_text())
        self.page.click('#inbox-reset')
        self.assertEqual(self.page.locator('#inbox-search').input_value(), '')
        self.page.wait_for_function('window.inboxRequests.length === 3')
        self.assertNotIn('q=', self.page.evaluate('window.inboxRequests[2].path'))
        self.page.evaluate("""() => window.inboxRequests[2].resolve({ok:true,
            items:[{msg_id:'新结果', content:'新结果', verdict:'notice'}],
            total:1, counts:{notice:1,suspect:0,noise:0,promoted:0}})""")
        self.page.get_by_role('button', name='将这条通知转成待办').click()
        self.page.wait_for_function('window.inboxRequests.length === 4')
        self.page.evaluate("""taskId => window.inboxRequests[3].resolve({ok:true,
            items:[{msg_id:'新结果', content:'新结果', verdict:'notice', task_id:taskId}],
            total:1, counts:{notice:1,suspect:0,noise:0,promoted:1}})""", task_id)
        self.page.get_by_role('button', name='查看对应待办').click()
        self.assertTrue(self.page.locator('#task-' + str(task_id)).is_visible())

    def test_qq_portal_and_deep_links_keep_live_controls(self):
        self.open()
        self.page.evaluate("window.originalQQ = document.getElementById('connect')")
        self.settings("消息来源", "QQ 与订阅群")
        self.assertTrue(self.page.locator("#settings-page-qq #connect").is_visible())
        self.assertEqual(self.page.locator("#connect").count(), 1)
        self.page.click("#tab-tasks-button")
        self.assertTrue(self.page.evaluate("document.querySelector('.side-rail > #connect') === originalQQ"))
        self.settings()
        self.page.evaluate("scrollTargetIntoView(document.getElementById('push-save'))")
        self.assertTrue(self.page.locator("#settings-page-push").is_visible())
        self.page.click("#tab-tasks-button")
        self.page.evaluate("railShowSubscription()")
        self.page.wait_for_selector("#settings-page-calendar", state="visible")
        self.assertTrue(self.page.locator("#sync-url").is_visible())

    def test_search_finds_nested_setting_and_preserves_navigation(self):
        self.open()
        self.settings()
        self.page.locator("#settings-search").fill("端口")
        self.page.get_by_role("button", name="系统与帮助 / 运行信息", exact=True).click()
        self.assertTrue(self.page.locator("#settings-page-runtime").is_visible())
        self.assertEqual(self.page.locator("#settings-search").input_value(), "")
        self.page.locator("#settings-search").fill("找不到的设置zzzz")
        self.assertIn("没有匹配项", self.page.locator(".settings-search-results").inner_text())
        self.assertTrue(self.page.locator("#settings-page-runtime").is_visible())

    def test_create_task_from_primary_toolbar(self):
        self.open()
        self.page.locator(".workspace-toolbar").get_by_role("button", name="新建待办", exact=True).click()
        self.assertTrue(self.page.locator('#task-editor-dialog').is_visible())
        self.assertTrue(self.page.locator("#rail-new-form").is_visible())
        self.assertTrue(self.page.locator('#rail-new-summary').evaluate('el => document.activeElement === el'))
        self.page.locator("#rail-new-summary").fill("浏览器验收：提交实验报告")
        self.page.locator('#task-editor-notes').fill('需要附实验数据')
        self.page.keyboard.press('Escape')
        self.assertTrue(self.page.locator('#task-editor-discard').is_visible())
        self.page.click('#task-editor-continue')
        self.assertEqual(self.page.locator('#rail-new-summary').input_value(), '浏览器验收：提交实验报告')
        self.page.click("#rail-new-save")
        self.page.wait_for_selector('#task-editor-dialog', state='hidden')
        self.page.wait_for_selector("#tab-tasks .task")
        self.assertIn("浏览器验收：提交实验报告", self.page.locator("#tab-tasks").inner_text())
        created = next(t for t in self.fixture.server.store.sync_page()['items'] if t['summary'] == '浏览器验收：提交实验报告')
        self.assertEqual(created['notes'], '需要附实验数据')
        self.page.reload()
        self.page.wait_for_selector("#tab-tasks .task")
        self.assertIn("浏览器验收：提交实验报告", self.page.locator("#tab-tasks").inner_text())

    def test_manual_and_candidate_deletion_requires_confirmation(self):
        store = self.fixture.server.store
        manual = store.upsert_task(task_key='manual:' + str(uuid.uuid4()), summary='手动事项')
        candidate = store.upsert_task(task_key='candidate-delete', summary='候选事项', status='candidate')
        corrections_before = store.correction_stats()
        self.open()
        manual_card = self.page.locator('#task-' + str(manual))
        self.assertTrue(manual_card.get_by_text('分类与时间', exact=True).is_visible())
        self.assertEqual(manual_card.get_by_text('纠错', exact=True).count(), 0)
        for task_id in (manual, candidate):
            button = self.page.locator('#task-' + str(task_id)).get_by_role('button', name='删除这条待办', exact=True)
            button.click()
            self.assertFalse(store.sync_task(task_id)['deleted'])
            button.click()
            self.page.wait_for_function("id => !document.getElementById('task-' + id)", arg=task_id)
            self.assertTrue(store.sync_task(task_id)['deleted'])
        self.assertEqual(store.correction_stats(), corrections_before)

    def test_edit_dialog_keeps_draft_when_tasks_refresh(self):
        task_id = self.fixture.server.store.upsert_task(task_key='edit-draft', summary='修改前的事项')
        self.open()
        with self.page.expect_response(lambda response: '/api/v1/tasks/lookup?' in response.url) as lookup:
            self.page.locator('#task-' + str(task_id) + ' .task-edit-button').click()
        self.assertTrue(lookup.value.json()['ok'])
        self.page.wait_for_function("!document.querySelector('#rail-new-save').disabled && !document.querySelector('#task-editor-status').textContent")
        self.page.locator('#rail-new-summary').fill('修改后的事项')
        self.page.locator('#task-editor-notes').fill('尚未保存的备注')
        self.page.evaluate('syncTasks()')
        self.page.wait_for_timeout(300)
        self.assertTrue(self.page.locator('#task-editor-dialog').is_visible())
        self.assertEqual(self.page.locator('#rail-new-summary').input_value(), '修改后的事项')
        self.assertEqual(self.page.locator('#task-editor-notes').input_value(), '尚未保存的备注')
        self.page.click('#rail-new-save')
        self.page.wait_for_selector('#task-editor-dialog', state='hidden')
        self.page.wait_for_function("document.querySelector('#task-list-panel').textContent.includes('修改后的事项')")
        self.assertEqual(self.page.evaluate('document.activeElement.id'), 'task-edit-' + str(task_id))
        saved = self.fixture.server.store.sync_task(task_id)
        self.assertEqual(saved['summary'], '修改后的事项')
        self.assertEqual(saved['notes'], '尚未保存的备注')
        with self.page.expect_response(lambda response: '/api/v1/tasks/lookup?' in response.url) as lookup:
            self.page.locator('#task-' + str(task_id) + ' .task-edit-button').click()
        self.assertTrue(lookup.value.json()['ok'])
        self.page.wait_for_function("!document.querySelector('#rail-new-save').disabled && !document.querySelector('#task-editor-status').textContent")
        self.page.locator('#rail-new-summary').fill('网页上的新修改')
        store = self.fixture.server.store
        current, info = store.sync_task(task_id), store.sync_info()
        result = store.sync_mutate({'operation_id': str(uuid.uuid4()), 'uuid': current['uuid'],
            'type': 'update', 'server_id': info['server_id'], 'epoch': info['epoch'],
            'base_version': current['version'], 'base': {'summary': current['summary']},
            'changes': {'summary': '其他设备的修改'}}, 'test')
        self.assertTrue(result['ok'], result)
        self.page.click('#rail-new-save')
        self.page.wait_for_function("document.querySelector('#task-editor-status').textContent.includes('修改')")
        self.assertTrue(self.page.locator('#task-editor-dialog').is_visible())
        self.assertEqual(self.page.locator('#rail-new-summary').input_value(), '网页上的新修改')
        self.assertEqual(store.sync_task(task_id)['summary'], '其他设备的修改')

    def test_clear_finished_is_confirmed_once_and_reaches_mobile_change_stream(self):
        store = self.fixture.server.store
        done = store.upsert_task(task_key="finished", summary="可清理的事项", status="done")
        ongoing = store.upsert_task(task_key="ongoing", summary="仍需完成的事项")
        upper = store.sync_page()["upper"]
        self.open()
        self.page.click("#tasks-clear-done")
        self.assertFalse(store.sync_task(done)["deleted"])
        with self.page.expect_response("**/api/tasks/clear-finished") as response:
            self.page.click("#tasks-clear-done")
        self.assertEqual(response.value.json()["removed"], 1)
        self.assertTrue(store.sync_task(done)["deleted"])
        self.assertFalse(store.sync_task(ongoing)["deleted"])
        changes = store.sync_page(after=upper)["items"]
        self.assertEqual(len(changes), 1)
        self.assertTrue(changes[0]["deleted"])
        self.assertEqual(changes[0]["uuid"], store.sync_task(done)["uuid"])

    def test_settings_are_reachable_at_desktop_tablet_mobile_and_dark(self):
        self.open()
        self.settings()
        for width, theme in [(1440, "light"), (1024, "light"), (390, "light"), (1440, "dark")]:
            self.page.set_viewport_size({"width": width, "height": 900})
            self.page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
            for category, name in [("常用设置", "外观与布局"), ("通知与提取", "消息推送"), ("日程与数据", "手机日程同步"), ("系统与帮助", "版本更新")]:
                with self.subTest(width=width, theme=theme, page=name):
                    self.page.get_by_role("button", name=category, exact=True).click()
                    self.page.get_by_role("button", name=name, exact=True).click()
                    self.assertEqual(self.page.locator(".settings-page:visible").count(), 1)
                    geometry = self.page.evaluate("""() => {
                        const panel = document.querySelector('.settings-page:not([hidden])');
                        const r = panel.getBoundingClientRect();
                        return {left:r.left,right:r.right,width:innerWidth,scroll:document.documentElement.scrollWidth};
                    }""")
                    self.assertGreaterEqual(geometry["left"], 0, geometry)
                    self.assertLessEqual(geometry["right"], width + 1, geometry)
                    self.assertLessEqual(geometry["scroll"], width + 1, geometry)


if __name__ == "__main__":
    unittest.main()
