"""Small single-process ETL worker.

The HTTP contract is intentionally DataX-shaped: the server submits a validated
job document, while the execution backend can later be replaced with a DataX
subprocess without changing the server protocol. PostgreSQL is supported for
the local MVP so the worker can be exercised without shipping the DataX bundle.
"""
from __future__ import annotations

import json
import logging
import os
import queue
import re
import subprocess
import threading
import time
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

import psycopg

TOKEN = os.environ.get("DAASPRO_WORKER_TOKEN", "dev-worker-token")
HOST = os.environ.get("DAASPRO_WORKER_HOST", "0.0.0.0")
PORT = int(os.environ.get("DAASPRO_WORKER_PORT", "8090"))
LOG = logging.getLogger("etl-worker")
IDENT = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
PARAM = re.compile(r"(?<!:):([A-Za-z_][A-Za-z0-9_]*)")


def identifier(value: str) -> str:
    if not value or not IDENT.fullmatch(value):
        raise ValueError("invalid identifier")
    return '"' + value.replace('"', '""') + '"'


class Run:
    def __init__(self, run_id: str, job: dict[str, Any]):
        self.id = run_id
        self.job = job
        self.status = "QUEUED"
        self.error = None
        self.read_rows = 0
        self.written_rows = 0
        self.started_at = None
        self.finished_at = None
        self.cancel = threading.Event()
        self.log_lines: list[str] = []

    def view(self) -> dict[str, Any]:
        return {
            "workerRunId": self.id, "status": self.status, "error": self.error,
            "readRows": self.read_rows, "writtenRows": self.written_rows,
            "startedAt": self.started_at, "finishedAt": self.finished_at,
            "logs": "\n".join(self.log_lines)[-10000:],
        }


class Worker:
    def __init__(self) -> None:
        self.runs: dict[str, Run] = {}
        self.lock = threading.RLock()
        self.jobs: queue.Queue[Run] = queue.Queue()
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def submit(self, job: dict[str, Any]) -> Run:
        run_id = str(job.get("workerRunId") or uuid.uuid4())
        with self.lock:
            old = self.runs.get(run_id)
            if old:
                return old
            run = Run(run_id, job)
            self.runs[run_id] = run
            self.jobs.put(run)
            return run

    def get(self, run_id: str) -> Run | None:
        with self.lock:
            return self.runs.get(run_id)

    def cancel(self, run_id: str) -> Run | None:
        run = self.get(run_id)
        if run:
            run.cancel.set()
        return run

    def _loop(self) -> None:
        while True:
            run = self.jobs.get()
            if run.cancel.is_set():
                run.status = "CANCELED"
                continue
            run.status = "RUNNING"
            run.started_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            try:
                self._execute(run)
                if run.cancel.is_set():
                    run.status = "CANCELED"
                else:
                    run.status = "SUCCESS"
            except Exception as exc:  # never leak credentials into the log
                run.status = "FAILED"
                run.error = str(exc).replace(str(run.job.get("source", {}).get("password", "")), "***")
                LOG.exception("run %s failed", run.id)
            finally:
                run.finished_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

    def _execute(self, run: Run) -> None:
        command = run.job.get("command")
        if command:
            self._execute_command(run, command)
            return
        for mapping in run.job.get("mappings", []):
            if run.cancel.is_set():
                return
            self._execute_mapping(run, mapping)

    def _execute_command(self, run: Run, command: list[str]) -> None:
        if not isinstance(command, list) or not command:
            raise ValueError("command must be a non-empty list")
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as proc:
            while True:
                if run.cancel.is_set() and proc.poll() is None:
                    proc.terminate()
                    try:
                        proc.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                    return
                line = proc.stdout.readline() if proc.stdout else ""
                if line:
                    run.log_lines.append(line.rstrip()[:1000])
                if proc.poll() is not None:
                    break
            if proc.returncode:
                raise RuntimeError(f"worker command failed: {proc.returncode}")

    def _connect(self, config: dict[str, Any]):
        if config.get("type") != "postgresql":
            raise ValueError("当前 Worker 实际执行器仅支持 PostgreSQL")
        return psycopg.connect(host=config["host"], port=config["port"], dbname=config["databaseName"],
                               user=config["username"], password=config["password"], autocommit=True)

    def _execute_mapping(self, run: Run, mapping: dict[str, Any]) -> None:
        source = mapping["source"]
        target = mapping["target"]
        fields = [f for f in mapping.get("fields", []) if f.get("enabled", True)]
        if not fields:
            raise ValueError("至少选择一个字段")
        source_schema = identifier(source.get("schema") or "public")
        target_schema = identifier(target.get("schema") or "public")
        source_table = identifier(source["table"])
        target_table = identifier(target["table"])
        select = mapping.get("select") or f"SELECT {', '.join(identifier(f['source']) for f in fields)} FROM {source_schema}.{source_table}"
        if not select.lstrip().upper().startswith(("SELECT", "WITH")) or ";" in select:
            raise ValueError("仅允许单条只读 SELECT")
        source_conn = self._connect(source)
        target_conn = self._connect(target)
        try:
            with source_conn.cursor() as src, target_conn.cursor() as dst:
                # Platform SQL uses named parameters (:city); psycopg uses %(city)s.
                params = mapping.get("parameters") or {}
                executable_select = PARAM.sub(lambda match: "%(" + match.group(1) + ")s", select)
                src.execute(executable_select, params)
                target_cols = ", ".join(identifier(f["target"]) for f in fields)
                if mapping.get("targetMode", "CREATE") == "CREATE":
                    defs = ", ".join(f"{identifier(f['target'])} {self._type(f.get('type'))}" for f in fields)
                    dst.execute(f"CREATE TABLE IF NOT EXISTS {target_schema}.{target_table} ({defs})")
                mode = mapping.get("writeMode", "APPEND")
                if mode == "OVERWRITE":
                    dst.execute(f"TRUNCATE TABLE {target_schema}.{target_table}")
                keys = mapping.get("upsertKeys") or []
                for row in src:
                    if run.cancel.is_set():
                        return
                    values = tuple(row)
                    if mode == "UPSERT":
                        if not keys:
                            raise ValueError("更新插入必须配置键字段")
                        placeholders = ", ".join(["%s"] * len(values))
                        updates = ", ".join(f"{identifier(f['target'])}=EXCLUDED.{identifier(f['target'])}" for f in fields if f["target"] not in keys)
                        stmt = f"INSERT INTO {target_schema}.{target_table} ({target_cols}) VALUES ({placeholders}) ON CONFLICT ({', '.join(identifier(k) for k in keys)}) DO UPDATE SET {updates or identifier(keys[0]) + '=EXCLUDED.' + identifier(keys[0])}"
                    else:
                        stmt = f"INSERT INTO {target_schema}.{target_table} ({target_cols}) VALUES ({', '.join(['%s'] * len(values))})"
                    dst.execute(stmt, values)
                    run.read_rows += 1
                    run.written_rows += 1
        finally:
            source_conn.close()
            target_conn.close()

    @staticmethod
    def _type(value: str | None) -> str:
        return {"bigint": "BIGINT", "numeric": "NUMERIC", "timestamp": "TIMESTAMP", "date": "DATE", "boolean": "BOOLEAN", "text": "TEXT"}.get(value or "text", "TEXT")


WORKER = Worker()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: Any) -> None:
        LOG.info(fmt, *args)

    def _auth(self) -> bool:
        return self.headers.get("Authorization", "") == f"Bearer {TOKEN}"

    def _json(self, status: int, body: dict[str, Any]) -> None:
        raw = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)

    def do_GET(self) -> None:
        if self.path == "/internal/health":
            self._json(200, {"status": "UP", "worker": "etl-worker"}); return
        if not self._auth(): self._json(401, {"message": "unauthorized"}); return
        prefix = "/internal/runs/"
        if self.path.startswith(prefix):
            run = WORKER.get(self.path[len(prefix):])
            self._json(200 if run else 404, run.view() if run else {"message": "run not found"}); return
        self._json(404, {"message": "not found"})

    def do_POST(self) -> None:
        if not self._auth(): self._json(401, {"message": "unauthorized"}); return
        length = int(self.headers.get("Content-Length", "0"))
        try: body = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError: self._json(400, {"message": "invalid json"}); return
        if self.path == "/internal/runs":
            run = WORKER.submit(body); self._json(202, run.view()); return
        prefix = "/internal/runs/"
        if self.path.endswith("/cancel") and self.path.startswith(prefix):
            run = WORKER.cancel(self.path[len(prefix):-len("/cancel")])
            self._json(202 if run else 404, run.view() if run else {"message": "run not found"}); return
        self._json(404, {"message": "not found"})


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
