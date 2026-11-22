"""Shared Microsoft Edge `--app=` + Win32 window and profile-process helpers.

Used by both `agendum.floatball_host` and `agendum.app_window`. Pure stdlib only,
and safe to import on non-Windows platforms (no Windows-only ctypes calls at import
time).
"""

from __future__ import annotations

import ctypes
import datetime as dt
import json
import ntpath
import os
import re
import subprocess
import time
import uuid
from pathlib import Path
from typing import Any, Iterable, Sequence

from .win_text import decode_console_output

EDGE_CANDIDATES = (
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
)
WINDOW_CLASS = "Chrome_WidgetWin_1"
CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)

GWL_STYLE = -16
GWL_EXSTYLE = -20
GWLP_WNDPROC = -4
WS_CAPTION = 0x00C00000
WS_THICKFRAME = 0x00040000
WS_POPUP = 0x80000000
WS_SYSMENU = 0x00080000
WS_MINIMIZEBOX = 0x00020000
WS_MAXIMIZEBOX = 0x00010000
WS_EX_TOOLWINDOW = 0x00000080
WS_EX_TOPMOST = 0x00000008
WS_EX_NOACTIVATE = 0x08000000
HWND_TOPMOST = -1
SWP_NOSIZE = 0x0001
SWP_NOMOVE = 0x0002
SWP_NOZORDER = 0x0004
SWP_NOACTIVATE = 0x0010
SWP_FRAMECHANGED = 0x0020
SWP_SHOWWINDOW = 0x0040
SW_HIDE = 0
SW_SHOW = 5
SW_RESTORE = 9
SW_SHOWNOACTIVATE = 4
WM_CLOSE = 0x0010
WM_SETICON = 0x0080
WM_NCHITTEST = 0x0084
ICON_SMALL = 0
ICON_BIG = 1
IMAGE_ICON = 1
LR_LOADFROMFILE = 0x0010
HTCLIENT = 1
HTCAPTION = 2
MONITOR_DEFAULTTONEAREST = 2
ASFW_ANY = -1
VT_LPWSTR = 31

_APPUSERMODEL_FMTID = "{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}"
_IID_IPROPERTYSTORE = "{886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99}"
_PKEY_RELAUNCH_COMMAND = 2
_PKEY_RELAUNCH_ICON = 3
_PKEY_RELAUNCH_DISPLAY_NAME = 4
_PKEY_APPUSERMODEL_ID = 5


class RECT(ctypes.Structure):
    _fields_ = [
        ("left", ctypes.c_long),
        ("top", ctypes.c_long),
        ("right", ctypes.c_long),
        ("bottom", ctypes.c_long),
    ]


class POINT(ctypes.Structure):
    _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]


class MONITORINFO(ctypes.Structure):
    _fields_ = [
        ("cbSize", ctypes.c_ulong),
        ("rcMonitor", RECT),
        ("rcWork", RECT),
        ("dwFlags", ctypes.c_ulong),
    ]


class _GUID(ctypes.Structure):
    _fields_ = [
        ("Data1", ctypes.c_uint32),
        ("Data2", ctypes.c_uint16),
        ("Data3", ctypes.c_uint16),
        ("Data4", ctypes.c_ubyte * 8),
    ]

    @classmethod
    def from_str(cls, text: str) -> "_GUID":
        parsed = uuid.UUID(text)
        data4 = (ctypes.c_ubyte * 8)(*parsed.bytes[8:])
        return cls(parsed.time_low, parsed.time_mid, parsed.time_hi_version, data4)


class _PROPERTYKEY(ctypes.Structure):
    _fields_ = [("fmtid", _GUID), ("pid", ctypes.c_uint32)]


class _PROPVARIANT_LPWSTR(ctypes.Structure):
    _fields_ = [
        ("vt", ctypes.c_uint16),
        ("wReserved1", ctypes.c_uint16),
        ("wReserved2", ctypes.c_uint16),
        ("wReserved3", ctypes.c_uint16),
        ("pwszVal", ctypes.c_wchar_p),
        ("extra", ctypes.c_void_p),
    ]


_DPI_READY = False
_U32_READY = False


def ensure_dpi_aware() -> None:
    """Enable Per-Monitor DPI awareness on Windows so Win32 rects match physical pixels."""
    global _DPI_READY
    if _DPI_READY or os.name != "nt":
        return
    _DPI_READY = True
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except (AttributeError, OSError):
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except (AttributeError, OSError):
            pass


def user32():
    """Declare Win32 signatures once so 64-bit HWND pointers are never truncated."""
    global _U32_READY
    lib = ctypes.windll.user32
    if not _U32_READY:
        lib.SetWindowPos.argtypes = [
            ctypes.c_void_p,
            ctypes.c_void_p,
            ctypes.c_int,
            ctypes.c_int,
            ctypes.c_int,
            ctypes.c_int,
            ctypes.c_uint,
        ]
        lib.SetWindowPos.restype = ctypes.c_bool
        lib.GetWindowRect.argtypes = [ctypes.c_void_p, ctypes.POINTER(RECT)]
        lib.GetWindowRect.restype = ctypes.c_bool
        lib.GetWindowLongW.argtypes = [ctypes.c_void_p, ctypes.c_int]
        lib.GetWindowLongW.restype = ctypes.c_long
        lib.SetWindowLongW.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_long]
        lib.SetWindowLongW.restype = ctypes.c_long
        lib.ShowWindow.argtypes = [ctypes.c_void_p, ctypes.c_int]
        lib.ShowWindow.restype = ctypes.c_bool
        lib.PostMessageW.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_size_t, ctypes.c_ssize_t]
        lib.PostMessageW.restype = ctypes.c_bool
        lib.SendMessageW.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_size_t, ctypes.c_ssize_t]
        lib.SendMessageW.restype = ctypes.c_ssize_t
        lib.GetWindowTextW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_int]
        lib.GetWindowTextW.restype = ctypes.c_int
        lib.GetClassNameW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_int]
        lib.GetClassNameW.restype = ctypes.c_int
        lib.GetForegroundWindow.restype = ctypes.c_void_p
        lib.SetForegroundWindow.argtypes = [ctypes.c_void_p]
        lib.SetForegroundWindow.restype = ctypes.c_bool
        lib.BringWindowToTop.argtypes = [ctypes.c_void_p]
        lib.BringWindowToTop.restype = ctypes.c_bool
        lib.AllowSetForegroundWindow.argtypes = [ctypes.c_uint]
        lib.AllowSetForegroundWindow.restype = ctypes.c_bool
        lib.AttachThreadInput.argtypes = [ctypes.c_uint, ctypes.c_uint, ctypes.c_bool]
        lib.AttachThreadInput.restype = ctypes.c_bool
        lib.MonitorFromWindow.restype = ctypes.c_void_p
        lib.MonitorFromWindow.argtypes = [ctypes.c_void_p, ctypes.c_uint]
        lib.MonitorFromPoint.restype = ctypes.c_void_p
        lib.MonitorFromPoint.argtypes = [POINT, ctypes.c_uint]
        lib.GetMonitorInfoW.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
        lib.GetMonitorInfoW.restype = ctypes.c_bool
        lib.IsIconic.argtypes = [ctypes.c_void_p]
        lib.IsIconic.restype = ctypes.c_bool
        lib.IsWindow.argtypes = [ctypes.c_void_p]
        lib.IsWindow.restype = ctypes.c_bool
        lib.IsWindowVisible.argtypes = [ctypes.c_void_p]
        lib.IsWindowVisible.restype = ctypes.c_bool
        lib.GetWindowThreadProcessId.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint)]
        lib.GetWindowThreadProcessId.restype = ctypes.c_uint
        lib.LoadImageW.argtypes = [
            ctypes.c_void_p,
            ctypes.c_wchar_p,
            ctypes.c_uint,
            ctypes.c_int,
            ctypes.c_int,
            ctypes.c_uint,
        ]
        lib.LoadImageW.restype = ctypes.c_void_p
        lib.DestroyIcon.argtypes = [ctypes.c_void_p]
        lib.DestroyIcon.restype = ctypes.c_bool
        if hasattr(lib, "GetDpiForWindow"):
            lib.GetDpiForWindow.argtypes = [ctypes.c_void_p]
            lib.GetDpiForWindow.restype = ctypes.c_uint
        if hasattr(lib, "GetDpiForSystem"):
            lib.GetDpiForSystem.argtypes = []
            lib.GetDpiForSystem.restype = ctypes.c_uint
        _U32_READY = True
    return lib


def window_class(hwnd: int) -> str:
    if os.name != "nt" or not hwnd:
        return ""
    lib = user32()
    buffer = ctypes.create_unicode_buffer(256)
    lib.GetClassNameW(hwnd, buffer, 256)
    return buffer.value


def window_title(hwnd: int) -> str:
    if os.name != "nt" or not hwnd:
        return ""
    lib = user32()
    buffer = ctypes.create_unicode_buffer(512)
    lib.GetWindowTextW(hwnd, buffer, 512)
    return buffer.value


def work_area(hwnd: int | None = None) -> tuple[int, int, int, int]:
    """Return the work area (screen minus taskbar) for `hwnd` or the primary monitor."""
    if os.name != "nt":
        return 0, 0, 1920, 1032
    lib = user32()
    monitor = (
        lib.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST)
        if hwnd
        else lib.MonitorFromPoint(POINT(0, 0), MONITOR_DEFAULTTONEAREST)
    )
    info = MONITORINFO()
    info.cbSize = ctypes.sizeof(MONITORINFO)
    if not monitor or not lib.GetMonitorInfoW(monitor, ctypes.byref(info)):
        width = lib.GetSystemMetrics(0)
        height = lib.GetSystemMetrics(1)
        return 0, 0, width, max(480, height - 48)
    return info.rcWork.left, info.rcWork.top, info.rcWork.right, info.rcWork.bottom


def dpi_scale(hwnd: int | None = None) -> float:
    if os.name != "nt":
        return 1.0
    lib = user32()
    dpi = 96
    try:
        if hwnd and hasattr(lib, "GetDpiForWindow"):
            dpi = lib.GetDpiForWindow(hwnd) or 96
        elif hasattr(lib, "GetDpiForSystem"):
            dpi = lib.GetDpiForSystem() or 96
    except Exception:
        dpi = 96
    return max(1.0, (dpi or 96) / 96.0)


def find_edge_path(candidates: Sequence[str] | None = None) -> str:
    """Locate msedge.exe from known installation paths."""
    pool = EDGE_CANDIDATES if candidates is None else candidates
    for candidate in pool:
        if candidate and Path(candidate).is_file():
            return str(candidate)
    raise RuntimeError("找不到 Microsoft Edge：请确认 msedge.exe 已安装")


def split_command_args(command_line: str) -> list[str]:
    """Split a Windows command line into tokens while stripping outer quotes."""
    tokens = re.findall(r'(?:[^\s"]+|"[^"]*")+', str(command_line or ""))
    return [token.replace('"', "") for token in tokens]


def extract_user_data_dirs(command_line: str) -> list[str]:
    """Extract all `--user-data-dir` path arguments from a Windows command line."""
    args = split_command_args(command_line)
    values: list[str] = []
    for index, arg in enumerate(args):
        lowered = arg.casefold()
        if lowered.startswith("--user-data-dir="):
            values.append(arg.split("=", 1)[1])
        elif lowered == "--user-data-dir":
            values.append(args[index + 1] if index + 1 < len(args) else "")
    return values


def normalize_profile_path(profile: Path | str) -> str:
    """Normalize a profile directory path for exact case-insensitive comparison."""
    raw = str(profile or "").strip()
    if not raw:
        return ""
    text = raw
    if os.name == "nt":
        try:
            text = str(Path(raw).resolve())
        except OSError:
            text = raw
    norm = ntpath.normcase(ntpath.normpath(text))
    if len(norm) > 3 and norm.endswith("\\"):
        norm = norm.rstrip("\\")
    return norm


def process_matches_commandline(command_line: str, profile: Path | str) -> bool:
    """True only when the command line launches msedge with the exact `--user-data-dir`."""
    args = split_command_args(command_line)
    if not args:
        return False
    exe_head = " ".join(args[:2]).lower() if args[0].lower().endswith("cmd.exe") else args[0].lower()
    if "msedge" not in exe_head:
        return False
    wanted = normalize_profile_path(profile)
    if not wanted:
        return False
    values = extract_user_data_dirs(command_line)
    return bool(values) and all(value and normalize_profile_path(value) == wanted for value in values)


def parse_process_age_seconds(
    started_ms: Any = None,
    creation_date: Any = None,
    *,
    now: float | None = None,
) -> float | None:
    """Convert PowerShell/CIM process start timestamps into process age in seconds."""
    current = time.time() if now is None else float(now)
    if started_ms is not None and not isinstance(started_ms, bool):
        try:
            epoch = float(started_ms)
            if epoch > 1e11:
                epoch /= 1000.0
            return max(0.0, current - epoch)
        except (TypeError, ValueError):
            pass
    if isinstance(creation_date, dict):
        creation_date = creation_date.get("value")
    if creation_date is None:
        return None
    text = str(creation_date).strip()
    match = re.search(r"/Date\((\d+)\)/", text)
    if match:
        return max(0.0, current - int(match.group(1)) / 1000.0)
    digits = "".join(ch for ch in text[:14] if ch.isdigit())
    if len(digits) == 14:
        try:
            started = dt.datetime.strptime(digits, "%Y%m%d%H%M%S")
            return max(0.0, current - started.timestamp())
        except ValueError:
            return None
    return None


def processes_using_profile(profile: Path | str) -> list[dict[str, Any]] | None:
    """Inspect `msedge.exe` processes matching `profile`.

    Returns `None` if process inspection failed (so callers never mistake an
    unavailable query for "nothing running" or fall back to killing by name).
    """
    if os.name != "nt":
        return []
    script = (
        "$ErrorActionPreference='Stop'; "
        "Get-CimInstance Win32_Process -Filter \"Name='msedge.exe'\" | "
        "Select-Object ProcessId,ParentProcessId,CommandLine,"
        "@{Name='StartedMs';Expression={if($_.CreationDate){([DateTimeOffset]$_.CreationDate).ToUnixTimeMilliseconds()}else{$null}}} | "
        "ConvertTo-Json -Compress"
    )
    try:
        done = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            timeout=20,
            creationflags=CREATE_NO_WINDOW,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if done.returncode != 0:
        return None
    text = decode_console_output(done.stdout).strip()
    if not text:
        return []
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return None
    if isinstance(data, dict):
        data = [data]
    if not isinstance(data, list):
        return None
    matched: list[dict[str, Any]] = []
    for row in data:
        if not isinstance(row, dict):
            continue
        cmd = str(row.get("CommandLine") or "")
        if not process_matches_commandline(cmd, profile):
            continue
        raw_pid = row.get("ProcessId")
        try:
            pid = int(raw_pid)
        except (TypeError, ValueError):
            continue
        if pid <= 0:
            continue
        raw_parent = row.get("ParentProcessId")
        try:
            parent_pid = int(raw_parent or 0)
        except (TypeError, ValueError):
            parent_pid = 0
        matched.append(
            {
                "pid": pid,
                "parent_pid": parent_pid,
                "command_line": cmd,
                "age_seconds": parse_process_age_seconds(
                    row.get("StartedMs"),
                    row.get("CreationDate"),
                ),
            }
        )
    return matched


def pids_using_profile(profile: Path | str) -> list[int]:
    """Return PIDs of `msedge.exe` processes matching exact `--user-data-dir=profile`."""
    rows = processes_using_profile(profile)
    return [row["pid"] for row in rows] if rows else []


def kill_tree(pid: int) -> bool:
    """Terminate a verified PID and its child tree."""
    if not pid or int(pid) <= 0:
        return False
    try:
        done = subprocess.run(
            ["taskkill", "/PID", str(int(pid)), "/T", "/F"],
            creationflags=CREATE_NO_WINDOW,
            capture_output=True,
            timeout=15,
        )
        return done.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def find_windows_for_pids(
    pids: Iterable[int],
    *,
    min_width: int = 20,
    min_height: int = 20,
    allow_minimized: bool = False,
) -> list[int]:
    """Enumerate visible top-level Edge app windows owned by `pids`."""
    if os.name != "nt":
        return []
    targets = {int(pid) for pid in pids if pid and int(pid) > 0}
    if not targets:
        return []
    lib = user32()
    found: list[int] = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_ssize_t)
    def enum_window(handle, _param):
        pid = ctypes.c_uint()
        lib.GetWindowThreadProcessId(handle, ctypes.byref(pid))
        if pid.value not in targets or not lib.IsWindowVisible(handle):
            return True
        if window_class(handle) != WINDOW_CLASS:
            return True
        if allow_minimized and lib.IsIconic(handle):
            found.append(int(handle))
            return True
        rect = RECT()
        if not lib.GetWindowRect(handle, ctypes.byref(rect)):
            return True
        if rect.right - rect.left < min_width or rect.bottom - rect.top < min_height:
            return True
        found.append(int(handle))
        return True

    lib.EnumWindows(enum_window, 0)
    return found


def activate_window(hwnd: int) -> bool:
    """Restore if minimized and bring `hwnd` to the foreground."""
    if os.name != "nt" or not hwnd:
        return False
    lib = user32()
    if not lib.IsWindow(hwnd):
        return False
    if lib.IsIconic(hwnd):
        lib.ShowWindow(hwnd, SW_RESTORE)
    else:
        lib.ShowWindow(hwnd, SW_SHOW)
    try:
        lib.AllowSetForegroundWindow(ASFW_ANY)
    except Exception:
        pass
    lib.BringWindowToTop(hwnd)
    if lib.SetForegroundWindow(hwnd):
        return True
    foreground = lib.GetForegroundWindow()
    if foreground and foreground != hwnd:
        fg_tid = lib.GetWindowThreadProcessId(foreground, None)
        cur_tid = ctypes.windll.kernel32.GetCurrentThreadId()
        if fg_tid and cur_tid and fg_tid != cur_tid:
            attached = bool(lib.AttachThreadInput(cur_tid, fg_tid, True))
            try:
                lib.BringWindowToTop(hwnd)
                lib.SetForegroundWindow(hwnd)
            finally:
                if attached:
                    lib.AttachThreadInput(cur_tid, fg_tid, False)
    return bool(lib.IsWindow(hwnd))


def _set_window_property(store_ptr: Any, set_value_fn: Any, pid: int, value: str) -> None:
    if not value:
        return
    pkey = _PROPERTYKEY(_GUID.from_str(_APPUSERMODEL_FMTID), pid)
    pv = _PROPVARIANT_LPWSTR()
    pv.vt = VT_LPWSTR
    pv.pwszVal = value
    set_value_fn(store_ptr, ctypes.byref(pkey), ctypes.byref(pv))


def apply_window_icon_and_aumid(
    hwnd: int,
    *,
    icon_path: Path | None = None,
    app_id: str = "",
    display_name: str = "Agendum",
    relaunch_command: str = "",
) -> list[int]:
    """Set Win32 window icons and taskbar AppUserModelID properties on `hwnd`."""
    if os.name != "nt" or not hwnd:
        return []
    lib = user32()
    if not lib.IsWindow(hwnd):
        return []
    handles: list[int] = []
    if icon_path is not None and Path(icon_path).is_file():
        resolved = str(Path(icon_path).resolve())
        for icon_type, size in ((ICON_BIG, 32), (ICON_SMALL, 16)):
            try:
                hicon = lib.LoadImageW(None, resolved, IMAGE_ICON, size, size, LR_LOADFROMFILE)
                if hicon:
                    lib.SendMessageW(hwnd, WM_SETICON, icon_type, int(hicon))
                    handles.append(int(hicon))
            except Exception:
                continue
    if app_id:
        co_init_hr = None
        ole32 = None
        try:
            ole32 = ctypes.windll.ole32
            ole32.CoInitializeEx.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
            ole32.CoInitializeEx.restype = ctypes.HRESULT
            co_init_hr = ole32.CoInitializeEx(None, 2)  # COINIT_APARTMENTTHREADED
        except Exception:
            ole32 = None
        try:
            shell32 = ctypes.windll.shell32
            shell32.SHGetPropertyStoreForWindow.argtypes = [
                ctypes.c_void_p,
                ctypes.POINTER(_GUID),
                ctypes.POINTER(ctypes.c_void_p),
            ]
            shell32.SHGetPropertyStoreForWindow.restype = ctypes.HRESULT
            iid = _GUID.from_str(_IID_IPROPERTYSTORE)
            store_ptr = ctypes.c_void_p()
            hr = shell32.SHGetPropertyStoreForWindow(hwnd, ctypes.byref(iid), ctypes.byref(store_ptr))
            if hr == 0 and store_ptr.value:
                vtable = ctypes.cast(
                    ctypes.cast(store_ptr, ctypes.POINTER(ctypes.c_void_p)).contents,
                    ctypes.POINTER(ctypes.c_void_p),
                )
                set_value = ctypes.WINFUNCTYPE(
                    ctypes.HRESULT,
                    ctypes.c_void_p,
                    ctypes.POINTER(_PROPERTYKEY),
                    ctypes.POINTER(_PROPVARIANT_LPWSTR),
                )(vtable[6])
                commit = ctypes.WINFUNCTYPE(ctypes.HRESULT, ctypes.c_void_p)(vtable[7])
                release = ctypes.WINFUNCTYPE(ctypes.c_ulong, ctypes.c_void_p)(vtable[2])
                try:
                    _set_window_property(store_ptr, set_value, _PKEY_APPUSERMODEL_ID, app_id)
                    if icon_path is not None and Path(icon_path).is_file():
                        _set_window_property(store_ptr, set_value, _PKEY_RELAUNCH_ICON, str(Path(icon_path).resolve()))
                    if display_name:
                        _set_window_property(store_ptr, set_value, _PKEY_RELAUNCH_DISPLAY_NAME, display_name)
                    if relaunch_command:
                        _set_window_property(store_ptr, set_value, _PKEY_RELAUNCH_COMMAND, relaunch_command)
                    commit(store_ptr)
                finally:
                    release(store_ptr)
        except Exception:
            pass
        finally:
            if ole32 is not None and co_init_hr in (0, 1):
                try:
                    ole32.CoUninitialize()
                except Exception:
                    pass
    return handles


def destroy_icons(handles: Iterable[int]) -> None:
    if os.name != "nt":
        return
    lib = user32()
    for handle in handles:
        if handle:
            try:
                lib.DestroyIcon(int(handle))
            except Exception:
                pass


__all__ = [
    "ASFW_ANY",
    "CREATE_NO_WINDOW",
    "EDGE_CANDIDATES",
    "GWL_EXSTYLE",
    "GWL_STYLE",
    "GWLP_WNDPROC",
    "HTCAPTION",
    "HTCLIENT",
    "HWND_TOPMOST",
    "ICON_BIG",
    "ICON_SMALL",
    "IMAGE_ICON",
    "LR_LOADFROMFILE",
    "MONITOR_DEFAULTTONEAREST",
    "MONITORINFO",
    "POINT",
    "RECT",
    "SW_HIDE",
    "SW_RESTORE",
    "SW_SHOW",
    "SW_SHOWNOACTIVATE",
    "SWP_FRAMECHANGED",
    "SWP_NOACTIVATE",
    "SWP_NOMOVE",
    "SWP_NOSIZE",
    "SWP_NOZORDER",
    "SWP_SHOWWINDOW",
    "WINDOW_CLASS",
    "WM_CLOSE",
    "WM_NCHITTEST",
    "WM_SETICON",
    "WS_CAPTION",
    "WS_EX_NOACTIVATE",
    "WS_EX_TOOLWINDOW",
    "WS_EX_TOPMOST",
    "WS_MAXIMIZEBOX",
    "WS_MINIMIZEBOX",
    "WS_POPUP",
    "WS_SYSMENU",
    "WS_THICKFRAME",
    "activate_window",
    "apply_window_icon_and_aumid",
    "destroy_icons",
    "dpi_scale",
    "ensure_dpi_aware",
    "extract_user_data_dirs",
    "find_edge_path",
    "find_windows_for_pids",
    "kill_tree",
    "normalize_profile_path",
    "parse_process_age_seconds",
    "pids_using_profile",
    "process_matches_commandline",
    "processes_using_profile",
    "split_command_args",
    "user32",
    "window_class",
    "window_title",
    "work_area",
]
