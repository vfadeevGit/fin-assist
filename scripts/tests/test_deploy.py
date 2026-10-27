import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "deploy-production.sh"
DIGEST = "sha256:" + "a" * 64
TAG = "sha-" + "b" * 40


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "docker").mkdir()
        self.env_file = self.root / "docker/.env"
        self.original = 'POSTGRES_PASSWORD="keep this secret"\nAPP_TAG=old\nAPP_IMAGE=vfadeevdocker/fin-assist\n'
        self.env_file.write_text(self.original)
        (self.root / "docker/docker-compose.yml").write_text(
            'services:\n  finassist:\n    image: ${APP_IMAGE}:${APP_TAG}${APP_DIGEST:+@}${APP_DIGEST:-}\n')
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name, code in {
            "docker": '''#!/bin/bash
printf '%s\\n' "$*" >> "$CALLS"
if [[ " $* " == *" pull finassist "* && "${FAIL_PULL:-}" == 1 ]]; then exit 1; fi
''',
            "curl": '''#!/bin/bash
if [[ "${FAIL_HEALTH:-}" == 1 ]]; then exit 22; fi
''',
            "sleep": "#!/bin/bash\nexit 0\n",
        }.items():
            path = self.bin / name
            path.write_text(code)
            path.chmod(0o755)
        self.calls = self.root / "calls"
        self.env = {**os.environ, "PATH": f"{self.bin}:{os.environ['PATH']}", "CALLS": str(self.calls)}
        for name in ("APP_IMAGE", "APP_TAG", "APP_DIGEST"):
            self.env.pop(name, None)

    def run_deploy(self, tag=TAG, digest=DIGEST):
        return subprocess.run(
            ["bash", str(SCRIPT), str(self.root), "vfadeevdocker/fin-assist", tag, digest,
             "fin-assist.st-novator.ru"], env=self.env, text=True, capture_output=True)

    def test_success_persists_exact_image_and_preserves_secrets(self):
        result = self.run_deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        saved = self.env_file.read_text()
        self.assertIn('POSTGRES_PASSWORD="keep this secret"', saved)
        self.assertIn(f"APP_TAG={TAG}\n", saved)
        self.assertIn(f"APP_DIGEST={DIGEST}\n", saved)
        self.assertEqual(self.env_file.with_name(".env.previous").read_text(), self.original)
        calls = self.calls.read_text()
        self.assertIn("up -d --no-deps finassist", calls)
        self.assertIn("exec -T nginx nginx -s reload", calls)
        self.assertNotIn("down", calls)

    def test_failed_pull_does_not_restart_or_save(self):
        self.env["FAIL_PULL"] = "1"
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.env_file.read_text(), self.original)
        self.assertTrue(self.calls.exists(), result.stderr)
        self.assertNotIn("up -d", self.calls.read_text())

    def test_failed_health_check_does_not_save_success(self):
        self.env["FAIL_HEALTH"] = "1"
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.env_file.read_text(), self.original)
        self.assertIn("health check", result.stderr.lower())

    def test_invalid_image_inputs_do_not_contact_docker(self):
        for tag, digest in [("latest;touch bad", DIGEST), (TAG, "sha256:invalid")]:
            with self.subTest(tag=tag, digest=digest):
                result = self.run_deploy(tag, digest)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.calls.exists())

    def test_missing_environment_fails_before_docker(self):
        self.env_file.unlink()
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.calls.exists())


if __name__ == "__main__":
    unittest.main()
