import unittest
from unittest.mock import patch

from noticehub import update_sources


class UpdateSourcesTests(unittest.TestCase):
    def test_default_source_uses_fork_and_its_default_branch(self):
        with patch.dict("os.environ", {}, clear=True):
            sources = update_sources.sources()
        self.assertEqual([source["id"] for source in sources], ["github"])
        self.assertEqual(sources[0]["repo"], "YANGAIJUNZHE/notice-hub")
        self.assertEqual(sources[0]["api_root"], "https://api.github.com")
        self.assertEqual(
            sources[0]["raw_base"],
            "https://raw.githubusercontent.com/YANGAIJUNZHE/notice-hub/codex/notice-hub-custom",
        )

    def test_empty_environment_and_settings_leave_mirror_disabled(self):
        settings = type("Settings", (), {"update_mirror": ""})()
        with patch.dict("os.environ", {}, clear=True):
            sources = update_sources.sources(settings)
        self.assertEqual([source["id"] for source in sources], ["github"])

    def test_mirror_is_second_and_urls_use_its_repo(self):
        with patch.dict("os.environ", {"QQ_DIGEST_UPDATE_MIRROR": "ExpertKT/notice-hub"}, clear=True):
            sources = update_sources.sources()
        self.assertEqual([source["id"] for source in sources], ["github", "gitee"])
        mirror = sources[1]
        self.assertEqual(mirror["repo"], "ExpertKT/notice-hub")
        self.assertEqual(mirror["api_root"], "https://gitee.com/api/v5")
        self.assertEqual(mirror["raw_base"], "https://gitee.com/ExpertKT/notice-hub/raw/master")
        self.assertTrue(update_sources.announcements_url(mirror).endswith("/announcements.json"))
        self.assertTrue(update_sources.manifest_url(mirror).endswith("/update-manifest.json"))

    def test_settings_value_precedes_environment(self):
        settings = type("Settings", (), {"update_mirror": "owner/settings"})()
        with patch.dict("os.environ", {"QQ_DIGEST_UPDATE_MIRROR": "owner/environment"}, clear=True):
            self.assertEqual(update_sources.mirror_repo(settings), "owner/settings")

    def test_invalid_settings_falls_back_to_environment_and_invalid_env_is_empty(self):
        settings = type("Settings", (), {"update_mirror": "invalid"})()
        with patch.dict("os.environ", {"QQ_DIGEST_UPDATE_MIRROR": "owner/environment"}, clear=True):
            self.assertEqual(update_sources.mirror_repo(settings), "owner/environment")
        with patch.dict("os.environ", {"QQ_DIGEST_UPDATE_MIRROR": "invalid"}, clear=True):
            self.assertEqual(update_sources.mirror_repo(), "")


if __name__ == "__main__":
    unittest.main()
