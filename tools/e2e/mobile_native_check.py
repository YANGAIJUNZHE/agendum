"""指定本任务模拟器执行：真实 APK + Room + Keystore + WebView + Python HTTP 后端。

不由常规 CI 自动发现。需要已安装 Debug APK、Node 22+ 和明确指定的测试设备。
"""
import json
import os
import shlex
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
import tests  # noqa: E402,F401
from noticehub.config import Settings  # noqa: E402
from noticehub.store import Store  # noqa: E402
from noticehub.webapp import TaskWebServer  # noqa: E402

ADB = os.environ.get("NOTICEHUB_TEST_ADB", "adb")
SERIAL = os.environ.get("NOTICEHUB_PHONE_SERIAL", "")


def adb(*args):
    if not SERIAL:
        raise RuntimeError("请设置 NOTICEHUB_PHONE_SERIAL，明确指定测试设备")
    result = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20)
    if result.returncode:
        raise AssertionError("本任务模拟器的 ADB 操作失败")
    return result.stdout.strip()


class NativeMobileTest(unittest.TestCase):
    def until(self, function, timeout=40):
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            try:
                value = function()
                if value:
                    return value
            except Exception as error:
                last = str(error)[:350]
            time.sleep(0.3)
        self.fail("等待真实手机行为超时" + (": " + last if last else ""))

    def connect_debugger(self):
        def connect():
            pid = adb("shell", "pidof", "com.noticehub.app").split()[0]
            port = adb("forward", "tcp:0", "localabstract:webview_devtools_remote_" + pid)
            try:
                with urllib.request.urlopen("http://127.0.0.1:" + port + "/json/list", timeout=3) as response:
                    pages = json.load(response)
                targets = [page for page in pages if page.get("url", "").endswith("client.html")]
                if targets:
                    self.socket = targets[0]["webSocketDebuggerUrl"]
                    self.forward_port = port
                    self.addCleanup(lambda: adb("forward", "--remove", "tcp:" + port))
                    return True
                return False
            finally:
                if getattr(self, "forward_port", None) != port:
                    adb("forward", "--remove", "tcp:" + port)
        self.until(connect)

    def evaluate(self, expression):
        result = subprocess.run(["node", str(ROOT / "tools/e2e/devtools_eval.mjs"), self.socket, expression], capture_output=True, text=True, encoding="utf-8", timeout=12)
        if result.returncode:
            raise AssertionError("WebView 界面操作失败：" + result.stderr[:300])
        return json.loads(result.stdout)

    def test_real_app_offline_cold_start_and_backend_restart(self):
        self.assertEqual(adb("get-state"), "device")
        self.addCleanup(lambda: adb("shell", "am", "force-stop", "com.noticehub.app"))
        adb("shell", "am", "force-stop", "com.noticehub.app")
        self.assertIn("Success", adb("shell", "pm", "clear", "com.noticehub.app"))
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / "native.sqlite3"
        self.store = Store(path)
        task_id = self.store.upsert_task(task_key="native-source", summary="Native original")
        settings = Settings(web_host="127.0.0.1", web_port=0, web_token="native-test-admin", data_dir=Path(temporary.name))
        server = TaskWebServer(settings, self.store)
        self.assertTrue(server.start())
        self.addCleanup(lambda: server.stop())
        port = server.server.server_address[1]
        adb("reverse", "tcp:" + str(port), "tcp:" + str(port))
        self.addCleanup(lambda: adb("reverse", "--remove", "tcp:" + str(port)))
        ticket = self.store.sync_pair_start("真实 App 验收", "native", approved=True)
        link = "noticehub://connect?" + urllib.parse.urlencode({"base": "http://127.0.0.1:" + str(port), "pair_id": ticket["id"], "pair_secret": ticket["secret"]})
        adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", shlex.quote(link), "com.noticehub.app")
        self.connect_debugger()
        self.until(lambda: self.evaluate("document.getElementById('list').textContent.includes('Native original')"))
        self.evaluate("document.querySelector('#list .task-title').click();true")
        self.until(lambda: self.evaluate("!document.getElementById('editor').hidden"))
        server.stop()
        self.evaluate("document.getElementById('edit-summary').value='Native offline title';document.getElementById('edit-notes').value='Kept after force stop';document.getElementById('save').click();true")
        self.until(lambda: self.evaluate("document.getElementById('editor').hidden && document.getElementById('list').textContent.includes('Native offline title')"), timeout=8)
        self.assertIn("待同步", self.evaluate("document.getElementById('summary').textContent"))
        output = ROOT / ".tmp-qa/mobile-sync"
        output.mkdir(parents=True, exist_ok=True)
        adb("shell", "screencap", "-p", "/sdcard/noticehub-native.png")
        adb("pull", "/sdcard/noticehub-native.png", str(output / "native-offline.png"))
        adb("shell", "am", "force-stop", "com.noticehub.app")
        # 旧调试连接在进程结束后不再可用；清理由 addCleanup 统一完成。
        adb("shell", "am", "start", "-W", "-n", "com.noticehub.app/.MainActivity")
        self.connect_debugger()
        self.until(lambda: self.evaluate("document.getElementById('list').textContent.includes('Native offline title')"))
        self.assertIn("待同步", self.evaluate("document.getElementById('summary').textContent"))
        old_epoch = self.store.sync_epoch
        self.store = Store(path)
        self.assertNotEqual(old_epoch, self.store.sync_epoch)
        settings.web_port = port
        server = TaskWebServer(settings, self.store)
        self.assertTrue(server.start())
        self.evaluate("document.querySelector('[data-page=sync]').click();document.getElementById('sync-now').click();true")
        self.until(lambda: self.store.sync_task(task_id)["summary"] == "Native offline title", timeout=50)
        self.assertEqual(self.store.sync_task(task_id)["notes"], "Kept after force stop")
        self.until(lambda: self.evaluate("document.getElementById('pending-count').textContent==='0'"))
        self.store.upsert_task(task_key="native-source", summary="Generated old title")
        self.assertEqual(self.store.sync_task(task_id)["summary"], "Native offline title")
        # 编辑表单保持打开期间，拉取了另一端对同字段的修改，仍必须产生冲突。
        self.evaluate("document.querySelector('[data-page=tasks]').click();document.querySelector('#list .task-title').click();true")
        current = self.store.sync_task(task_id)
        result = self.store.sync_mutate({**self.store.sync_info(), "operation_id": str(uuid.uuid4()), "uuid": current["uuid"],
            "type": "update", "base_version": current["version"], "base": {"summary": current["summary"]}, "changes": {"summary": "Changed while phone editor open"}}, "desktop")
        self.assertTrue(result["ok"])
        self.evaluate("AndroidBridge.refresh();true")
        self.until(lambda: self.evaluate("document.getElementById('list').textContent.includes('Changed while phone editor open')"))
        self.evaluate("document.getElementById('edit-summary').value='Phone resolves conflict';document.getElementById('save').click();true")
        self.until(lambda: self.evaluate("document.getElementById('conflicts').textContent.includes('Changed while phone editor open')"))
        self.assertEqual(self.store.sync_task(task_id)["summary"], "Changed while phone editor open")
        self.evaluate("document.querySelector('[data-page=sync]').click();document.querySelector('#conflicts .primary').click();true")
        self.until(lambda: self.store.sync_task(task_id)["summary"] == "Phone resolves conflict")
        self.evaluate("document.querySelector('[data-page=tasks]').click();document.querySelector('#list .task-done').click();true")
        self.until(lambda: self.store.sync_task(task_id)["status"] == "done")
        self.assertEqual(len(self.store.list_tasks()), 1)
        adb("shell", "am", "force-stop", "com.noticehub.app")


if __name__ == "__main__":
    unittest.main()
