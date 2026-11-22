"""Fluent / Win11 视觉 token、减少动效、骨架屏与增量刷新锁行为回归。"""
from __future__ import annotations

import os
import unittest

from tests import open_authed_page
from tests import test_ui_browser as browser_fixture

try:  # pragma: no cover
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


def _contrast_ratio(fg_rgb: tuple[int, int, int], bg_rgb: tuple[int, int, int]) -> float:
    def _lin(channel: int) -> float:
        s = channel / 255.0
        return s / 12.92 if s <= 0.04045 else ((s + 0.055) / 1.055) ** 2.4

    def _lum(rgb: tuple[int, int, int]) -> float:
        return 0.2126 * _lin(rgb[0]) + 0.7152 * _lin(rgb[1]) + 0.0722 * _lin(rgb[2])

    l1, l2 = _lum(fg_rgb), _lum(bg_rgb)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


@unittest.skipUnless(HAVE_PLAYWRIGHT, "playwright 未安装（pip install -r requirements-dev.txt）")
class FluentUiBehaviorTest(unittest.TestCase):
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

    def _new_page(self, *, reduced_motion: str = "no-preference"):
        context = self.browser.new_context(
            viewport={"width": 1440, "height": 960},
            reduced_motion=reduced_motion,
        )
        self.addCleanup(context.close)
        page = context.new_page()
        errors: list[str] = []
        page.on("pageerror", lambda err: errors.append(str(err)))
        self.addCleanup(lambda: self.assertEqual(errors, [], "页面不应抛出 JavaScript 异常"))
        page.route("**/api/napcat/status", self.fixture._napcat_status_stub)
        page.route("**/api/napcat/qrcode**", self.fixture._napcat_qrcode_stub)
        return context, page

    def test_polling_unchanged_data_preserves_dom_nodes_focus_and_scroll(self):
        store = self.fixture.server.store
        first_id = store.upsert_task(task_key="fluent-stay-1", summary="保持不变的事项一", status="open")
        second_id = store.upsert_task(task_key="fluent-stay-2", summary="仅修改第二条", status="open")
        for idx in range(12):
            store.upsert_task(task_key=f"fluent-pad-{idx}", summary=f"填充高度事项 {idx}", status="open")
        context, page = self._new_page(reduced_motion="reduce")
        open_authed_page(context, page, self.fixture.base, TOKEN)
        page.wait_for_selector(f"#task-{first_id}")

        # 聚焦第一张卡片里的编辑按钮并标记节点引用 + 滚动位置
        pre_scroll = page.evaluate(
            f"""() => {{
                const first = document.querySelector('#task-{first_id}');
                const second = document.querySelector('#task-{second_id}');
                const btn = document.querySelector('#task-edit-{first_id}');
                window.__refFirst = first;
                window.__refSecond = second;
                window.__refBtn = btn;
                btn.focus();
                window.scrollTo(0, 180);
                return Math.round(window.scrollY);
            }}"""
        )
        self.assertGreaterEqual(pre_scroll, 150, "前置条件：页面应具备足够高度可滚动")

        # 1) 完全相同的后端数据时轮询刷新：第一/第二张卡片 DOM 节点、焦点与滚动位置都必须原样保留
        page.evaluate("() => syncTasks()")
        page.wait_for_timeout(150)
        same_check = page.evaluate(
            f"""() => ({{
                firstSame: document.querySelector('#task-{first_id}') === window.__refFirst,
                secondSame: document.querySelector('#task-{second_id}') === window.__refSecond,
                focusKept: document.activeElement === window.__refBtn,
                scrollY: Math.round(window.scrollY),
            }})"""
        )
        self.assertTrue(same_check["firstSame"], "数据未变时不应重建第一张卡片 DOM")
        self.assertTrue(same_check["secondSame"], "数据未变时不应重建第二张卡片 DOM")
        self.assertTrue(same_check["focusKept"], "数据未变轮询不应丢失按钮焦点")
        self.assertGreaterEqual(same_check["scrollY"], 150, "数据未变轮询不应让滚动跳回顶部")

        # 2) 仅改第二条时增量更新：第一张未变卡片仍然复用原 DOM 节点且焦点不断，第二张仅更新自身内容
        store.upsert_task(task_key="fluent-stay-2", summary="第二条已改标题", status="open")
        page.evaluate("() => syncTasks()")
        page.wait_for_function(
            f"document.querySelector('#task-{second_id} .t').textContent.includes('第二条已改标题')"
        )
        partial_check = page.evaluate(
            f"""() => ({{
                firstReused: document.querySelector('#task-{first_id}') === window.__refFirst,
                focusKept: document.activeElement === window.__refBtn,
            }})"""
        )
        self.assertTrue(partial_check["firstReused"], "邻近卡片更新时未改动的卡片应复用原 DOM")
        self.assertTrue(partial_check["focusKept"], "邻近卡片更新时应保留原焦点按钮")

    def test_reduced_motion_and_paused_preference_disable_transitions_and_close_sync(self):
        store = self.fixture.server.store
        task_id = store.upsert_task(task_key="fluent-motion-1", summary="动效检查待办", status="open")

        # 1) 系统级 prefers-reduced-motion: reduce
        ctx_red, page_red = self._new_page(reduced_motion="reduce")
        open_authed_page(ctx_red, page_red, self.fixture.base, TOKEN)
        page_red.wait_for_selector(f"#task-{task_id}")
        durations = page_red.evaluate(
            """() => {
                const task = document.querySelector('li.task');
                const btn = document.querySelector('#task-new-button');
                const body = document.querySelector('#pinned-panel .rail-panel-body');
                return {
                    task: getComputedStyle(task).transitionDuration,
                    btn: getComputedStyle(btn).transitionDuration,
                    railBody: body ? getComputedStyle(body).transitionDuration : '0s',
                };
            }"""
        )
        self.assertEqual(durations["task"], "0s")
        self.assertEqual(durations["btn"], "0s")
        self.assertEqual(durations["railBody"], "0s")

        # 打开待办对话框：空白校验失败停留在弹窗并提示，取消/放弃修改在 reduced-motion 下立即同步关闭
        page_red.click("#task-new-button")
        self.assertTrue(page_red.locator("#task-editor-dialog").is_visible())
        page_red.locator("#rail-new-summary").fill("   ")
        page_red.click("#rail-new-save")
        self.assertTrue(page_red.locator("#task-editor-dialog").is_visible(), "必填校验失败应阻止关闭")
        self.assertIn("请填写待办内容", page_red.locator("#task-editor-status").inner_text())
        page_red.click("#rail-new-cancel")
        page_red.click("#task-editor-abandon")
        closed_sync = page_red.evaluate("() => !document.querySelector('#task-editor-dialog').open")
        self.assertTrue(closed_sync, "reduced-motion 下关闭对话框应立即生效而非异步等待动画")

        # 2) 设置内手动开启「暂停动态效果」(html[data-motion=paused])
        ctx_norm, page_norm = self._new_page(reduced_motion="no-preference")
        open_authed_page(ctx_norm, page_norm, self.fixture.base, TOKEN)
        page_norm.wait_for_selector("#rail-actions .rail-panel-toggle")
        toggle_btn = page_norm.locator("#rail-actions .rail-panel-toggle")
        if toggle_btn.get_attribute("aria-expanded") == "false":
            toggle_btn.click()
        page_norm.wait_for_selector("#rail-motion", state="visible")
        page_norm.click("#rail-motion")
        self.assertEqual(page_norm.evaluate("() => document.documentElement.dataset.motion"), "paused")
        paused_dur = page_norm.evaluate(
            """() => ({
                btn: getComputedStyle(document.querySelector('#task-new-button')).transitionDuration,
                shimmer: (() => {
                    const el = window.nhCreateSkeletonCards(1);
                    document.body.appendChild(el);
                    const line = el.querySelector('.nh-skeleton-line');
                    const anim = getComputedStyle(line, '::after').animationName;
                    el.remove();
                    return anim;
                })(),
            })"""
        )
        self.assertEqual(paused_dur["btn"], "0s")
        self.assertEqual(paused_dur["shimmer"], "none")

    def test_inbox_skeleton_replaces_loading_and_clears_on_success_or_error(self):
        context, page = self._new_page(reduced_motion="no-preference")
        open_authed_page(context, page, self.fixture.base, TOKEN)

        # 拦截 /api/inbox 让首轮请求暂挂，检查骨架屏出现；随后返回 500 失败路径，检查骨架屏退场与错误提示
        pending = []

        def hold_inbox(route):
            pending.append(route)

        page.route("**/api/inbox?**", hold_inbox)
        page.click("#tab-inbox-button")
        page.wait_for_function("document.querySelectorAll('#inbox-items .nh-skeleton-card').length >= 2")
        self.assertIn("正在读取", page.locator("#inbox-message").text_content())

        # 失败路径：后端报错时必须清掉骨架屏并给出错误文案，不能永久卡死在骨架态
        self.assertTrue(pending)
        pending.pop(0).fulfill(status=500, content_type="application/json", body='{"ok":false,"error":"服务器暂不可用"}')
        page.wait_for_function(
            "document.querySelectorAll('#inbox-items .nh-skeleton-card').length === 0 "
            "&& document.querySelector('#inbox-message').textContent.includes('收件箱读取失败')"
        )
        self.assertFalse(page.locator("#inbox-message").evaluate("n => n.classList.contains('is-loading')"))

        # 恢复成功路径：空结果也要收掉骨架屏并显示空状态
        page.click("#inbox-refresh")
        page.wait_for_function("document.querySelectorAll('#inbox-items .nh-skeleton-card').length >= 2")
        self.assertTrue(pending)
        pending.pop(0).fulfill(
            status=200,
            content_type="application/json",
            body='{"ok":true,"items":[],"total":0,"counts":{"notice":0,"suspect":0,"noise":0,"promoted":0}}',
        )
        page.wait_for_function("document.querySelectorAll('#inbox-items .nh-skeleton-card').length === 0")
        self.assertIn("暂无判定结果", page.locator("#inbox-message").text_content())

    def test_fluent_tokens_light_and_dark_contrast_and_typography(self):
        store = self.fixture.server.store
        store.upsert_task(task_key="fluent-theme-1", summary="主题对照测试待办", status="open")
        context, page = self._new_page(reduced_motion="reduce")
        open_authed_page(context, page, self.fixture.base, TOKEN)
        page.wait_for_selector(".task")

        for theme in ("light", "dark"):
            page.evaluate("t => document.documentElement.dataset.theme = t", theme)
            metrics = page.evaluate(
                """() => {
                    const parseRgb = s => {
                        const m = String(s).match(/rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)/);
                        return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
                    };
                    const task = document.querySelector('.task');
                    const title = document.querySelector('.task .t');
                    const primaryBtn = document.querySelector('#task-new-button');
                    const rootStyle = getComputedStyle(document.documentElement);
                    return {
                        fontFamily: getComputedStyle(document.body).fontFamily,
                        radiusCard: getComputedStyle(task).borderRadius,
                        radiusBtn: getComputedStyle(primaryBtn).borderRadius,
                        pageBg: parseRgb(rootStyle.backgroundColor),
                        taskBg: parseRgb(getComputedStyle(task).backgroundColor),
                        taskFg: parseRgb(getComputedStyle(title).color),
                        primaryBg: parseRgb(getComputedStyle(primaryBtn).backgroundColor),
                        primaryFg: parseRgb(getComputedStyle(primaryBtn).color),
                    };
                }"""
            )
            self.assertIn("Segoe UI Variable", metrics["fontFamily"])
            self.assertEqual(metrics["radiusCard"], "8px")
            self.assertEqual(metrics["radiusBtn"], "8px")
            self.assertGreaterEqual(_contrast_ratio(tuple(metrics["taskFg"]), tuple(metrics["taskBg"])), 4.5)
            self.assertGreaterEqual(_contrast_ratio(tuple(metrics["primaryFg"]), tuple(metrics["primaryBg"])), 4.5)


if __name__ == "__main__":
    unittest.main()
