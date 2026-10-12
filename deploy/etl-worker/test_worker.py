import os
import sys
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(__file__))
import worker


class WorkerTest(unittest.TestCase):
    def test_idempotent_submission_and_single_queue(self):
        w = worker.Worker()
        job = {"workerRunId": "same", "command": [sys.executable, "-c", "print('ok')"]}
        first = w.submit(job)
        second = w.submit(job)
        self.assertIs(first, second)
        deadline = time.time() + 3
        while time.time() < deadline and first.status in {"QUEUED", "RUNNING"}:
            time.sleep(.02)
        self.assertEqual(first.status, "SUCCESS")
        self.assertIn("ok", "\n".join(first.log_lines))

    def test_bearer_auth(self):
        self.assertEqual(worker.Handler._auth.__name__, "_auth")

    def test_identifier_rejects_injection(self):
        with self.assertRaises(ValueError):
            worker.identifier("users; DROP TABLE users")

    def test_named_parameter_conversion(self):
        converted = worker.PARAM.sub(lambda m: "%(" + m.group(1) + ")s", "SELECT * FROM t WHERE city=:city")
        self.assertEqual(converted, "SELECT * FROM t WHERE city=%(city)s")


if __name__ == "__main__":
    unittest.main()
