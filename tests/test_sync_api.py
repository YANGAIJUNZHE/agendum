"""v1 HTTP 契约：真实请求验证设备隔离、配对、分页和缓存头。"""
import json
import tempfile
import unittest
import urllib.request
import urllib.error
import uuid
from types import SimpleNamespace
from pathlib import Path

from agendum.config import Settings
from agendum.store import Store
from agendum.webapp import TaskWebServer
from agendum import webapp


class SyncApiTest(unittest.TestCase):
    def setUp(self):
        webapp._reset_auth_limits()
        self.addCleanup(webapp._reset_auth_limits)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(Path(self.tmp.name) / "data.sqlite3")
        self.task_id = self.store.upsert_task(task_key="one", summary="电脑生成的待办")
        self.server = TaskWebServer(Settings(web_host="127.0.0.1", web_port=0, web_token="admin-secret", data_dir=Path(self.tmp.name)), self.store)
        self.assertTrue(self.server.start())
        self.addCleanup(self.server.stop)
        self.base = "http://127.0.0.1:%d" % self.server.server.server_address[1]

    def call(self, path, body=None, token="", admin=False, extra_headers=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if admin:
            headers["X-Token"] = "admin-secret"
        headers.update(extra_headers or {})
        request = urllib.request.Request(self.base + path, data=json.dumps(body).encode() if body is not None else None, headers=headers)
        try:
            response = urllib.request.urlopen(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.code, dict(response.headers), json.loads(response.read())

    def pair(self):
        code, _, ticket = self.call("/api/v1/pair/start", {"name": "手机"})
        self.assertEqual(code, 200)
        self.assertEqual(self.call("/api/v1/admin/devices/approve", {"id": ticket["id"]}, admin=True)[0], 200)
        return self.call("/api/v1/pair/exchange", {"id": ticket["id"], "secret": ticket["secret"]})[2]

    def test_device_can_sync_and_manage_but_cannot_reach_admin(self):
        pair = self.pair()
        token = pair["token"]
        info = self.call("/api/v1/info")[2]
        path = "/api/v1/sync/bootstrap?server_id=%s&epoch=%s" % (info["server_id"], info["epoch"])
        code, headers, result = self.call(path, token=token)
        self.assertEqual(code, 200)
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertEqual(result["items"][0]["summary"], "电脑生成的待办")
        task = result["items"][0]
        request = {**info, "type": "update", "operation_id": str(uuid.uuid4()), "uuid": task["uuid"], "base_version": task["version"],
                   "base": {"notes": ""}, "changes": {"notes": "手机修改"}}
        self.assertTrue(self.call("/api/v1/mutations", request, token=token)[2]["ok"])
        self.assertEqual(self.store.sync_task(self.task_id)["notes"], "手机修改")
        self.assertEqual(self.call("/api/v1/admin/devices", token=token)[0], 403)
        self.assertEqual(self.call("/api/settings", token=token)[0], 401)
        self.assertEqual(self.call("/api/napcat/start", {}, token=token)[0], 401)

    def test_revocation_does_not_accept_old_token(self):
        pair = self.pair()
        self.call("/api/v1/admin/devices/revoke", {"device_id": pair["device_id"]}, admin=True)
        status, _, data = self.call("/api/v1/tasks/lookup?id=%s" % self.task_id, token=pair["token"])
        self.assertEqual(status, 401)
        self.assertEqual(data["code"], "pair_required")

    def test_no_auth_unknown_routes_bad_body_and_stale_epoch(self):
        self.assertEqual(self.call("/api/v1/sync/changes")[0], 401)
        self.assertEqual(self.call("/api/v1/unknown", admin=True)[0], 404)
        self.assertEqual(self.call("/api/v1/mutations", [], admin=True)[0], 400)
        status, _, result = self.call("/api/v1/sync/changes?epoch=old", admin=True)
        self.assertEqual(status, 200)
        self.assertEqual(result["code"], "reset_required")

    def test_qr_contains_one_time_ticket_not_admin_secret(self):
        status, _, result = self.call("/api/v1/admin/devices/ticket", {"base": "https://schedule.example.com"}, admin=True)
        self.assertEqual(status, 200)
        self.assertNotIn("admin-secret", result["url"])
        self.assertIn("pair_secret=", result["url"])
        self.assertTrue(result["qr"].startswith("data:image/png;base64,"))

    def test_attacker_lockout_does_not_block_other_phone_or_local_admin(self):
        pair = self.pair()
        attacker = {"CF-Connecting-IP": "198.51.100.10", "X-Forwarded-Proto": "https"}
        phone = {"CF-Connecting-IP": "198.51.100.11", "X-Forwarded-Proto": "https"}
        for _ in range(webapp.AUTH_FAILURE_LIMIT + 1):
            self.assertEqual(self.call("/api/v1/sync/changes", extra_headers=attacker)[0], 401)
        self.assertEqual(self.call("/api/v1/info", extra_headers=attacker)[0], 429)
        self.assertEqual(self.call("/api/v1/tasks/lookup?id=%s" % self.task_id, token=pair["token"], extra_headers=phone)[0], 200)
        self.assertEqual(self.call("/api/v1/admin/devices", admin=True)[0], 200)
        task = self.store.sync_task(self.task_id)
        request = dict(server_id=pair["server_id"], epoch=pair["epoch"], operation_id=str(uuid.uuid4()),
                       uuid=task["uuid"], type="update", base_version=task["version"],
                       base={"notes": task["notes"]}, changes={"notes": "限流隔离后仍可保存"})
        self.assertTrue(self.call("/api/v1/mutations", request, token=pair["token"], extra_headers=phone)[2]["ok"])
        self.assertEqual(self.store.sync_task(self.task_id)["notes"], "限流隔离后仍可保存")

    def test_bad_bearer_tokens_are_also_throttled(self):
        headers = {"CF-Connecting-IP": "198.51.100.12", "X-Forwarded-Proto": "https"}
        for _ in range(webapp.AUTH_FAILURE_LIMIT + 1):
            self.assertEqual(self.call("/api/v1/sync/changes", token="invalid", extra_headers=headers)[0], 401)
        self.assertEqual(self.call("/api/v1/sync/changes", token="invalid", extra_headers=headers)[0], 429)

    def test_proxy_plain_http_is_rejected_before_pairing_or_mutation(self):
        headers = {"CF-Connecting-IP": "198.51.100.13", "X-Forwarded-Proto": "http"}
        self.assertEqual(self.call("/api/v1/info", extra_headers=headers)[0], 426)
        self.assertEqual(self.call("/api/v1/pair/start", {"name": "不应创建"}, extra_headers=headers)[0], 426)
        self.assertEqual(self.store.sync_pair_pending(), [])
        self.assertEqual(self.call("/api/v1/mutations", {}, admin=True, extra_headers=headers)[0], 426)
        self.assertEqual(self.call("/api/v1/admin/devices/ticket", {"base": "http://example.com"}, admin=True)[0], 400)

    def test_forwarded_headers_only_trusted_from_loopback_and_separate_namespaces(self):
        def source(peer, forwarded):
            handler = SimpleNamespace(_client_address=lambda: peer, headers={"CF-Connecting-IP": forwarded})
            return webapp._Handler._auth_source(handler)
        self.assertEqual(source("192.168.1.2", "198.51.100.1"), "192.168.1.2")
        self.assertEqual(source("127.0.0.1", "127.0.0.1"), "cf:127.0.0.1")
        self.assertEqual(source("127.0.0.1", "bad"), "cf:invalid")
        self.assertEqual(source("127.0.0.1", "::ffff:198.51.100.1"), "cf:198.51.100.1")


if __name__ == "__main__":
    unittest.main()
