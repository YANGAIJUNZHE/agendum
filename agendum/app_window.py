"""Dedicated Microsoft Edge `--app=` host for the Agendum main desktop window."""

from __future__ import annotations

import ctypes
import hashlib
import os
import subprocess
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable

from .edge_app import (
    CREATE_NO_WINDOW,
    RECT,
    SWP_NOACTIVATE,
    SWP_NOZORDER,
    activate_window,
    apply_window_icon_and_aumid,
    destroy_icons,
    dpi_scale,
    ensure_dpi_aware,
    find_edge_path,
    find_windows_for_pids,
    host_path,
    kill_tree,
    normalize_profile_path,
    pids_using_profile,
    processes_using_profile,
    user32,
    window_title,
    work_area,
)

APP_PROFILE_DIR_NAME = "app-window-profile"
APP_PID_FILE_NAME = "app-window.pid"
DEFAULT_WINDOW_SIZE = (1200, 800)
WINDOW_WAIT_SECONDS = 15.0
VERIFY_SURVIVAL_SECONDS = 2.0
STARTUP_GRACE_SECONDS = 5.0
_ERROR_TITLE_MARKERS = (
    "无法访问此页面",
    "can't reach this page",
    "can’t reach this page",
    "aw, snap",
    "哦豁",
    "err_",
)


@dataclass
class AppWindowResult:
    ok: bool
    action: str = ""
    hwnd: int | None = None
    reason: str = ""
    message: str = ""


def app_window_profile_dir(data_dir: Path | str) -> Path:
    """Dedicated Edge `--user-data-dir` for the main window (separate from floatball)."""
    target = host_path(data_dir) / APP_PROFILE_DIR_NAME
    try:
        return target.resolve()
    except (OSError, NotImplementedError):
        return target


def app_user_model_id(install_root: Path | str) -> str:
    """Per-install AppUserModelID so prod/beta/regular Edge stay distinct on the taskbar."""
    norm = normalize_profile_path(install_root) or str(install_root)
    digest = hashlib.sha256(norm.encode("utf-8", "replace")).hexdigest()[:12]
    return f"Agendum.App.{digest}"


def select_app_icon_path(install_root: Path | str, channel: str | None = None) -> Path | None:
    root = host_path(install_root)
    names = ("icon-beta.ico", "icon.ico") if channel == "beta" else ("icon.ico",)
    for name in names:
        candidate = root / "assets" / name
        if candidate.is_file():
            return candidate
    return None


def compute_initial_geometry(
    work: tuple[int, int, int, int],
    scale: float = 1.0,
    preferred: tuple[int, int] = DEFAULT_WINDOW_SIZE,
) -> dict[str, int]:
    """Compute centered physical & logical bounds clamped within `work`."""
    left, top, right, bottom = work
    work_w = max(320, int(right) - int(left))
    work_h = max(240, int(bottom) - int(top))
    eff_scale = max(1.0, float(scale or 1.0))
    target_w = round(preferred[0] * eff_scale)
    target_h = round(preferred[1] * eff_scale)
    max_w = max(640, work_w - 48) if work_w >= 720 else work_w
    max_h = max(480, work_h - 48) if work_h >= 540 else work_h
    phys_w = max(min(target_w, max_w), min(work_w, 640))
    phys_h = max(min(target_h, max_h), min(work_h, 480))
    phys_w = min(phys_w, work_w)
    phys_h = min(phys_h, work_h)
    phys_x = int(left) + max(0, (work_w - phys_w) // 2)
    phys_y = int(top) + max(0, (work_h - phys_h) // 2)
    return {
        "phys_x": phys_x,
        "phys_y": phys_y,
        "phys_w": phys_w,
        "phys_h": phys_h,
        "logical_x": round(phys_x / eff_scale),
        "logical_y": round(phys_y / eff_scale),
        "logical_w": max(320, round(phys_w / eff_scale)),
        "logical_h": max(240, round(phys_h / eff_scale)),
    }


def is_error_page_title(title: str) -> bool:
    """Detect disconnected/crashed Edge window titles (ignore empty loading titles)."""
    text = str(title or "").strip()
    if not text:
        return False
    lower = text.casefold()
    if any(marker in lower for marker in _ERROR_TITLE_MARKERS):
        return True
    if lower.startswith(("http://", "https://", "127.0.0.1", "localhost")):
        return True
    return False


def url_responds(url: str, timeout: float = 2.5) -> bool:
    """Verify the target console URL actually answers HTTP 200."""
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            status = getattr(response, "status", None)
            if status is None:
                status = response.getcode()
            if status != 200:
                return False
            response.read(256)
            return True
    except Exception:
        return False


def _same_origin(first: str, second: str) -> bool:
    if not first or not second:
        return False
    a = urllib.parse.urlsplit(first)
    b = urllib.parse.urlsplit(second)
    return (a.scheme, a.netloc) == (b.scheme, b.netloc)


def _is_window_valid(hwnd: int | None) -> bool:
    if os.name != "nt" or not hwnd:
        return False
    try:
        return bool(user32().IsWindow(hwnd))
    except Exception:
        return False


def _window_rect(hwnd: int) -> tuple[int, int, int, int] | None:
    if os.name != "nt" or not hwnd:
        return None
    rect = RECT()
    if not user32().GetWindowRect(hwnd, ctypes.byref(rect)):
        return None
    return rect.left, rect.top, rect.right, rect.bottom


def _rect_outside_work(rect: tuple[int, int, int, int], work: tuple[int, int, int, int]) -> bool:
    left, top, right, bottom = rect
    w_left, w_top, w_right, w_bottom = work
    return left < w_left or top < w_top or right > w_right or bottom > w_bottom


class AppWindowHost:
    """Manage the single standalone Edge `--app=` main console window per install."""

    def __init__(
        self,
        install_root: Path | str,
        data_dir: Path | str,
        *,
        channel: str | None = None,
        verify_delay: float = VERIFY_SURVIVAL_SECONDS,
        window_wait: float = WINDOW_WAIT_SECONDS,
        startup_grace: float = STARTUP_GRACE_SECONDS,
        url_probe: Callable[[str], bool] | None = None,
        edge_finder: Callable[[], str] | None = None,
        popen: Callable[..., subprocess.Popen] | None = None,
        logger: Callable[[str], None] | None = None,
    ) -> None:
        self.install_root = host_path(install_root)
        self.data_dir = host_path(data_dir)
        self.channel = channel
        self.profile_dir = app_window_profile_dir(self.data_dir)
        self.pid_path = self.data_dir / APP_PID_FILE_NAME
        self.verify_delay = float(verify_delay)
        self.window_wait = float(window_wait)
        self.startup_grace = float(startup_grace)
        self._url_probe = url_probe or url_responds
        self._edge_finder = edge_finder or find_edge_path
        self._popen = popen or subprocess.Popen
        self._logger = logger
        self.process: subprocess.Popen | None = None
        self.hwnd: int | None = None
        self._active_url = ""
        self._started_at = 0.0
        self._icon_handles: list[int] = []

    def _log(self, message: str) -> None:
        if self._logger is not None:
            try:
                self._logger(message)
            except Exception:
                pass

    def _apply_branding(self, hwnd: int) -> None:
        icon_path = select_app_icon_path(self.install_root, self.channel)
        exe_path = self.install_root / "Agendum.exe"
        display_name = "Agendum Beta" if self.channel == "beta" else "Agendum"
        new_handles = apply_window_icon_and_aumid(
            hwnd,
            icon_path=icon_path,
            app_id=app_user_model_id(self.install_root),
            display_name=display_name,
            relaunch_command=str(exe_path) if exe_path.is_file() else "",
        )
        if new_handles:
            old_handles = self._icon_handles
            self._icon_handles = new_handles
            if old_handles:
                destroy_icons(old_handles)

    def _position_window(self, hwnd: int, *, force_center: bool) -> None:
        if os.name != "nt" or not hwnd:
            return
        lib = user32()
        if lib.IsIconic(hwnd):
            return
        work = work_area(hwnd)
        rect = _window_rect(hwnd)
        if not force_center and rect is not None and not _rect_outside_work(rect, work):
            return
        geom = compute_initial_geometry(work, scale=dpi_scale(hwnd))
        lib.SetWindowPos(
            hwnd,
            None,
            geom["phys_x"],
            geom["phys_y"],
            geom["phys_w"],
            geom["phys_h"],
            SWP_NOZORDER | SWP_NOACTIVATE,
        )

    def _tracked_pid(self) -> int | None:
        proc = self.process
        if proc is None:
            return None
        if proc.poll() is None:
            return getattr(proc, "pid", None)
        return None

    def _profile_state(self) -> tuple[list[dict[str, Any]] | None, set[int]]:
        rows = processes_using_profile(self.profile_dir)
        pids: set[int] = set()
        if rows is not None:
            pids.update(row["pid"] for row in rows if row.get("pid"))
        tracked = self._tracked_pid()
        if tracked:
            pids.add(int(tracked))
        return rows, pids

    def _find_window_for_pids(self, pids: Iterable[int]) -> int | None:
        windows = find_windows_for_pids(
            pids,
            min_width=200,
            min_height=150,
            allow_minimized=True,
        )
        if self.hwnd and self.hwnd in windows:
            return self.hwnd
        return windows[0] if windows else None

    def _youngest_age(self, rows: list[dict[str, Any]] | None) -> float | None:
        ages: list[float] = []
        if rows:
            for row in rows:
                age = row.get("age_seconds")
                if age is not None:
                    ages.append(float(age))
        if self._started_at and self._tracked_pid():
            ages.append(max(0.0, time.monotonic() - self._started_at))
        return min(ages) if ages else None

    def _wait_existing_window_during_grace(
        self,
        rows: list[dict[str, Any]] | None,
        pids: set[int],
    ) -> int | None:
        hwnd = self._find_window_for_pids(pids)
        if hwnd:
            return hwnd
        age = self._youngest_age(rows)
        if age is None or age >= self.startup_grace:
            return None
        remaining = min(self.startup_grace, self.startup_grace - age)
        deadline = time.monotonic() + remaining
        while time.monotonic() < deadline:
            time.sleep(min(0.2, max(0.0, deadline - time.monotonic())))
            rows_now, pids_now = self._profile_state()
            if not pids_now:
                return None
            hwnd = self._find_window_for_pids(pids_now)
            if hwnd:
                return hwnd
            if rows_now is not None and not rows_now and not self._tracked_pid():
                return None
        return self._find_window_for_pids(pids)

    def _wait_title_settled(self, hwnd: int, budget: float = 1.5) -> str:
        deadline = time.monotonic() + budget
        title = window_title(hwnd)
        while not title.strip() and time.monotonic() < deadline and _is_window_valid(hwnd):
            time.sleep(0.15)
            title = window_title(hwnd)
        return title

    def _cleanup_profile_pids(self, pids: Iterable[int], *, reason: str) -> bool:
        targets = [int(pid) for pid in pids if pid and int(pid) > 0]
        if not targets:
            return True
        self._log("%s：pids=%s" % (reason, targets))
        for pid in targets:
            kill_tree(pid)
        time.sleep(0.6)
        remaining = pids_using_profile(self.profile_dir)
        if remaining:
            for pid in remaining:
                kill_tree(pid)
            time.sleep(0.4)
            remaining = pids_using_profile(self.profile_dir)
        return not remaining

    def _cleanup_failed_launch(self, process: subprocess.Popen | None) -> None:
        targets: set[int] = set()
        if process is not None:
            pid = getattr(process, "pid", None)
            if pid and process.poll() is None:
                targets.add(int(pid))
        profile_pids = pids_using_profile(self.profile_dir)
        if profile_pids:
            targets.update(profile_pids)
        for pid in targets:
            kill_tree(pid)
        try:
            self.pid_path.unlink()
        except OSError:
            pass
        if self.process is process:
            self.process = None
        self.hwnd = None

    def activate_existing(self, url: str = "") -> AppWindowResult | None:
        """Activate a live main window for this install's profile if present."""
        if os.name != "nt":
            return None
        if not self.profile_dir.exists() and self._tracked_pid() is None and not self.hwnd:
            return None
        rows, pids = self._profile_state()
        if rows is None and self._tracked_pid() and _is_window_valid(self.hwnd):
            pids = {int(self._tracked_pid())}  # type: ignore[arg-type]
        if not pids:
            return None
        hwnd = self._wait_existing_window_during_grace(rows, pids)
        if not hwnd:
            return None
        if url and not self._url_probe(url):
            return AppWindowResult(
                ok=False,
                reason="page_unresponsive",
                message=(
                    "检测到控制台窗口已存在，但服务页面当前无应答，已改用默认浏览器打开。"
                    "下一步：确认托盘服务正在运行（必要时点「重启服务」）后再点「打开控制台」。"
                ),
            )
        title = self._wait_title_settled(hwnd, budget=1.0)
        port_changed = bool(self._active_url and url and not _same_origin(self._active_url, url))
        if is_error_page_title(title) or port_changed:
            return None
        self._apply_branding(hwnd)
        if activate_window(hwnd):
            self.hwnd = hwnd
            if url:
                self._active_url = url
            self._log("已激活现有 Agendum 主窗口（hwnd=%s）" % hwnd)
            return AppWindowResult(ok=True, action="activated", hwnd=hwnd)
        return None

    def has_live_window(self, url: str = "") -> bool:
        """Check whether a healthy main app window is open for this install's profile."""
        if os.name != "nt":
            return False
        if not self.profile_dir.exists() and self._tracked_pid() is None and not self.hwnd:
            return False
        _, pids = self._profile_state()
        if not pids:
            return False
        hwnd = self._find_window_for_pids(pids)
        if not hwnd:
            return False
        if is_error_page_title(window_title(hwnd)):
            return False
        if url and not self._url_probe(url):
            return False
        return True

    def _wait_launched_window(self, process: subprocess.Popen) -> tuple[int | None, str]:
        deadline = time.monotonic() + self.window_wait
        stable: int | None = None
        hits = 0
        started = time.monotonic()
        while time.monotonic() < deadline:
            code = process.poll()
            candidates: set[int] = set()
            if code is None and getattr(process, "pid", None):
                candidates.add(int(process.pid))
            elapsed = time.monotonic() - started
            if code is not None or elapsed >= 0.8:
                candidates.update(pids_using_profile(self.profile_dir))
            if code is not None and not candidates:
                return None, "process_exited"
            hwnd = self._find_window_for_pids(candidates)
            if hwnd:
                if hwnd == stable:
                    hits += 1
                    if hits >= 2:
                        return hwnd, ""
                else:
                    stable = hwnd
                    hits = 1
            else:
                stable = None
                hits = 0
            time.sleep(min(0.2, max(0.0, deadline - time.monotonic())))
        return stable, "" if stable else "window_timeout"

    def _process_still_alive(self, process: subprocess.Popen, hwnd: int) -> tuple[bool, int | None]:
        if not _is_window_valid(hwnd):
            return False, process.poll()
        code = process.poll()
        if code is None:
            return True, None
        profile_pids = pids_using_profile(self.profile_dir)
        alive = bool(
            profile_pids
            and find_windows_for_pids(profile_pids, min_width=200, min_height=150, allow_minimized=True)
        )
        return alive, code

    def open_or_activate(self, url: str) -> AppWindowResult:
        """Open the main UI in a standalone Edge `--app=` window, or activate existing one."""
        if os.name != "nt":
            return AppWindowResult(
                ok=False,
                reason="unsupported",
                message="当前系统不是 Windows，已改用默认浏览器打开控制台。下一步：在默认浏览器中继续操作即可。",
            )
        ensure_dpi_aware()

        # 1. Existing live window check (with startup grace threshold & process+page verification).
        existing = self.activate_existing(url)
        if existing is not None:
            return existing

        # 2. Clean up any headless/error-stuck leftover Edge on THIS profile (never by process name).
        _, stale_pids = self._profile_state()
        if stale_pids:
            if not self._cleanup_profile_pids(stale_pids, reason="清理占着主窗口资料目录的残留 Edge"):
                return AppWindowResult(
                    ok=False,
                    reason="profile_locked",
                    message=(
                        "独立窗口资料目录仍被残留 Edge 进程占用且未能清理，已改用默认浏览器打开控制台。"
                        "下一步：关闭残留的 Agendum 浏览器窗口后，再点托盘「打开控制台」重试。"
                    ),
                )
            self.hwnd = None
            self.process = None

        # 3. Locate Edge binary.
        try:
            edge = self._edge_finder()
        except Exception:
            return AppWindowResult(
                ok=False,
                reason="edge_missing",
                message=(
                    "未找到 Microsoft Edge（msedge.exe），已改用默认浏览器打开控制台。"
                    "下一步：安装或修复 Microsoft Edge 后，再点托盘「打开控制台」即可恢复独立窗口。"
                ),
            )

        # 4. Prepare profile and initial centered geometry, then spawn Edge `--app=`.
        self.profile_dir.mkdir(parents=True, exist_ok=True)
        had_prefs = (self.profile_dir / "Default" / "Preferences").is_file()
        geom = compute_initial_geometry(work_area(None), scale=dpi_scale(None))
        args = [
            edge,
            "--app=" + url,
            "--window-size=%d,%d" % (geom["logical_w"], geom["logical_h"]),
            "--window-position=%d,%d" % (geom["logical_x"], geom["logical_y"]),
            "--user-data-dir=" + str(self.profile_dir),
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-sync",
            "--disable-extensions",
        ]
        try:
            process = self._popen(args, creationflags=CREATE_NO_WINDOW)
        except OSError as exc:
            return AppWindowResult(
                ok=False,
                reason="launch_failed",
                message=(
                    "无法启动 Microsoft Edge（%s），已改用默认浏览器打开控制台。"
                    "下一步：检查系统是否阻止运行 msedge.exe 后，再点托盘「打开控制台」。" % exc
                ),
            )
        self.process = process
        self._started_at = time.monotonic()
        try:
            self.data_dir.mkdir(parents=True, exist_ok=True)
            self.pid_path.write_text(str(process.pid), encoding="utf-8")
        except OSError:
            pass

        # 5. Wait for main window, apply icon & AppUserModelID, then verify survival + page response.
        hwnd, wait_reason = self._wait_launched_window(process)
        if not hwnd:
            exit_code = process.poll()
            self._cleanup_failed_launch(process)
            if wait_reason == "process_exited":
                return AppWindowResult(
                    ok=False,
                    reason="process_exited",
                    message=(
                        "独立窗口进程启动后立即退出（退出码 %s），已改用默认浏览器打开控制台。"
                        "下一步：确认 Microsoft Edge 本身可正常打开后，再点托盘「打开控制台」重试，详情见 logs\\launcher.log。"
                        % exit_code
                    ),
                )
            return AppWindowResult(
                ok=False,
                reason="window_timeout",
                message=(
                    "Microsoft Edge 独立窗口在预期时间内未出现，已改用默认浏览器打开控制台。"
                    "下一步：检查是否有安全软件或弹窗拦截 Edge 后，再点托盘「打开控制台」重试。"
                ),
            )

        self._apply_branding(hwnd)
        self._position_window(hwnd, force_center=not had_prefs)
        activate_window(hwnd)

        # Wait a couple seconds to verify the process stays alive and the page answers.
        alive, exit_code = True, None
        if self.verify_delay > 0:
            end = time.monotonic() + self.verify_delay
            while time.monotonic() < end:
                alive, exit_code = self._process_still_alive(process, hwnd)
                if not alive:
                    break
                time.sleep(min(0.25, max(0.0, end - time.monotonic())))
        if alive:
            alive, exit_code = self._process_still_alive(process, hwnd)

        if not alive:
            self._cleanup_failed_launch(process)
            return AppWindowResult(
                ok=False,
                reason="process_exited_after_launch",
                message=(
                    "独立窗口启动后未能保持运行（退出码 %s），已改用默认浏览器打开控制台。"
                    "下一步：检查 Microsoft Edge 是否异常退出后，再点托盘「打开控制台」重试，详情见 logs\\launcher.log。"
                    % exit_code
                ),
            )

        if not self._url_probe(url) or is_error_page_title(window_title(hwnd)):
            self._cleanup_failed_launch(process)
            return AppWindowResult(
                ok=False,
                reason="page_unresponsive",
                message=(
                    "独立窗口已启动但控制台页面无应答，已改用默认浏览器打开控制台。"
                    "下一步：确认托盘服务运行正常（必要时点「重启服务」）后再点「打开控制台」，详情见 logs\\launcher.log。"
                ),
            )

        # Re-apply icon after page settles in case Edge replaced WM_SETICON during load.
        self._apply_branding(hwnd)
        self.hwnd = hwnd
        self._active_url = url
        self._log("已以独立窗口打开控制台（hwnd=%s）" % hwnd)
        return AppWindowResult(ok=True, action="launched", hwnd=hwnd)

    def stop(self) -> None:
        """Close the managed app window owned by this install's profile (best-effort)."""
        hwnd = self.hwnd
        if os.name == "nt" and hwnd and _is_window_valid(hwnd):
            try:
                from .edge_app import WM_CLOSE

                user32().PostMessageW(hwnd, WM_CLOSE, 0, 0)
            except Exception:
                pass
        if self._icon_handles:
            destroy_icons(self._icon_handles)
            self._icon_handles = []
        if os.name == "nt" and (self.profile_dir.exists() or self._tracked_pid() or hwnd):
            _, pids = self._profile_state()
            if pids and hwnd:
                time.sleep(0.4)
                _, pids = self._profile_state()
            for pid in pids:
                kill_tree(pid)
        try:
            self.pid_path.unlink()
        except OSError:
            pass
        self.process = None
        self.hwnd = None
        self._active_url = ""


__all__ = [
    "APP_PID_FILE_NAME",
    "APP_PROFILE_DIR_NAME",
    "AppWindowHost",
    "AppWindowResult",
    "DEFAULT_WINDOW_SIZE",
    "STARTUP_GRACE_SECONDS",
    "VERIFY_SURVIVAL_SECONDS",
    "WINDOW_WAIT_SECONDS",
    "app_user_model_id",
    "app_window_profile_dir",
    "compute_initial_geometry",
    "is_error_page_title",
    "select_app_icon_path",
    "url_responds",
]
