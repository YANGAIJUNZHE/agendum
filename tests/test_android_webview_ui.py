"""Android 本地页面交互；原生离线队列由 Android 仪器测试覆盖。"""
import datetime
import unittest
from pathlib import Path

from playwright.sync_api import sync_playwright
import tests  # noqa: F401


class AndroidWebViewUiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(channel='msedge', headless=True)

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.context = self.browser.new_context(viewport={'width': 390, 'height': 844}, reduced_motion='reduce')
        self.addCleanup(self.context.close)
        self.page = self.context.new_page()
        self.errors = []
        self.page.on('pageerror', lambda error: self.errors.append(str(error)))
        self.page.add_init_script("""window.recordedCommands=[]; window.AndroidBridge={
            command: s => recordedCommands.push(JSON.parse(s)), refresh:()=>{},
            exportPending:()=>{}, reconnect:()=>{}};""")
        self.page.goto((Path(__file__).resolve().parents[1] / 'android/app/src/main/assets/client.html').as_uri())
        today = datetime.date.today().isoformat()
        self.task = {'uuid': '00000000-0000-4000-8000-000000000001', 'version': 1,
            'summary': '交实验报告', 'notes': '核对实验数据', 'deadline': {'kind': 'date', 'value': today},
            'category': 'action', 'status': 'open', 'pinned': False, 'urgent_override': None,
            'snooze_until': {'kind': 'none', 'value': ''}}
        self.state = {'tasks': [self.task], 'conflicts': [], 'pending': 0,
            'server_id': 'isolated-test', 'connected': True, 'base': 'https://example.invalid', 'lastSuccess': 1}
        self.page.evaluate('state => NH_render(state)', self.state)

    def tearDown(self):
        self.assertEqual(self.errors, [])

    def test_sync_shortcut_tracks_native_completion_without_navigation(self):
        self.page.emulate_media(reduced_motion='no-preference')
        self.page.evaluate("() => { window.syncRequests=[]; AndroidBridge.requestSync=id => syncRequests.push(id); }")
        button = self.page.locator('#sync-shortcut')
        spinner = self.page.locator('#sync-spinner')
        button.click()
        first = self.page.evaluate('syncRequests[0]')
        self.assertTrue(first)
        self.assertTrue(self.page.locator('#page-tasks').is_visible())
        self.assertTrue(button.is_disabled())
        self.assertEqual(button.get_attribute('aria-busy'), 'true')
        self.assertTrue(spinner.is_visible())
        self.assertEqual(spinner.evaluate('el => getComputedStyle(el).animationName'), 'sync-spin')
        self.page.evaluate("document.querySelector('#sync-shortcut').click()")
        self.assertEqual(self.page.evaluate('syncRequests.length'), 1)
        self.page.evaluate('state => NH_render(state)', self.state)
        self.page.evaluate("NH_syncFinished('wrong-id', true, '')")
        self.assertTrue(button.is_disabled())
        self.assertTrue(spinner.is_visible())
        self.page.evaluate("id => NH_syncFinished(id, true, '')", first)
        self.assertTrue(button.is_enabled())
        self.assertTrue(spinner.is_hidden())
        button.click()
        second = self.page.evaluate('syncRequests[1]')
        self.assertNotEqual(first, second)
        self.page.evaluate("id => NH_syncFinished(id, false, 'network error')", second)
        self.assertTrue(button.is_enabled())
        self.assertTrue(spinner.is_hidden())
        self.assertTrue(self.page.locator('#page-tasks').is_visible())
        self.assertIn('本地编辑已保留', self.page.locator('#feedback-message').inner_text())
        button.click()
        self.assertEqual(self.page.evaluate('syncRequests.length'), 3)

    def test_navigation_search_calendar_and_return(self):
        self.assertTrue(self.page.locator('#page-tasks').is_visible())
        self.page.locator('#search').fill('找不到的任务')
        self.assertNotIn('交实验报告', self.page.locator('#list').inner_text())
        self.page.click('#reset-filters')
        self.assertIn('交实验报告', self.page.locator('#list').inner_text())
        self.page.locator('.bottom [data-page=calendar]').click()
        self.assertTrue(self.page.locator('#calendar').is_visible())
        current = self.page.locator('#month').inner_text()
        self.page.click('#prev')
        self.assertNotEqual(self.page.locator('#month').inner_text(), current)
        self.page.click('#today')
        self.assertEqual(self.page.locator('#month').inner_text(), current)
        self.page.click('#new')
        self.assertTrue(self.page.locator('#editor').is_visible())
        self.page.click('#editor-back')
        self.assertTrue(self.page.locator('#calendar').is_visible())
        self.page.locator('.bottom [data-page=account]').click()
        self.page.locator('#page-account [data-page=connection]').click()
        self.assertTrue(self.page.locator('#page-connection').is_visible())
        self.page.evaluate('NH_back()')
        self.assertTrue(self.page.locator('#page-account').is_visible())

    def test_editor_draft_survives_unrelated_native_callback_and_refresh(self):
        self.page.locator('#list .task-done').click()
        self.page.wait_for_function('recordedCommands.length === 1')
        prior = self.page.evaluate('recordedCommands[0].requestId')
        self.assertTrue(prior)
        self.page.click('#new')
        self.page.locator('#edit-summary').fill('新建的草稿')
        self.page.locator('#edit-notes').fill('草稿备注')
        self.page.evaluate('state => NH_render(state)', self.state)
        self.page.evaluate('id => NH_saved(id)', prior)
        self.assertTrue(self.page.locator('#editor').is_visible())
        self.assertEqual(self.page.locator('#edit-summary').input_value(), '新建的草稿')
        self.assertEqual(self.page.locator('#edit-notes').input_value(), '草稿备注')
        self.page.click('#save')
        self.page.wait_for_function('recordedCommands.length === 2')
        request = self.page.evaluate('recordedCommands[1]')
        self.assertEqual(request['type'], 'create')
        self.assertEqual(request['changes']['notes'], '草稿备注')
        self.page.evaluate("id => NH_error('模拟保存失败', id)", request['requestId'])
        self.assertTrue(self.page.locator('#editor').is_visible())
        self.assertIn('输入已保留', self.page.locator('#editor-error').inner_text())
        self.assertEqual(self.page.locator('#edit-summary').input_value(), '新建的草稿')

    def test_conflict_choice_is_sent_to_native_bridge(self):
        local = dict(self.task, summary='手机上的标题')
        current = dict(self.task, summary='电脑上的标题', version=2)
        state = dict(self.state, tasks=[local], pending=1, conflicts=[{'uuid': self.task['uuid'],
            'operation_id': 'conflict-id', 'type': 'update', 'local': local,
            'result': {'code': 'conflict', 'fields': ['summary'], 'current': current}}])
        self.page.evaluate('state => NH_render(state)', state)
        self.page.locator('.bottom [data-page=account]').click()
        self.page.locator('#page-account [data-page=sync]').click()
        self.page.get_by_label('电脑当前值：电脑上的标题', exact=True).check()
        self.page.get_by_role('button', name='按选择合并提交', exact=True).click()
        self.page.wait_for_function('recordedCommands.length === 1')
        request = self.page.evaluate('recordedCommands[0]')
        self.assertEqual(request['type'], 'resolve')
        self.assertFalse(request['choices']['summary'])


if __name__ == '__main__':
    unittest.main()
