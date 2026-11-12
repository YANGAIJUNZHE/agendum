"""独立的 v1 日程接口。设备凭据仅在此模块生效，不进入电脑管理接口。"""
from __future__ import annotations

import logging
import urllib.parse
from typing import Any

LOGGER = logging.getLogger(__name__)


def _admin(handler: Any) -> bool:
    # Tunnel 的对端也是环回地址，不能把空管理员令牌当作公网管理员身份。
    if not getattr(handler.server, "token", ""):
        host = urllib.parse.urlsplit("//" + str(handler.headers.get("Host") or "")).hostname
        return host in {"127.0.0.1", "localhost", "::1"} and not handler.headers.get("CF-Connecting-IP") and handler._authorized()
    return handler._authorized()


def dispatch(handler: Any, method: str, path: str) -> bool:
    if path != "/api/v1" and not path.startswith("/api/v1/"):
        return False
    if handler._auth_source().startswith("cf:") and handler.headers.get("X-Forwarded-Proto", "").lower() != "https":
        handler._json(426, {"ok": False, "error": "手机同步必须使用 HTTPS 地址"})
        return True
    store = handler.store
    try:
        if method == "GET" and path == "/api/v1/info":
            handler._json(200, {"ok": True, **store.sync_info()})
            return True
        body = handler._json_body() if method == "POST" else {}
        if not isinstance(body, dict):
            raise ValueError("请求体必须是对象")
        if method == "POST" and path == "/api/v1/pair/start":
            source = handler._auth_source()
            handler._json(200, store.sync_pair_start(str(body.get("name") or "安卓手机"), source))
            return True
        if method == "POST" and path == "/api/v1/pair/exchange":
            ident, secret = body.get("id"), body.get("secret")
            if not isinstance(ident, str) or not isinstance(secret, str) or len(ident) > 100 or len(secret) > 100:
                raise ValueError("无效配对信息")
            handler._json(200, store.sync_pair_exchange(ident, secret))
            return True
        authorization = str(handler.headers.get("Authorization") or "")
        device = store.sync_device(authorization[7:]) if authorization.startswith("Bearer ") else None
        if authorization and not device:
            handler._authentication_failed()
            handler._json(401, {"ok": False, "code": "pair_required", "error": "配对已失效，请重新配对；本地编辑仍然保留"})
            return True
        is_admin = not device and _admin(handler)
        if not device and not is_admin:
            handler._json(401, {"ok": False, "error": "需要配对或电脑管理员会话"})
            return True
        if path.startswith("/api/v1/admin/"):
            if not is_admin:
                handler._json(403, {"ok": False, "error": "此操作仅限电脑端管理员"})
                return True
            if method == "GET" and path == "/api/v1/admin/devices":
                handler._json(200, {"ok": True, "devices": store.sync_devices(), "pending": store.sync_pair_pending(),
                                    "base": getattr(handler.server.settings, "mobile_base_url", "")})
                return True
            if method == "POST" and path == "/api/v1/admin/devices/revoke":
                store.sync_revoke(str(body.get("device_id") or ""))
                handler._json(200, {"ok": True})
                return True
            if method == "POST" and path == "/api/v1/admin/devices/approve":
                store.sync_pair_approve(str(body.get("id") or ""))
                handler._json(200, {"ok": True})
                return True
            if method == "POST" and path == "/api/v1/admin/devices/ticket":
                base = str(getattr(handler.server.settings, "mobile_base_url", "") or body.get("base") or "").rstrip("/")
                parsed = urllib.parse.urlsplit(base)
                if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
                    raise ValueError("手机入口必须是有效的 HTTPS 地址")
                ticket = store.sync_pair_start("扫码连接的手机", "desktop", approved=True)
                url = "agendum://connect?" + urllib.parse.urlencode({"base": base, "pair_id": ticket["id"], "pair_secret": ticket["secret"]})
                # 返回 PNG 数据地址；票据不进入 URL 查询串或访问日志。
                import base64
                from .qr import png_bytes
                handler._json(200, {"ok": True, "url": url, "qr": "data:image/png;base64," + base64.b64encode(png_bytes(url)).decode("ascii"), "expires_in": 300})
                return True
        if method == "GET" and path in {"/api/v1/sync/bootstrap", "/api/v1/sync/changes"}:
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(handler.path).query, max_num_fields=10)
            def get(name: str, default: str = "") -> str:
                return query.get(name, [default])[0]
            if get("epoch") != store.sync_epoch or get("server_id") != store.sync_info()["server_id"]:
                handler._json(200, {"ok": False, "code": "reset_required", **store.sync_info()})
                return True
            result = store.sync_page(after=int(get("after", "0")), limit=int(get("limit", "200")),
                                     upper=int(get("upper")) if get("upper") else None,
                                     bootstrap=path.endswith("bootstrap"), key=get("key"))
            handler._json(200, result)
            return True
        if method == "GET" and path == "/api/v1/tasks/lookup":
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(handler.path).query, max_num_fields=3)
            task = store.sync_task(int(query.get("id", ["0"])[0]))
            handler._json(200 if task else 404, {"ok": bool(task), "task": task})
            return True
        if method == "POST" and path == "/api/v1/mutations":
            handler._json(200, store.sync_mutate(body, device or "desktop"))
            return True
        handler._json(404, {"ok": False, "error": "接口不存在"})
    except (ValueError, TypeError, KeyError, OverflowError) as error:
        handler._json(400, {"ok": False, "error": str(error) or "无效请求"})
    except Exception:
        LOGGER.exception("日程同步请求失败")
        handler._json(500, {"ok": False, "error": "同步暂未完成，请稍后重试；本地编辑不会丢弃"})
    return True
