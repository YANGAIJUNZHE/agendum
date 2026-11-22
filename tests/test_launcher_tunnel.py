"""启动器托管 cloudflared 隧道与三代前缀令牌的回归测试（成功路径 + 失败路径）。"""

from __future__ import annotations

import importlib.util
import io
import json
import posixpath
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
from pathlib import Path, PureWindowsPath
from types import SimpleNamespace
from unittest.mock import patch

from agendum import hosting

_SPEC = importlib.util.spec_from_file_location(
    "packaging_launcher_tunnel", Path(__file__).parents[1] / "packaging" / "launcher.py"
)
launcher = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(launcher)


class FakeTunnelProcess:
    def __init__(self, polls: list[int | None], pid: int = 9001) -> None:
        self._polls = list(polls)
        self._idx = 0
        self.pid = pid
        self.returncode: int | None = None
        self.terminated = False
        self.killed = False

    def poll(self) -> int | None:
        if self._idx < len(self._polls):
            value = self._polls[self._idx]
            self._idx += 1
        else:
            value = self._polls[-1] if self._polls else None
        if value is not None:
            self.returncode = value
        return value

    def terminate(self) -> None:
        self.terminated = True
        if self.returncode is None:
            self.returncode = 0
        self._polls = [self.returncode]
        self._idx = 0

    def wait(self, timeout: float | None = None) -> int | None:
        if self.returncode is None:
            self.returncode = 0
        return self.returncode

    def kill(self) -> None:
        self.killed = True
        if self.returncode is None:
            self.returncode = -9
        self._polls = [self.returncode]
        self._idx = 0


def _http_json_response(payload: dict, status: int = 200):
    raw = json.dumps(payload).encode("utf-8")

    class Resp:
        def __init__(self) -> None:
            self.status = status
            self._bio = io.BytesIO(raw)

        def read(self, n: int = -1) -> bytes:
            return self._bio.read(n)

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

    return Resp()


class TokenFileMatchingTests(unittest.TestCase):
    def test_matches_same_install_dir_quoted_unquoted_and_slash_variants(self):
        token_file = Path(r"D:\Agendum\data\cloudflared.token")
        self.assertTrue(
            launcher._command_uses_token_file(
                r'"D:\Agendum\data\cloudflared.exe" tunnel --no-autoupdate run --token-file "D:\Agendum\data\cloudflared.token"',
                token_file,
            )
        )
        self.assertTrue(
            launcher._command_uses_token_file(
                r"cloudflared.exe tunnel run --token-file=d:/agendum/data/cloudflared.token",
                token_file,
            )
        )

    def test_rejects_other_install_dir_and_prefix_suffix_collisions(self):
        token_file = Path(r"D:\Agendum\data\cloudflared.token")
        self.assertFalse(
            launcher._command_uses_token_file(
                r'cloudflared.exe tunnel run --token-file "D:\Agendum-beta\data\cloudflared.token"',
                token_file,
            )
        )
        self.assertFalse(
            launcher._command_uses_token_file(
                r'cloudflared.exe tunnel run --token-file "D:\Agendum\data\cloudflared.token.bak"',
                token_file,
            )
        )
        self.assertFalse(
            launcher._command_uses_token_file(
                r'cloudflared.exe tunnel run --note "D:\Agendum\data\cloudflared.token"',
                token_file,
            )
        )

    def test_find_tunnel_processes_returns_none_when_cim_query_fails(self):
        with patch.object(launcher.os, "name", "nt"), patch.object(
            launcher.subprocess, "run", side_effect=OSError("cim blocked")
        ):
            self.assertIsNone(launcher._find_tunnel_processes(Path(r"D:\Agendum\data\cloudflared.token")))

    def test_posix_os_path_and_not_implemented_path_still_match_windows_token_paths(self):
        def raise_windows_path(*_args, **_kwargs):
            raise NotImplementedError("cannot instantiate 'WindowsPath' on your system")

        token_file = PureWindowsPath(r"D:\Agendum\data\cloudflared.token")
        with patch.object(launcher.os, "path", posixpath), \
                patch.object(launcher.os, "name", "nt"), \
                patch.object(launcher.sys, "platform", "linux"), \
                patch.object(launcher, "Path", side_effect=raise_windows_path), \
                patch.object(Path, "resolve", side_effect=raise_windows_path):
            self.assertTrue(
                launcher._command_uses_token_file(
                    r"cloudflared.exe tunnel run --token-file=d:/agendum/data/cloudflared.token",
                    token_file,
                )
            )
            self.assertFalse(
                launcher._command_uses_token_file(
                    r'cloudflared.exe tunnel run --token-file "D:\Agendum-beta\data\cloudflared.token"',
                    token_file,
                )
            )


class EnsureTunnelRunningTests(unittest.TestCase):
    def _prepare_fixture(self, tmp: str, *, exe: bool = True, token: bool = True, base_url: str = "https://sync.example.com") -> tuple[Path, Path, object]:
        root = Path(tmp)
        data_dir = root / "data"
        data_dir.mkdir(parents=True, exist_ok=True)
        (root / "logs").mkdir(parents=True, exist_ok=True)
        env_file = root / ".env"
        lines = ["AGENDUM_WEB_PORT=18766"]
        if base_url:
            lines.append(f"AGENDUM_MOBILE_BASE_URL={base_url}")
        env_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
        if exe:
            (data_dir / "cloudflared.exe").write_bytes(b"stub")
        if token:
            (data_dir / "cloudflared.token").write_text("dummy-token", encoding="utf-8")
        settings = SimpleNamespace(web_port=18766, mobile_base_url=base_url, data_dir=data_dir)
        return root, env_file, settings

    def test_default_protocol_succeeds_after_alive_and_info_200_check(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, env_file, settings = self._prepare_fixture(tmp)
            proc = FakeTunnelProcess([None, None, None, None], pid=7001)
            popen_calls: list[list[str]] = []

            def fake_popen(args, **kwargs):
                popen_calls.append(list(args))
                return proc

            with patch.object(launcher, "_find_tunnel_processes", return_value=[]), \
                    patch.object(launcher.subprocess, "Popen", side_effect=fake_popen), \
                    patch.object(
                        launcher.urllib.request,
                        "urlopen",
                        return_value=_http_json_response({"ok": True, "protocol": 1}),
                    ):
                res = launcher.ensure_tunnel_running(
                    root, env_file, settings, 18766, 18766, verify_delay=0.0, probe_timeout=0.5
                )

            self.assertTrue(res["ok"])
            self.assertEqual(res["protocol"], "default")
            self.assertIs(res["process"], proc)
            self.assertEqual(len(popen_calls), 1)
            self.assertNotIn("--protocol", popen_calls[0])
            self.assertIn("--token-file", popen_calls[0])
            self.assertIn(str((root / "data" / "cloudflared.token").resolve()), popen_calls[0])

    def test_falls_back_to_http2_when_default_exits_or_returns_530(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, env_file, settings = self._prepare_fixture(tmp)
            proc_quic = FakeTunnelProcess([None, None], pid=7001)
            proc_http2 = FakeTunnelProcess([None, None, None, None], pid=7002)
            popen_calls: list[list[str]] = []

            def fake_popen(args, **kwargs):
                popen_calls.append(list(args))
                return proc_quic if len(popen_calls) == 1 else proc_http2

            probe_calls = {"n": 0}

            def fake_urlopen(req, timeout=4.0):
                probe_calls["n"] += 1
                if probe_calls["n"] == 1:
                    raise urllib.error.HTTPError(req.full_url, 530, "Tunnel offline", {}, None)
                return _http_json_response({"ok": True, "protocol": 1})

            with patch.object(launcher, "_find_tunnel_processes", return_value=[]), \
                    patch.object(launcher.subprocess, "Popen", side_effect=fake_popen), \
                    patch.object(launcher.urllib.request, "urlopen", side_effect=fake_urlopen):
                res = launcher.ensure_tunnel_running(
                    root, env_file, settings, 18766, 18766, verify_delay=0.0, probe_timeout=0.0
                )

            self.assertTrue(res["ok"])
            self.assertEqual(res["protocol"], "http2")
            self.assertIs(res["process"], proc_http2)
            self.assertTrue(proc_quic.terminated)
            self.assertEqual(len(popen_calls), 2)
            self.assertIn("--protocol", popen_calls[1])
            self.assertIn("http2", popen_calls[1])

    def test_failure_path_returns_error_when_both_protocols_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, env_file, settings = self._prepare_fixture(tmp)
            proc1 = FakeTunnelProcess([1], pid=8001)
            proc2 = FakeTunnelProcess([2], pid=8002)
            procs = iter([proc1, proc2])

            def fake_popen(args, **kwargs):
                err_f = kwargs.get("stderr")
                if err_f is not None:
                    err_f.write("2026-11-22 ERR failed to dial edge with quic: timeout\n")
                    err_f.flush()
                return next(procs)

            with patch.object(launcher, "_find_tunnel_processes", return_value=[]), \
                    patch.object(launcher.subprocess, "Popen", side_effect=fake_popen):
                res = launcher.ensure_tunnel_running(
                    root, env_file, settings, 18766, 18766, verify_delay=0.0, probe_timeout=0.0
                )

            self.assertFalse(res["ok"])
            self.assertEqual(res["reason"], "tunnel_failed")
            self.assertIsNone(res["process"])
            self.assertIn("mobile-tunnel.err.log", res["message"])
            self.assertIn("下一步：", res["message"])
            log = (root / "logs" / "launcher.log").read_text(encoding="utf-8")
            self.assertIn("手机公网隧道启动失败", log)

    def test_deduplicates_when_same_token_process_is_already_healthy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, env_file, settings = self._prepare_fixture(tmp)
            with patch.object(launcher, "_find_tunnel_processes", return_value=[4242]), \
                    patch.object(launcher.subprocess, "Popen") as popen, \
                    patch.object(
                        launcher.urllib.request,
                        "urlopen",
                        return_value=_http_json_response({"ok": True, "protocol": 1}),
                    ):
                res = launcher.ensure_tunnel_running(
                    root, env_file, settings, 18766, 18766, verify_delay=0.0, probe_timeout=0.0
                )

            popen.assert_not_called()
            self.assertTrue(res["ok"])
            self.assertEqual(res["protocol"], "existing")
            self.assertEqual(res["existing_pids"], [4242])

    def test_replaces_stale_matched_pid_only_and_restarts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, env_file, settings = self._prepare_fixture(tmp)
            proc = FakeTunnelProcess([None, None, None, None], pid=7010)
            stopped: list[list[int]] = []
            calls = {"n": 0}

            def fake_urlopen(req, timeout=4.0):
                calls["n"] += 1
                if calls["n"] <= 2:
                    raise urllib.error.HTTPError(req.full_url, 530, "Tunnel offline", {}, None)
                return _http_json_response({"ok": True, "protocol": 1})

            with patch.object(launcher, "_find_tunnel_processes", return_value=[5555]), \
                    patch.object(launcher, "_stop_tunnel_pids", side_effect=lambda pids, *a, **k: stopped.append(list(pids))), \
                    patch.object(launcher.subprocess, "Popen", return_value=proc) as popen, \
                    patch.object(launcher.urllib.request, "urlopen", side_effect=fake_urlopen), \
                    patch.object(launcher.time, "sleep"):
                res = launcher.ensure_tunnel_running(
                    root, env_file, settings, 18766, 18766, verify_delay=0.0, probe_timeout=0.5
                )

            self.assertEqual(stopped, [[5555]])
            self.assertEqual(popen.call_count, 1)
            self.assertTrue(res["ok"])

    def test_port_mismatch_blocks_tunnel_launch_and_warns_clearly(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, env_file, settings = self._prepare_fixture(tmp)
            (root / "data" / "mobile-deployment.json").write_text(
                json.dumps({"origin": "http://127.0.0.1:18766"}), encoding="utf-8"
            )
            with patch.object(launcher.subprocess, "Popen") as popen:
                res = launcher.ensure_tunnel_running(
                    root, env_file, settings, 18766, 18767, verify_delay=0.0, probe_timeout=0.0
                )

            popen.assert_not_called()
            self.assertFalse(res["ok"])
            self.assertEqual(res["reason"], "port_mismatch")
            self.assertIn("18766", res["message"])
            self.assertIn("18767", res["message"])
            self.assertIn("下一步：", res["message"])

    def test_missing_exe_or_token_reports_clear_error(self):
        with tempfile.TemporaryDirectory() as tmp1:
            root1, env1, settings1 = self._prepare_fixture(tmp1, exe=False, token=True)
            res1 = launcher.ensure_tunnel_running(root1, env1, settings1, 18766, 18766)
            self.assertFalse(res1["ok"])
            self.assertEqual(res1["reason"], "missing_exe")
            self.assertIn("cloudflared.exe", res1["message"])

        with tempfile.TemporaryDirectory() as tmp2:
            root2, env2, settings2 = self._prepare_fixture(tmp2, exe=True, token=False)
            res2 = launcher.ensure_tunnel_running(root2, env2, settings2, 18766, 18766)
            self.assertFalse(res2["ok"])
            self.assertEqual(res2["reason"], "missing_token")
            self.assertIn("cloudflared.token", res2["message"])


class TrayTunnelLifecycleAndTokenTests(unittest.TestCase):
    def test_quit_stops_only_matched_tunnel_pids_never_by_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            data_dir = root / "data"
            data_dir.mkdir()
            (data_dir / "cloudflared.token").write_text("tok", encoding="utf-8")
            settings = SimpleNamespace(web_port=18766, data_dir=data_dir)
            tunnel_proc = FakeTunnelProcess([None], pid=6111)
            service_proc = FakeTunnelProcess([None], pid=6222)
            with patch.object(hosting, "hosting_status", return_value={"hosting_active": False}):
                host = launcher.TrayHost(
                    root, root / ".env", settings, service_proc, "http://127.0.0.1:18766", tunnel_process=tunnel_proc
                )
            host.icon = SimpleNamespace(stop=lambda: None)
            host.floatball = SimpleNamespace(stop=lambda: None)
            stopped_pids: list[int] = []
            taskkill_cmds: list[list[str]] = []

            def fake_run(cmd, **kwargs):
                taskkill_cmds.append(list(cmd))
                if "/PID" in cmd:
                    stopped_pids.append(int(cmd[cmd.index("/PID") + 1]))
                return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")

            with patch.object(launcher, "_find_tunnel_processes", return_value=[6333]), \
                    patch.object(launcher.os, "name", "nt"), \
                    patch.object(launcher.subprocess, "run", side_effect=fake_run), \
                    patch.object(hosting, "hosting_status", return_value={"hosting_active": False}):
                host._quit_steps()

            self.assertTrue(tunnel_proc.terminated)
            self.assertIsNone(host.tunnel_process)
            self.assertEqual(stopped_pids, [6333])
            for cmd in taskkill_cmds:
                self.assertNotIn("/IM", cmd)

    def test_quit_stops_tunnel_when_path_raises_not_implemented_under_mocked_nt(self):
        def raise_windows_path(*_args, **_kwargs):
            raise NotImplementedError("cannot instantiate 'WindowsPath' on your system")

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            data_dir = root / "data"
            data_dir.mkdir()
            (data_dir / "cloudflared.token").write_text("tok", encoding="utf-8")
            settings = SimpleNamespace(web_port=18766, data_dir=str(data_dir))
            tunnel_proc = FakeTunnelProcess([None], pid=6111)
            service_proc = FakeTunnelProcess([None], pid=6222)
            with patch.object(hosting, "hosting_status", return_value={"hosting_active": False}):
                host = launcher.TrayHost(
                    root, root / ".env", settings, service_proc, "http://127.0.0.1:18766", tunnel_process=tunnel_proc
                )
            host.icon = SimpleNamespace(stop=lambda: None)
            host.floatball = SimpleNamespace(stop=lambda: None)
            stopped_pids: list[int] = []
            taskkill_cmds: list[list[str]] = []

            def fake_run(cmd, **kwargs):
                taskkill_cmds.append(list(cmd))
                if "/PID" in cmd:
                    stopped_pids.append(int(cmd[cmd.index("/PID") + 1]))
                return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")

            with patch.object(launcher, "_find_tunnel_processes", return_value=[6333]), \
                    patch.object(launcher.os, "name", "nt"), \
                    patch.object(launcher.sys, "platform", "linux"), \
                    patch.object(launcher, "Path", side_effect=raise_windows_path), \
                    patch.object(launcher.subprocess, "run", side_effect=fake_run), \
                    patch.object(hosting, "hosting_status", return_value={"hosting_active": False}):
                host._quit_steps()

            self.assertEqual(stopped_pids, [6333])
            for cmd in taskkill_cmds:
                self.assertNotIn("/IM", cmd)
            log_text = (root / "logs" / "launcher.log").read_text(encoding="utf-8")
            self.assertIn("pid=6333", log_text)
            self.assertNotIn("退出时结束隧道子进程出错", log_text)

    def test_watchdog_tick_restarts_unexpectedly_exited_tunnel_up_to_limit(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            settings = SimpleNamespace(web_port=18766, data_dir=root / "data")
            dead_tunnel = FakeTunnelProcess([1], pid=7777)
            service_proc = FakeTunnelProcess([None, None, None], pid=7888)
            with patch.object(hosting, "hosting_status", return_value={"hosting_active": False}):
                host = launcher.TrayHost(
                    root, root / ".env", settings, service_proc, "http://127.0.0.1:18766", tunnel_process=dead_tunnel
                )
            notices: list[str] = []
            host._notify = notices.append
            with patch.object(host, "start_tunnel_async") as start_async:
                self.assertTrue(host.watchdog_tick())
            self.assertEqual(start_async.call_count, 1)
            self.assertEqual(host._tunnel_restart_attempts, 1)
            self.assertIsNone(host.tunnel_process)
            self.assertTrue(any("正在重连" in msg for msg in notices))

    def test_chaoxing_clipboard_and_floatball_read_agendum_and_noticehub_tokens(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            env_file = root / ".env"
            env_file.write_text("NOTICEHUB_WEB_TOKEN=nh-tok-999\n", encoding="utf-8")
            settings = SimpleNamespace(web_port=18766, data_dir=root / "data")
            with patch.object(hosting, "hosting_status", return_value={"hosting_active": False}):
                host = launcher.TrayHost(
                    root, env_file, settings, FakeTunnelProcess([None]), "http://127.0.0.1:18766"
                )
            host._notify = lambda msg: None
            seen_tokens: list[str] = []

            def fake_urlopen(req, timeout=20):
                seen_tokens.append(req.headers.get("X-token") or req.headers.get("X-Token") or "")
                return _http_json_response({"ok": True})

            with patch.object(launcher.urllib.request, "urlopen", side_effect=fake_urlopen):
                host._read_chaoxing_cookie()
            with patch.object(host, "_clipboard_text", return_value="cookie=abc"), \
                    patch.object(launcher.urllib.request, "urlopen", side_effect=fake_urlopen):
                host._import_clipboard_cookie()
            self.assertEqual(seen_tokens, ["nh-tok-999", "nh-tok-999"])

            opened_urls: list[str] = []
            host.floatball = SimpleNamespace(start=opened_urls.append)
            with patch.object(launcher, "FLOATBALL_ENABLED", True):
                host._start_floatball()
            self.assertEqual(opened_urls, ["http://127.0.0.1:18766/floatball?token=nh-tok-999"])


class StartMobileScriptTests(unittest.TestCase):
    @unittest.skipUnless(sys.platform == "win32", "PowerShell script verification on Windows")
    def test_start_mobile_honors_noticehub_port_and_fails_when_tunnel_exits(self):
        script_src = Path(__file__).parents[1] / "tools" / "start_mobile.ps1"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tools_dir = root / "tools"
            data_dir = root / "data"
            tools_dir.mkdir()
            data_dir.mkdir()
            shutil.copy2(script_src, tools_dir / "start_mobile.ps1")
            (data_dir / "cloudflared.token").write_text("dummy", encoding="utf-8")
            stub = root / "stub.exe"
            stub.write_bytes(b"")
            (root / ".env").write_text(
                "NOTICEHUB_WEB_PORT=19876\nNOTICEHUB_MOBILE_BASE_URL=https://sync.example.com\n",
                encoding="utf-8",
            )
            ps_script = root / "verify_start_mobile.ps1"
            ps_script.write_text(
                "\n".join(
                    [
                        "$ErrorActionPreference = 'Stop'",
                        f"$script = '{(tools_dir / 'start_mobile.ps1').as_posix()}'",
                        f"$stub = '{stub.as_posix()}'",
                        "$global:seenUris = @()",
                        "$global:spawned = @()",
                        "function Invoke-RestMethod { param([string]$Uri, [int]$TimeoutSec = 5, [Parameter(ValueFromRemainingArguments=$true)]$Rest) $global:seenUris += $Uri; if ($Uri -eq 'http://127.0.0.1:19876/api/v1/info') { return [pscustomobject]@{ ok = $true; protocol = 1; server_id = 'srv-nh' } }; throw 'unreachable' }",
                        "function Start-Process { param([string]$FilePath, [string[]]$ArgumentList, [Parameter(ValueFromRemainingArguments=$true)]$Rest) $global:spawned += ,$ArgumentList; return [pscustomobject]@{ Id = 99999; StartTime = [datetime]::UtcNow; HasExited = $true; ExitCode = 1 } | Add-Member -MemberType ScriptMethod -Name Refresh -Value { } -PassThru }",
                        "& $script -PythonPath $stub -NoTunnel",
                        "if ($global:seenUris[0] -ne 'http://127.0.0.1:19876/api/v1/info') { throw ('wrong port uri: ' + $global:seenUris[0]) }",
                        "Remove-Item -LiteralPath (Join-Path (Split-Path $stub) 'data/mobile-runtime.json') -Force",
                        "$failed = $false",
                        "try { & $script -PythonPath $stub -CloudflaredPath $stub } catch { if ($_.Exception.Message -match 'cloudflared') { $failed = $true } else { throw } }",
                        "if (-not $failed) { throw 'expected start_mobile to fail when tunnel exits' }",
                        "if ($global:spawned.Count -ne 2) { throw ('expected default + http2 retry, got ' + $global:spawned.Count) }",
                        "if ($global:spawned[1] -notcontains '--protocol' -or $global:spawned[1] -notcontains 'http2') { throw 'missing http2 fallback' }",
                    ]
                ),
                encoding="utf-8",
            )
            done = subprocess.run(
                ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ps_script)],
                capture_output=True,
                text=True,
                timeout=20,
            )
            self.assertEqual(done.returncode, 0, msg=f"stdout={done.stdout}\nstderr={done.stderr}")


if __name__ == "__main__":
    unittest.main()
