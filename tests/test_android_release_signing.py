"""Android 发布签名：密码不对必须在打包前就红，而不是在 packageRelease 甩一句 KeytoolException。

beta.19 的发布卡在这里：keystore 是 PKCS12，keytool 生成时直接忽略了 -keypass（它会提示
「PKCS12 密钥库不支持独立的密钥密码」），私钥实际用 storePassword 加密；keystore.properties
里却另填了一个 keyPassword。keytool 查库时偷着用 storePassword，所以本地看着没问题；Gradle 把
keyPassword 原样交给 KeyStore.getKey()，于是报
「Get Key failed: Given final block not properly padded」。

三条断言锁住这次修复：
  1. keyPassword 缺省回落到 storePassword —— 一个 PKCS12 库只留一个密码；
  2. 打包前先跑 verifyReleaseSigning，且 assembleRelease 依赖它；
  3. CI 只接受 apksigner 验过名的 APK，未签名包不许进 release。
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "android" / "app" / "build.gradle"
WORKFLOW = ROOT / ".github" / "workflows" / "tests.yml"


def android_job() -> str:
    """只取 tests.yml 里 android 这一个 job 的正文，避免串到别的 job 上。"""
    text = WORKFLOW.read_text(encoding="utf-8")
    start = text.index("\n  android:")
    end = text.index("\n  ci-green:")
    return text[start:end]


class KeyPasswordFallbackTest(unittest.TestCase):
    def setUp(self) -> None:
        self.text = GRADLE.read_text(encoding="utf-8")

    def test_key_password_falls_back_to_store_password(self):
        # 缺省值必须是 storePassword，不能留空、不能是第三份密码。
        self.assertRegex(
            self.text,
            r"releaseKeyPassword\s*=\s*signingValue\('keyPassword'\)\s*\?:\s*releaseStorePassword",
        )

    def test_signing_config_uses_the_fallback_value(self):
        # signingConfig 里必须用算好的 releaseKeyPassword，而不是原始属性值。
        self.assertRegex(self.text, r"keyPassword\s+releaseKeyPassword")
        self.assertNotRegex(self.text, r"keyPassword\s+signingValue\('keyPassword'\)")


class VerifyReleaseSigningTaskTest(unittest.TestCase):
    def setUp(self) -> None:
        self.text = GRADLE.read_text(encoding="utf-8")

    def test_task_exists_and_really_decrypts_the_private_key(self):
        self.assertIn("tasks.register('verifyReleaseSigning')", self.text)
        # 只看文件在不在不算验证：必须真的用 keyPassword 解一次私钥。
        self.assertRegex(self.text, r"ks\.getKey\(alias,\s*releaseKeyPassword\.toCharArray\(\)\)")

    def test_assemble_release_depends_on_the_check(self):
        self.assertRegex(
            self.text,
            r"tasks\.named\('assembleRelease'\)\s*\{\s*dependsOn\s+tasks\.named\('verifyReleaseSigning'\)",
        )


class AndroidJobOrderTest(unittest.TestCase):
    def setUp(self) -> None:
        self.job = android_job()

    def test_signing_is_verified_before_the_apk_is_built(self):
        verify = self.job.find("verifyReleaseSigning")
        build = self.job.find("- name: Build Android APK")
        self.assertNotEqual(verify, -1, "android job 必须先跑 verifyReleaseSigning")
        self.assertNotEqual(build, -1)
        self.assertLess(verify, build, "签名校验要排在打包之前，否则等于没查")

    def test_prepare_step_does_not_require_a_second_password(self):
        # PKCS12 只有一把密码：ANDROID_KEY_PASSWORD 缺席时沿用 storePassword，
        # 不许把空值或猜来的值写进 properties。
        self.assertIn("for name in KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS;", self.job)
        self.assertIn('if [ -z "$KEY_PASSWORD" ]; then', self.job)
        self.assertIn('KEY_PASSWORD="$KEYSTORE_PASSWORD"', self.job)

    def test_staged_apk_must_be_signed(self):
        # 未签名包文件名带 unsigned，绝不能当发布产物复制出去。
        self.assertIn('! -name "*unsigned*"', self.job)
        # 复制成功不等于签上了名：apksigner 验过才算。
        self.assertIn('"$apksigner" verify', self.job)


if __name__ == "__main__":
    unittest.main()
