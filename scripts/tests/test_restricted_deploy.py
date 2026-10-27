import importlib.util
import fcntl
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import subprocess


PATH = Path(__file__).resolve().parents[1] / "restricted-deploy.py"
TAG = "sha-" + "b" * 40
DIGEST = "sha256:" + "a" * 64


class RestrictedDeploymentTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(PATH.exists(), "Restricted deployment entrypoint is missing")
        spec = importlib.util.spec_from_file_location("restricted_deploy", PATH)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def test_rejects_commands_paths_and_malformed_images(self):
        for args in ([], ["--check", "extra"], [TAG, DIGEST, "/tmp/other"],
                     ["latest", DIGEST], [TAG, DIGEST + ";id"],
                     [TAG, "other/repo@" + DIGEST]):
            with self.subTest(args=args), patch.object(self.module.subprocess, "run") as run:
                self.assertEqual(self.module.main(args), 2)
                run.assert_not_called()

    def test_non_root_cannot_execute_deployment(self):
        with patch.object(self.module.os, "geteuid", return_value=1000):
            self.assertEqual(self.module.main([TAG, DIGEST]), 1)

    def test_dispatch_uses_fixed_target_and_clean_environment(self):
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(self.module, "LOCK_PATH", str(Path(tmp) / "lock")), \
                patch.object(self.module.os, "geteuid", return_value=0), \
                patch.dict(os.environ, {"BASH_ENV": "/tmp/evil", "DOCKER_HOST": "tcp://evil"}), \
                patch.object(self.module.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as run:
            self.assertEqual(self.module.main([TAG, DIGEST]), 0)
            args, kwargs = run.call_args
            self.assertEqual(args[0], ["/bin/bash", "/usr/local/lib/finassist/deploy-production.sh",
                                      "/root", "vfadeevdocker/fin-assist", TAG, DIGEST,
                                      "fin-assist.st-novator.ru"])
            self.assertEqual(kwargs["env"], {"HOME": "/root", "PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8"})
            self.assertEqual(kwargs["stdin"], subprocess.DEVNULL)

    def test_deployment_failure_is_returned(self):
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(self.module, "LOCK_PATH", str(Path(tmp) / "lock")), \
                patch.object(self.module.os, "geteuid", return_value=0), \
                patch.object(self.module.subprocess, "run", return_value=subprocess.CompletedProcess([], 17)):
            self.assertEqual(self.module.main([TAG, DIGEST]), 17)

    def test_concurrent_deployment_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            lock_path = str(Path(tmp) / "lock")
            with open(lock_path, "a") as lock, \
                    patch.object(self.module, "LOCK_PATH", lock_path), \
                    patch.object(self.module.os, "geteuid", return_value=0), \
                    patch.object(self.module.subprocess, "run") as run:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.assertEqual(self.module.main([TAG, DIGEST]), 1)
                run.assert_not_called()
