"""Tests for standalone Edge `--app=` main window host and launcher fallback."""

from __future__ import annotations

import importlib.util
import json
import os
import posixpath
import subprocess
import tempfile
import unittest
from pathlib import Path, PureWindowsPath
from types import SimpleNamespace
from unittest.mock import patch

from agendum import app_window, edge_app
from agendum.app_window import (
    AppWindowHost,
    AppWindowResult,
    app_user_model_id,
    app_window_profile_dir,
    compute_initial_geometry,
    is_error_page_title,
    select_app_icon_path,
)

_SPEC = importlib.util.spec_from_file_location(
    "packaging_launcher_app_window", Path(__file__).parents[1] / "packaging" / "launcher.py"
)
launcher = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(launcher)


class FakeProcess:
    def __init__(self, polls, pid: int = 5678):
        self._polls = list(polls)
        self._index = 0
        self.pid = pid
        self.returncode = None

    def poll(self):
        if self._index < len(self._polls):
            value = self._polls[self._index]
            self._index += 1
        else:
            value = self._polls[-1]
        if value is not None:
            self.returncode = value
        return value


class ProfileAndBrandingTests(unittest.TestCase):
    def test_main_profile_is_separate_from_floatball_and_napcat(self):
        with tempfile.TemporaryDirectory() as tmp:
            data_dir = Path(tmp) / "data"
            main_profile = app_window_profile_dir(data_dir)
            self.assertEqual(main_profile.name, "app-window-profile")
            self.assertEqual(main_profile.parent, data_dir.resolve())
            self.assertNotEqual(main_profile, (data_dir / "floatball-profile").resolve())
            self.assertNotEqual(main_profile, (data_dir / "napcat-profile").resolve())

    def test_app_user_model_id_is_scoped_per_install_directory(self):
        prod = app_user_model_id(Path(r"D:\Agendum"))
        beta = app_user_model_id(Path(r"D:\Agendum-beta"))
        self.assertNotEqual(prod, beta)
        self.assertEqual(prod, app_user_model_id(Path(r"d:\agendum\.")))
        self.assertTrue(prod.startswith("Agendum.App."))

    def test_select_app_icon_path_prefers_beta_icon_for_beta_channel(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            assets = root / "assets"
            assets.mkdir()
            stable_ico = assets / "icon.ico"
            beta_ico = assets / "icon-beta.ico"
            stable_ico.write_bytes(b"ico")
            beta_ico.write_bytes(b"beta")
            self.assertEqual(select_app_icon_path(root, "beta"), beta_ico)
            self.assertEqual(select_app_icon_path(root, "stable"), stable_ico)
            self.assertEqual(select_app_icon_path(root, None), stable_ico)

    def test_initial_geometry_centers_and_clamps_to_work_area(self):
        large = compute_initial_geometry((0, 0, 1920, 1032), scale=1.0)
        self.assertEqual((large["logical_w"], large["logical_h"]), (1200, 800))
        self.assertEqual((large["phys_x"], large["phys_y"]), (360, 116))

        small = compute_initial_geometry((0, 0, 1024, 600), scale=1.0)
        self.assertLessEqual(small["phys_w"], 1024)
        self.assertLessEqual(small["phys_h"], 600)
        self.assertGreaterEqual(small["phys_x"], 0)
        self.assertGreaterEqual(small["phys_y"], 0)

    def test_error_page_title_detection(self):
        self.assertFalse(is_error_page_title("Agendum"))
        self.assertFalse(is_error_page_title("Agendum · 月历"))
        self.assertFalse(is_error_page_title(""))
        self.assertTrue(is_error_page_title("127.0.0.1:8766"))
        self.assertTrue(is_error_page_title("无法访问此页面"))
        self.assertTrue(is_error_page_title("Hmm… can't reach this page"))


class ProcessMatchingAndSafetyTests(unittest.TestCase):
    def test_exact_profile_matching_rejects_prefix_siblings_and_regular_edge(self):
        target = Path(r"F:\Agendum\data\app-window-profile")
        self.assertTrue(
            edge_app.process_matches_commandline(
                r'"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" --app=http://127.0.0.1:8766/ --user-data-dir="F:\Agendum\data\app-window-profile"',
                target,
            )
        )
        # 同前缀不同目录、悬浮球、普通浏览器、无关进程一律不匹配
        self.assertFalse(
            edge_app.process_matches_commandline(
                r'msedge.exe --user-data-dir="F:\Agendum\data\app-window-profile-2"',
                target,
            )
        )
        self.assertFalse(
            edge_app.process_matches_commandline(
                r'msedge.exe --user-data-dir="F:\Agendum\data\floatball-profile"',
                target,
            )
        )
        self.assertFalse(
            edge_app.process_matches_commandline(
                r'"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" https://example.com',
                target,
            )
        )
        self.assertFalse(
            edge_app.process_matches_commandline(
                r'python.exe --user-data-dir="F:\Agendum\data\app-window-profile"',
                target,
            )
        )

    def test_processes_using_profile_handles_gbk_error_and_filters_by_profile_only(self):
        # 模拟 CIM 返回 GBK 中文错误时绝不抛出 UnicodeDecodeError，且返回 None 表示不可判定
        failed = SimpleNamespace(returncode=1, stdout=b"", stderr="无法从客户端中访问 CIM 资源".encode("gbk"))
        with patch.object(edge_app.os, "name", "nt"), patch.object(edge_app.subprocess, "run", return_value=failed):
            self.assertIsNone(edge_app.processes_using_profile(Path(r"F:\Agendum\data\app-window-profile")))

        target = Path(r"F:\Agendum\data\app-window-profile")
        payload = json.dumps(
            [
                {
                    "ProcessId": 101,
                    "ParentProcessId": 1,
                    "CommandLine": r'msedge.exe --app=http://127.0.0.1:8766/ --user-data-dir="F:\Agendum\data\app-window-profile"',
                    "StartedMs": 1_700_000_000_000,
                },
                {
                    "ProcessId": 202,
                    "ParentProcessId": 1,
                    "CommandLine": r'msedge.exe https://bing.com',
                    "StartedMs": 1_700_000_000_000,
                },
                {
                    "ProcessId": 303,
                    "ParentProcessId": 1,
                    "CommandLine": r'msedge.exe --app=http://127.0.0.1:8767/ --user-data-dir="F:\Agendum-beta\data\app-window-profile"',
                    "StartedMs": 1_700_000_000_000,
                },
            ]
        ).encode("utf-8")
        ok = SimpleNamespace(returncode=0, stdout=payload, stderr=b"")
        with patch.object(edge_app.os, "name", "nt"), patch.object(edge_app.subprocess, "run", return_value=ok):
            rows = edge_app.processes_using_profile(target)
        assert rows is not None
        self.assertEqual([row["pid"] for row in rows], [101])

    def test_kill_tree_never_kills_by_process_name(self):
        with patch.object(edge_app.subprocess, "run", return_value=SimpleNamespace(returncode=0)) as run_cmd:
            self.assertTrue(edge_app.kill_tree(4321))
        args = run_cmd.call_args.args[0]
        self.assertEqual(args, ["taskkill", "/PID", "4321", "/T", "/F"])
        self.assertNotIn("/IM", args)
        self.assertNotIn("msedge.exe", args)

    def test_posix_path_semantics_and_windows_path_not_implemented_are_handled(self):
        def raise_windows_path(*_args, **_kwargs):
            raise NotImplementedError("cannot instantiate 'WindowsPath' on your system")

        target = PureWindowsPath(r"F:\Agendum\data\app-window-profile")
        failed = SimpleNamespace(returncode=1, stdout=b"", stderr="无法从客户端中访问 CIM 资源".encode("gbk"))
        payload = json.dumps(
            [
                {
                    "ProcessId": 101,
                    "ParentProcessId": 1,
                    "CommandLine": r'msedge.exe --app=http://127.0.0.1:8766/ --user-data-dir="f:/agendum/data/app-window-profile/"',
                    "StartedMs": 1_700_000_000_000,
                },
                {
                    "ProcessId": 303,
                    "ParentProcessId": 1,
                    "CommandLine": r'msedge.exe --app=http://127.0.0.1:8767/ --user-data-dir="F:\Agendum-beta\data\app-window-profile"',
                    "StartedMs": 1_700_000_000_000,
                },
            ]
        ).encode("utf-8")
        ok = SimpleNamespace(returncode=0, stdout=payload, stderr=b"")

        with tempfile.TemporaryDirectory() as tmp:
            assets_dir = Path(tmp) / "assets"
            assets_dir.mkdir()
            ico = assets_dir / "icon.ico"
            ico.write_bytes(b"ico")
            expected_profile = Path(tmp) / "data" / "app-window-profile"
            with patch.object(edge_app.sys, "platform", "linux"), \
                    patch.object(edge_app.os, "name", "nt"), \
                    patch.object(edge_app, "Path", side_effect=raise_windows_path), \
                    patch.object(app_window, "Path", side_effect=raise_windows_path), \
                    patch.object(Path, "resolve", side_effect=raise_windows_path):
                self.assertEqual(
                    edge_app.normalize_profile_path(target),
                    r"f:\agendum\data\app-window-profile",
                )
                self.assertEqual(
                    edge_app.normalize_profile_path("f:/agendum/data/app-window-profile/"),
                    r"f:\agendum\data\app-window-profile",
                )
                self.assertTrue(
                    edge_app.process_matches_commandline(
                        r'msedge.exe --user-data-dir="f:/agendum/data/app-window-profile/"',
                        target,
                    )
                )
                with patch.object(edge_app.subprocess, "run", return_value=failed):
                    self.assertIsNone(edge_app.processes_using_profile(target))
                with patch.object(edge_app.subprocess, "run", return_value=ok):
                    rows = edge_app.processes_using_profile(target)
                self.assertEqual([row["pid"] for row in (rows or [])], [101])
                self.assertEqual(app_window_profile_dir(tmp).name, "app-window-profile")
                self.assertEqual(select_app_icon_path(tmp), ico)
                host = AppWindowHost(tmp, os.path.join(tmp, "data"))
                self.assertEqual(host.profile_dir, expected_profile)
                self.assertEqual(edge_app._resolved_existing_file(ico), os.path.abspath(str(ico)))

        with patch.object(edge_app.os, "path", posixpath):
            self.assertEqual(
                edge_app.normalize_windows_path("d:/Agendum/data/app-window-profile/"),
                r"d:\agendum\data\app-window-profile",
            )


class AppWindowHostLifecycleTests(unittest.TestCase):
    def _make_host(self, tmp: str, *, url_ok: bool = True, edge_ok: bool = True, popen=None, verify_delay: float = 0.01):
        root = Path(tmp)
        data_dir = root / "data"
        logs: list[str] = []

        def finder() -> str:
            if not edge_ok:
                raise RuntimeError("找不到 Microsoft Edge")
            return r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

        host = AppWindowHost(
            root,
            data_dir,
            verify_delay=verify_delay,
            window_wait=0.2,
            startup_grace=0.3,
            url_probe=lambda _url: url_ok,
            edge_finder=finder,
            popen=popen,
            logger=logs.append,
        )
        return host, logs

    def test_launch_success_verifies_survival_and_page_and_applies_icon(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = FakeProcess([None, None, None], pid=7777)
            spawned: list[list[str]] = []

            def fake_popen(args, **kwargs):
                spawned.append(args)
                return proc

            host, logs = self._make_host(tmp, url_ok=True, popen=fake_popen, verify_delay=0.05)
            with patch.object(app_window.os, "name", "nt"), \
                    patch.object(app_window, "ensure_dpi_aware"), \
                    patch.object(app_window, "processes_using_profile", return_value=[]), \
                    patch.object(app_window, "pids_using_profile", return_value=[]), \
                    patch.object(app_window, "find_windows_for_pids", return_value=[9001]), \
                    patch.object(app_window, "_is_window_valid", return_value=True), \
                    patch.object(app_window, "window_title", return_value="Agendum"), \
                    patch.object(app_window, "activate_window", return_value=True) as activate, \
                    patch.object(app_window, "work_area", return_value=(0, 0, 1920, 1032)), \
                    patch.object(app_window, "dpi_scale", return_value=1.0), \
                    patch.object(AppWindowHost, "_position_window") as position, \
                    patch.object(AppWindowHost, "_apply_branding") as branding:
                res = host.open_or_activate("http://127.0.0.1:8766/?token=abc")
            self.assertTrue(res.ok)
            self.assertEqual(res.action, "launched")
            self.assertEqual(res.hwnd, 9001)
            self.assertEqual(len(spawned), 1)
            cmd = spawned[0]
            self.assertIn("--app=http://127.0.0.1:8766/?token=abc", cmd)
            self.assertIn("--user-data-dir=" + str(host.profile_dir), cmd)
            self.assertTrue(any(arg.startswith("--window-size=") for arg in cmd))
            self.assertTrue(any(arg.startswith("--window-position=") for arg in cmd))
            self.assertFalse(any(arg.startswith("--app-id=") for arg in cmd))
            branding.assert_called_with(9001)
            position.assert_called_once()
            activate.assert_called_with(9001)
            self.assertTrue(any("已以独立窗口打开控制台" in line for line in logs))

    def test_edge_missing_fails_without_spawning_and_gives_actionable_message(self):
        with tempfile.TemporaryDirectory() as tmp:
            host, _ = self._make_host(tmp, edge_ok=False)
            with patch.object(app_window.os, "name", "nt"), \
                    patch.object(app_window, "ensure_dpi_aware"), \
                    patch.object(app_window, "processes_using_profile", return_value=[]):
                res = host.open_or_activate("http://127.0.0.1:8766/?token=abc")
            self.assertFalse(res.ok)
            self.assertEqual(res.reason, "edge_missing")
            self.assertIn("Microsoft Edge", res.message)
            self.assertIn("下一步：", res.message)

    def test_immediate_process_exit_fails_and_cleans_up_without_waiting_full_timeout(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = FakeProcess([1], pid=8888)
            host, _ = self._make_host(tmp, popen=lambda args, **kw: proc)
            with patch.object(app_window.os, "name", "nt"), \
                    patch.object(app_window, "ensure_dpi_aware"), \
                    patch.object(app_window, "processes_using_profile", return_value=[]), \
                    patch.object(app_window, "pids_using_profile", return_value=[]), \
                    patch.object(app_window, "find_windows_for_pids", return_value=[]), \
                    patch.object(app_window, "work_area", return_value=(0, 0, 1920, 1032)), \
                    patch.object(app_window, "dpi_scale", return_value=1.0), \
                    patch.object(app_window, "kill_tree") as killer:
                res = host.open_or_activate("http://127.0.0.1:8766/?token=abc")
            self.assertFalse(res.ok)
            self.assertEqual(res.reason, "process_exited")
            self.assertIn("立即退出", res.message)
            self.assertIn("下一步：", res.message)
            killer.assert_not_called()  # 已自行退出的 PID 无需强杀；也没有误杀他人

    def test_process_dies_during_survival_check_fails_and_cleans_up(self):
        with tempfile.TemporaryDirectory() as tmp:
            # 第一次 poll 还在（等到窗口），验证阶段 poll 返回退出码 2
            proc = FakeProcess([None, None, 2], pid=8899)
            host, _ = self._make_host(tmp, popen=lambda args, **kw: proc, verify_delay=0.05)
            with patch.object(app_window.os, "name", "nt"), \
                    patch.object(app_window, "ensure_dpi_aware"), \
                    patch.object(app_window, "processes_using_profile", return_value=[]), \
                    patch.object(app_window, "pids_using_profile", return_value=[]), \
                    patch.object(app_window, "find_windows_for_pids", return_value=[9002]), \
                    patch.object(app_window, "_is_window_valid", return_value=True), \
                    patch.object(app_window, "window_title", return_value="Agendum"), \
                    patch.object(app_window, "activate_window", return_value=True), \
                    patch.object(app_window, "work_area", return_value=(0, 0, 1920, 1032)), \
                    patch.object(app_window, "dpi_scale", return_value=1.0), \
                    patch.object(AppWindowHost, "_position_window"), \
                    patch.object(AppWindowHost, "_apply_branding"):
                res = host.open_or_activate("http://127.0.0.1:8766/?token=abc")
            self.assertFalse(res.ok)
            self.assertEqual(res.reason, "process_exited_after_launch")
            self.assertIn("未能保持运行", res.message)
            self.assertIn("下一步：", res.message)

    def test_unresponsive_page_fails_verification_and_kills_only_spawned_pid(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = FakeProcess([None, None, None, None], pid=9911)
            host, _ = self._make_host(tmp, url_ok=False, popen=lambda args, **kw: proc, verify_delay=0.01)
            with patch.object(app_window.os, "name", "nt"), \
                    patch.object(app_window, "ensure_dpi_aware"), \
                    patch.object(app_window, "processes_using_profile", return_value=[]), \
                    patch.object(app_window, "pids_using_profile", return_value=[]), \
                    patch.object(app_window, "find_windows_for_pids", return_value=[9003]), \
                    patch.object(app_window, "_is_window_valid", return_value=True), \
                    patch.object(app_window, "window_title", return_value="Agendum"), \
                    patch.object(app_window, "activate_window", return_value=True), \
                    patch.object(app_window, "work_area", return_value=(0, 0, 1920, 1032)), \
                    patch.object(app_window, "dpi_scale", return_value=1.0), \
                    patch.object(AppWindowHost, "_position_window"), \
                    patch.object(AppWindowHost, "_apply_branding"), \
                    patch.object(app_window, "kill_tree") as killer:
                res = host.open_or_activate("http://127.0.0.1:8766/?token=abc")
            self.assertFalse(res.ok)
            self.assertEqual(res.reason, "page_unresponsive")
            self.assertIn("无应答", res.message)
            self.assertIn("下一步：", res.message)
            killer.assert_called_once_with(9911)

    def test_existing_live_window_is_activated_instead_of_spawning_second_window(self):
        with tempfile.TemporaryDirectory() as tmp:
            popen_calls: list[list[str]] = []
            host, logs = self._make_host(tmp, url_ok=True, popen=lambda args, **kw: popen_calls.append(args))
            host.profile_dir.mkdir(parents=True)
            rows = [{"pid": 4444, "parent_pid": 1, "command_line": "msedge.exe --user-data-dir=" + str(host.profile_dir), "age_seconds": 30.0}]
            with patch.object(app_window.os, "name", "nt"), \
                    patch.object(app_window, "ensure_dpi_aware"), \
                    patch.object(app_window, "processes_using_profile", return_value=rows), \
                    patch.object(app_window, "find_windows_for_pids", return_value=[7001]), \
                    patch.object(app_window, "_is_window_valid", return_value=True), \
                    patch.object(app_window, "window_title", return_value="Agendum"), \
                    patch.object(app_window, "activate_window", return_value=True) as activate, \
                    patch.object(AppWindowHost, "_apply_branding"):
                res = host.open_or_activate("http://127.0.0.1:8766/?token=abc")
            self.assertTrue(res.ok)
            self.assertEqual(res.action, "activated")
            self.assertEqual(res.hwnd, 7001)
            self.assertEqual(popen_calls, [])
            activate.assert_called_once_with(7001)
            self.assertTrue(any("已激活现有" in line for line in logs))

    def test_young_process_within_grace_threshold_is_waited_not_killed_as_stale(self):
        with tempfile.TemporaryDirectory() as tmp:
            popen_calls: list[list[str]] = []
            host, _ = self._make_host(tmp, url_ok=True, popen=lambda args, **kw: popen_calls.append(args))
            host.profile_dir.mkdir(parents=True)
            rows = [{"pid": 5555, "parent_pid": 1, "command_line": "msedge.exe --user-data-dir=" + str(host.profile_dir), "age_seconds": 0.05}]
            # 第一次枚举窗口为空（刚拉起），等待片刻后窗口出现
            with patch.object(app_window.os, "name", "nt"), \
                    patch.object(app_window, "ensure_dpi_aware"), \
                    patch.object(app_window, "processes_using_profile", return_value=rows), \
                    patch.object(app_window, "find_windows_for_pids", side_effect=[[], [7002]]), \
                    patch.object(app_window, "_is_window_valid", return_value=True), \
                    patch.object(app_window, "window_title", return_value="Agendum"), \
                    patch.object(app_window, "activate_window", return_value=True) as activate, \
                    patch.object(app_window, "kill_tree") as killer, \
                    patch.object(AppWindowHost, "_apply_branding"):
                res = host.open_or_activate("http://127.0.0.1:8766/?token=abc")
            self.assertTrue(res.ok)
            self.assertEqual(res.action, "activated")
            self.assertEqual(res.hwnd, 7002)
            killer.assert_not_called()
            activate.assert_called_once_with(7002)
            self.assertEqual(popen_calls, [])

    def test_stale_windowless_edge_past_grace_is_cleaned_only_by_profile_pid_before_relaunch(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = FakeProcess([None, None, None, None], pid=6666)
            host, logs = self._make_host(tmp, url_ok=True, popen=lambda args, **kw: proc, verify_delay=0.01)
            host.profile_dir.mkdir(parents=True)
            stale_rows = [{"pid": 3131, "parent_pid": 1, "command_line": "msedge.exe --user-data-dir=" + str(host.profile_dir), "age_seconds": 120.0}]
            with patch.object(app_window.os, "name", "nt"), \
                    patch.object(app_window, "ensure_dpi_aware"), \
                    patch.object(app_window, "processes_using_profile", side_effect=[stale_rows, stale_rows, [], []]), \
                    patch.object(app_window, "pids_using_profile", side_effect=[[], []]), \
                    patch.object(app_window, "find_windows_for_pids", side_effect=[[], [7003], [7003]]), \
                    patch.object(app_window, "_is_window_valid", return_value=True), \
                    patch.object(app_window, "window_title", return_value="Agendum"), \
                    patch.object(app_window, "activate_window", return_value=True), \
                    patch.object(app_window, "work_area", return_value=(0, 0, 1920, 1032)), \
                    patch.object(app_window, "dpi_scale", return_value=1.0), \
                    patch.object(app_window, "kill_tree") as killer, \
                    patch.object(app_window.time, "sleep", return_value=None), \
                    patch.object(AppWindowHost, "_position_window"), \
                    patch.object(AppWindowHost, "_apply_branding"):
                res = host.open_or_activate("http://127.0.0.1:8766/?token=abc")
            self.assertTrue(res.ok)
            self.assertEqual(res.action, "launched")
            killer.assert_called_once_with(3131)
            self.assertTrue(any("清理占着主窗口资料目录的残留 Edge" in line for line in logs))

    def test_existing_window_with_unresponsive_page_fails_with_guidance(self):
        with tempfile.TemporaryDirectory() as tmp:
            host, _ = self._make_host(tmp, url_ok=False)
            host.profile_dir.mkdir(parents=True)
            rows = [{"pid": 4444, "parent_pid": 1, "command_line": "msedge.exe --user-data-dir=" + str(host.profile_dir), "age_seconds": 30.0}]
            with patch.object(app_window.os, "name", "nt"), \
                    patch.object(app_window, "ensure_dpi_aware"), \
                    patch.object(app_window, "processes_using_profile", return_value=rows), \
                    patch.object(app_window, "find_windows_for_pids", return_value=[7001]):
                res = host.open_or_activate("http://127.0.0.1:8766/?token=abc")
            self.assertFalse(res.ok)
            self.assertEqual(res.reason, "page_unresponsive")
            self.assertIn("无应答", res.message)
            self.assertIn("下一步：", res.message)

    def test_stop_kills_only_profile_matched_pids(self):
        with tempfile.TemporaryDirectory() as tmp:
            host, _ = self._make_host(tmp)
            host.profile_dir.mkdir(parents=True)
            host.hwnd = 7009
            host.pid_path.write_text("4545", encoding="utf-8")
            rows = [{"pid": 4545, "parent_pid": 1, "command_line": "msedge.exe --user-data-dir=" + str(host.profile_dir), "age_seconds": 10.0}]
            fake_u32 = SimpleNamespace(PostMessageW=lambda *a: True, IsWindow=lambda _h: True)
            with patch.object(app_window.os, "name", "nt"), \
                    patch.object(app_window, "_is_window_valid", return_value=True), \
                    patch.object(app_window, "user32", return_value=fake_u32), \
                    patch.object(app_window, "processes_using_profile", side_effect=[rows, rows]), \
                    patch.object(app_window, "kill_tree") as killer, \
                    patch.object(app_window.time, "sleep", return_value=None):
                host.stop()
            killer.assert_called_once_with(4545)
            self.assertFalse(host.pid_path.exists())


class LauncherUnifiedOpenTests(unittest.TestCase):
    def test_tray_on_open_uses_unified_open_console_and_notifies_on_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            env_file = root / ".env"
            env_file.write_text("AGENDUM_WEB_TOKEN=tok123\n", encoding="utf-8")
            url = launcher._console_url(env_file, 18766)
            self.assertIn("token=tok123", url)
            settings = SimpleNamespace(data_dir=str(root / "data"), web_port=18766)
            fake_proc = SimpleNamespace(poll=lambda: None)
            with patch.object(launcher.hosting, "hosting_status", return_value={"hosting_active": False}):
                tray = launcher.TrayHost(root, env_file, settings, fake_proc, url, 18766)
            notices: list[str] = []
            tray._notify = notices.append
            failure = AppWindowResult(
                ok=False,
                reason="edge_missing",
                message="未找到 Microsoft Edge（msedge.exe），已改用默认浏览器打开控制台。下一步：安装 Edge 后重试。",
            )

            class InlineThread:
                def __init__(self, target=None, name=None, daemon=None):
                    self._target = target

                def start(self):
                    self._target()

            with patch.object(launcher.threading, "Thread", InlineThread), \
                    patch.object(launcher, "_console_window_open", return_value=False), \
                    patch.object(tray.app_window, "open_or_activate", return_value=failure) as open_app, \
                    patch.object(launcher.webbrowser, "open", return_value=True) as open_browser:
                tray.on_open()
            open_app.assert_called_once_with(url)
            open_browser.assert_called_once_with(url)
            self.assertEqual(len(notices), 1)
            self.assertIn("未找到 Microsoft Edge", notices[0])
            self.assertIn("下一步：", notices[0])
            log_text = (root / "logs" / "launcher.log").read_text(encoding="utf-8")
            self.assertIn("未找到 Microsoft Edge", log_text)

    def test_tray_on_open_succeeds_without_falling_back_to_browser(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            env_file = root / ".env"
            env_file.write_text("AGENDUM_WEB_TOKEN=tok123\n", encoding="utf-8")
            url = launcher._console_url(env_file, 18766)
            settings = SimpleNamespace(data_dir=str(root / "data"), web_port=18766)
            fake_proc = SimpleNamespace(poll=lambda: None)
            with patch.object(launcher.hosting, "hosting_status", return_value={"hosting_active": False}):
                tray = launcher.TrayHost(root, env_file, settings, fake_proc, url, 18766)
            notices: list[str] = []
            tray._notify = notices.append
            success = AppWindowResult(ok=True, action="launched", hwnd=9100)

            class InlineThread:
                def __init__(self, target=None, name=None, daemon=None):
                    self._target = target

                def start(self):
                    self._target()

            with patch.object(launcher.threading, "Thread", InlineThread), \
                    patch.object(launcher, "_console_window_open", return_value=False), \
                    patch.object(tray.app_window, "open_or_activate", return_value=success) as open_app, \
                    patch.object(launcher.webbrowser, "open") as open_browser:
                tray.on_open()
            open_app.assert_called_once_with(url)
            open_browser.assert_not_called()
            self.assertEqual(notices, [])

    def test_browser_suppressed_skips_both_edge_and_webbrowser(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            env_file = root / ".env"
            settings = SimpleNamespace(data_dir=str(root / "data"))
            with patch.object(launcher, "BROWSER_SUPPRESSED", True), \
                    patch.object(launcher.AppWindowHost, "open_or_activate") as open_app, \
                    patch.object(launcher.webbrowser, "open") as open_browser:
                self.assertFalse(launcher._open_console(root, env_file, settings, "http://127.0.0.1:18766/?token=t"))
            open_app.assert_not_called()
            open_browser.assert_not_called()


if __name__ == "__main__":
    unittest.main()
