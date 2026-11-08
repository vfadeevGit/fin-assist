import importlib.util
import io
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch


class SyncOpenAiSecretsTest(unittest.TestCase):
    def test_uploads_only_ai_values_via_stdin(self):
        path = Path(__file__).resolve().parents[1] / 'sync-openai-secrets.py'
        spec = importlib.util.spec_from_file_location('sync_ai', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        values = dict(OPENAI_BASE_URL='https://ai2mee.ru/api', OPENAI_MODEL='gpt-5.2', OPENAI_API_KEY='test-key')
        rendered = json.dumps({'services': {'finassist': {'environment': values}}})
        with patch('sys.argv', ['sync', '--repo', 'example/repo']), \
                patch.object(module.subprocess, 'run', side_effect=[
                    subprocess.CompletedProcess([], 0, rendered),
                    *[subprocess.CompletedProcess([], 0) for _ in range(3)]]) as run, \
                patch('sys.stdout', new_callable=io.StringIO) as output:
            module.main()
            self.assertEqual(run.call_count, 4)
            for call in run.call_args_list[1:]:
                command = call.args[0]
                key = command[3]
                self.assertEqual(command, ['gh', 'secret', 'set', key, '--env', 'production', '--repo', 'example/repo'])
                self.assertEqual(call.kwargs['input'], values[key])
                self.assertNotIn('test-key', str(command))
            self.assertNotIn('test-key', output.getvalue())
