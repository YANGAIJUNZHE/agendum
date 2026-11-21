"""托盘「打开控制台」的地址必须带上访问令牌。

2026-11-15 现场：用户 .env 迁到 AGENDUM_* 前缀后，`_console_url` 只读
QQ_DIGEST_WEB_TOKEN，读不到就拼出 `http://127.0.0.1:18766/?token=`（空值），
浏览器打开是 401 页面。以下用例锁住「三种前缀都能读到」和「新名优先」。
"""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location(
    "packaging_launcher_console_url", Path(__file__).parents[1] / "packaging" / "launcher.py"
)
launcher = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(launcher)


class ConsoleUrlTokenTests(unittest.TestCase):
    def _url(self, env_text: str) -> str:
        with tempfile.TemporaryDirectory() as tmp:
            env_file = Path(tmp) / ".env"
            env_file.write_text(env_text, encoding="utf-8")
            return launcher._console_url(env_file, 18766)

    def test_new_prefix_token_is_used(self):
        url = self._url("AGENDUM_WEB_TOKEN=AwKTOU+abc/def=\n")
        self.assertIn("token=AwKTOU%2Babc%2Fdef%3D", url)

    def test_previous_prefix_token_is_used(self):
        url = self._url("NOTICEHUB_WEB_TOKEN=prev-token-123\n")
        self.assertIn("token=prev-token-123", url)

    def test_legacy_prefix_token_still_works(self):
        url = self._url("QQ_DIGEST_WEB_TOKEN=legacy-token-456\n")
        self.assertIn("token=legacy-token-456", url)

    def test_new_prefix_wins_over_legacy(self):
        url = self._url("QQ_DIGEST_WEB_TOKEN=old-one\nAGENDUM_WEB_TOKEN=new-one\n")
        self.assertIn("token=new-one", url)
        self.assertNotIn("old-one", url)

    def test_empty_value_falls_through_to_next_prefix(self):
        url = self._url("AGENDUM_WEB_TOKEN=\nQQ_DIGEST_WEB_TOKEN=fallback-token\n")
        self.assertIn("token=fallback-token", url)

    def test_port_is_kept(self):
        url = self._url("AGENDUM_WEB_TOKEN=t\n")
        self.assertTrue(url.startswith("http://127.0.0.1:18766/?token="), url)


if __name__ == "__main__":
    unittest.main()
