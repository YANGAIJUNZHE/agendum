"""启动器先退出后的托管 QQ：归属、去重、清理和失败反馈。"""
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from agendum import hosting, napcat_admin as na


class ManagedQQLifecycleTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.settings = SimpleNamespace(data_dir=Path(temporary.name), hosting_restore_qq=True)
        self.profile = str(na._profile_dir(self.settings))
        self.rows = []
        self.killed = []
        self.probes = []
        na.clear_probe_cache()
        self.addCleanup(na.clear_probe_cache)
        probe = patch.object(na, "_run_probe", side_effect=self.probe)
        kill = patch.object(na.subprocess, "run", side_effect=self.kill)
        detect = patch.object(na, "detect_boot", return_value=None)
        for replacement in (probe, kill, detect):
            replacement.start()
            self.addCleanup(replacement.stop)

    def row(self, pid, profile=None, *, name="QQ.exe", parent=0):
        command = f'"D:\\shared engine\\{name}"'
        if profile is not None:
            command += f' --user-data-dir="{profile}"'
        return {"Name": name, "ProcessId": pid, "ParentProcessId": parent, "CommandLine": command}

    def probe(self, args, *, what):
        self.probes.append(args[0])
        if args[0] == "tasklist.exe":
            return "\n".join(f'"{r["Name"]}","{r["ProcessId"]}"' for r in self.rows).encode()
        return json.dumps(self.rows).encode()

    def kill(self, args, **kwargs):
        pid = int(args[2])
        self.killed.append(pid)
        tree = {pid}
        while True:
            children = {r["ProcessId"] for r in self.rows if r["ParentProcessId"] in tree}
            if children <= tree:
                break
            tree |= children
        self.rows[:] = [r for r in self.rows if r["ProcessId"] not in tree]
        return subprocess.CompletedProcess(args, 0)

    def test_profile_matching_requires_complete_argument(self):
        profile = r"D:\Hub Data\napcat-profile"
        for command in (
            f'QQ.exe --user-data-dir="{profile}" --enable-logging',
            f'QQ.exe "--user-data-dir={profile}"',
            'QQ.exe --user-data-dir="d:/hub data/NAPCAT-PROFILE/"',
            f'QQ.exe --user-data-dir "{profile}"',
        ):
            with self.subTest(command=command):
                self.assertTrue(na._uses_profile(command, profile))
        for command in (
            f'QQ.exe --user-data-dir="{profile}-other"',
            f'QQ.exe --user-data-dir="{profile}\\child"',
            f'QQ.exe --log-file="{profile}"',
            f'QQ.exe --other-user-data-dir="{profile}"',
            f'QQ.exe "--log-message=example --user-data-dir={profile}"',
            f'QQ.exe --user-data-dir="{profile}" --user-data-dir="{profile}-other"',
            "QQ.exe",
        ):
            with self.subTest(command=command):
                self.assertFalse(na._uses_profile(command, profile))

    def test_orphan_qq_prevents_second_launch_before_login(self):
        self.rows = [self.row(20212, self.profile)]
        with patch.object(na, "_ports", return_value=set()), patch.object(na.subprocess, "Popen") as popen:
            first = na.launch(self.settings)
            second = na.launch(self.settings)
        self.assertTrue(first["already_running"])
        self.assertTrue(second["already_running"])
        self.assertEqual(first["pid"], 20212)
        self.assertEqual(na.running_pids(self.settings), [20212])
        popen.assert_not_called()

    def test_stop_without_engine_files_kills_only_own_qq_tree(self):
        for fast in (False, True):
            with self.subTest(fast=fast):
                na.clear_probe_cache()
                self.killed.clear()
                self.rows = [
                    self.row(20212, self.profile), self.row(22304, self.profile, parent=20212),
                    self.row(30), self.row(40, self.profile + "-other"),
                    self.row(50, self.profile + "\\child"),
                    self.row(60, self.profile + "-other", name=na._NAPCAT_BOOT_EXE),
                ]
                first = na.stop(self.settings, fast=fast)
                second = na.stop(self.settings, fast=fast)
                self.assertTrue(first["ok"], first)
                self.assertEqual(first["stopped"], [20212])
                self.assertEqual(self.killed, [20212], "子进程由 /T 清理，重复停止不得重复结束")
                self.assertEqual([r["ProcessId"] for r in self.rows], [30, 40, 50, 60])
                self.assertTrue(second["already_stopped"])
                self.assertTrue(second["ok"], second)

    def test_shared_engine_path_does_not_allow_killing_other_profile(self):
        engine = Path("D:/shared engine")
        self.rows = [self.row(1, self.profile, name=na._NAPCAT_BOOT_EXE),
                     self.row(2, self.profile + "-other", name=na._NAPCAT_BOOT_EXE),
                     self.row(3, self.profile)]
        with patch.object(na, "detect_boot", return_value={"boot_exe": str(engine / na._NAPCAT_BOOT_EXE)}):
            result = na.stop(self.settings)
        self.assertTrue(result["ok"], result)
        self.assertEqual(set(self.killed), {1, 3})
        self.assertEqual([r["ProcessId"] for r in self.rows], [2])

    def test_successful_stop_reuses_identity_and_verifies_fresh_tasklist(self):
        self.rows = [self.row(1, self.profile), self.row(2)]
        result = na.stop(self.settings, fast=True)
        self.assertTrue(result["ok"], result)
        self.assertEqual(self.probes.count("powershell.exe"), 1)
        self.assertEqual(self.probes.count("tasklist.exe"), 2)
        self.assertEqual([r["ProcessId"] for r in self.rows], [2])

    def test_new_managed_process_during_stop_cannot_be_reported_as_success(self):
        self.rows = [self.row(1, self.profile)]

        def respawn(args, **kwargs):
            self.rows[:] = [self.row(2, self.profile)]
            return subprocess.CompletedProcess(args, 0)

        with patch.object(na.subprocess, "run", side_effect=respawn):
            result = na.stop(self.settings, fast=True)
        self.assertFalse(result["ok"])
        self.assertIn("2", result["error"])

    def test_failed_kill_preserves_hosting_and_does_not_restore_qq(self):
        self.rows = [self.row(20212, self.profile)]
        state = {"active": True, "uin": "123", "since": "before"}
        hosting._write_state(self.settings, state)
        with patch.object(na.subprocess, "run", return_value=subprocess.CompletedProcess([], 5)), \
                patch.object(hosting, "start_user_qq") as restore:
            result = hosting.stop(self.settings)
        self.assertFalse(result["ok"])
        self.assertIn("20212", result["error"])
        self.assertEqual(hosting._read_state(self.settings), state)
        restore.assert_not_called()
        self.assertEqual(na.running_pids(self.settings), [20212])

    def test_restore_runs_only_after_qq_tree_is_gone_and_state_is_cleared(self):
        self.rows = [self.row(1, self.profile), self.row(2, self.profile, parent=1)]
        hosting._write_state(self.settings, {"active": True})

        def restore(settings, **kwargs):
            self.assertEqual(self.rows, [], "恢复普通 QQ 前必须结束托管 QQ 整棵进程树")
            self.assertIsNone(hosting._process_cache)
            return {"ok": True}

        hosting._process_cache = (0, [{"ProcessId": 1}])
        self.addCleanup(setattr, hosting, "_process_cache", None)
        with patch.object(hosting, "start_user_qq", side_effect=restore) as start:
            result = hosting.stop(self.settings)
        self.assertTrue(result["ok"], result)
        start.assert_called_once()
        self.assertFalse(hosting._read_state(self.settings)["active"])

    def test_kill_timeout_is_failure_and_invalidates_cached_state(self):
        self.rows = [self.row(20212, self.profile)]
        with patch.object(na.subprocess, "run", side_effect=subprocess.TimeoutExpired("taskkill", 1)):
            result = na.stop(self.settings, fast=True, budget=1)
        self.assertFalse(result["ok"])
        self.assertIn("超时", result["error"])
        self.rows.clear()
        self.assertEqual(na.running_pids(self.settings), [], "失败之后也不能留下旧进程缓存")

    def test_stop_probe_failure_is_not_already_stopped(self):
        self.rows = [self.row(20212, self.profile)]
        with patch.object(na, "_process_details", side_effect=OSError("query failed")):
            result = na.stop(self.settings, fast=True)
        self.assertFalse(result["ok"])
        self.assertFalse(result["already_stopped"])
        self.assertEqual(self.killed, [])

    def test_unreadable_qq_command_line_is_failure_without_killing(self):
        self.rows = [{"Name": "QQ.exe", "ProcessId": 20212, "CommandLine": None}]
        result = na.stop(self.settings, fast=True)
        self.assertFalse(result["ok"])
        self.assertFalse(result["already_stopped"])
        self.assertEqual(self.killed, [])


class StopProbeBudgetTests(unittest.TestCase):
    def tearDown(self):
        na.clear_probe_cache()

    def test_real_probe_failure_cannot_be_converted_to_success_on_stop(self):
        settings = SimpleNamespace(data_dir=Path(tempfile.gettempdir()))
        na.clear_probe_cache()
        with patch.object(na.subprocess, "check_output", side_effect=OSError("query failed")):
            result = na.stop(settings, fast=True)
        self.assertFalse(result["ok"])
        self.assertFalse(result["already_stopped"])

    def test_fast_stop_uses_one_budget_for_all_subprocesses(self):
        settings = SimpleNamespace(data_dir=Path(tempfile.gettempdir()))
        profile = str(na._profile_dir(settings))
        clock = [0.0]
        seen = []

        def query(args, **kwargs):
            seen.append(kwargs["timeout"])
            clock[0] += 2.0
            if args[0] == "tasklist.exe":
                return b'"QQ.exe","20212"'
            return json.dumps([{"Name": "QQ.exe", "ProcessId": 20212,
                                "CommandLine": f'QQ.exe --user-data-dir="{profile}"'}]).encode()

        na.clear_probe_cache()
        with patch.object(na.time, "monotonic", side_effect=lambda: clock[0]), \
                patch.object(na.subprocess, "check_output", side_effect=query), \
                patch.object(na.subprocess, "run", side_effect=subprocess.TimeoutExpired("taskkill", 1)) as kill:
            result = na.stop(settings, fast=True, budget=5)
        self.assertFalse(result["ok"])
        self.assertEqual(seen, [5, 3])
        self.assertEqual(kill.call_args.kwargs["timeout"], 1)


if __name__ == "__main__":
    unittest.main()
