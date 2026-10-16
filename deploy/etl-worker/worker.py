"""Small single-process ETL worker.

The HTTP contract is intentionally DataX-shaped: the server submits a validated
job document, while the execution backend can later be replaced with a DataX
subprocess without changing the server protocol. PostgreSQL is supported for
the local MVP so the worker can be exercised without shipping the DataX bundle.
"""
from __future__ import annotations

import decimal
import json
import logging
import os
import queue
import re
import subprocess
import threading
import time
import uuid
import datetime
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
# 容器内运行时，回环地址指向容器自身；改写为宿主机网关才能连到宿主端口映射
GATEWAY_REWRITE = os.environ.get("DAASPRO_WORKER_GATEWAY_REWRITE", "1") == "1"
HIVE_DRIVER = "org.apache.hive.jdbc.HiveDriver"
HIVE_JDBC_JAR = os.environ.get("HIVE_JDBC_JAR", "/opt/etl/hive-jdbc-standalone.jar")
CREATE_TABLE = re.compile(
    r"(?is)^\s*(CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?)"
    r"((?:`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)(?:\s*\.\s*(?:`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*))?)"
    r"(\s*\()(.*)$", re.S)


def identifier(value: str, tick: bool = False) -> str:
    if not value or not IDENT.fullmatch(value):
        raise ValueError("invalid identifier")
    if tick:
        return "`" + value.replace("`", "``") + "`"
    return '"' + value.replace('"', '""') + '"'


def qualify_create_table(ddl: str, default_schema: str, tick: bool = False) -> str:
    """未限定 schema 的 CREATE TABLE 补上默认 schema；统一加 IF NOT EXISTS 语义。"""
    match = CREATE_TABLE.match(ddl.strip())
    if not match:
        raise ValueError("建表语句必须是单条 CREATE TABLE")
    prefix, name, open_paren, tail = match.groups()
    if "IF NOT EXISTS" not in prefix.upper():
        prefix = re.sub(r"(?i)CREATE\s+TABLE\s+", "CREATE TABLE IF NOT EXISTS ", prefix)
    if "." in name:
        return f"{prefix}{name}{open_paren}{tail}"
    return f"{prefix or ''}{identifier(default_schema, tick)}.{name}{open_paren}{tail}"


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
        ds_type = config.get("type")
        host = config["host"]
        if GATEWAY_REWRITE and host in ("localhost", "127.0.0.1", "::1"):
            host = "host.docker.internal"
        if ds_type == "postgresql":
            return psycopg.connect(host=host, port=config["port"], dbname=config["databaseName"],
                                   user=config["username"], password=config["password"], autocommit=True)
        if ds_type == "hive":
            return self._connect_hive(host, config["port"], config.get("databaseName") or "default",
                                      config.get("username") or "", config.get("password") or "")
        raise ValueError("当前 Worker 执行器仅支持 PostgreSQL 与 Hive")

    _jvm_lock = threading.Lock()
    _jvm_started = False

    def _connect_hive(self, host: str, port: Any, database: str, username: str, password: str):
        try:
            import jpype
            import jaydebeapi
        except ImportError as exc:
            raise ValueError("Worker 未安装 Hive JDBC 依赖") from exc
        with Worker._jvm_lock:
            if not Worker._jvm_started and not jpype.isJVMStarted():
                jpype.startJVM(classpath=[HIVE_JDBC_JAR])
                Worker._jvm_started = True
        url = f"jdbc:hive2://{host}:{port}/{database}"
        conn = jaydebeapi.connect(HIVE_DRIVER, url, [username or "", password or ""], HIVE_JDBC_JAR)
        try:
            conn.jconn.setAutoCommit(True)
        except Exception:
            pass
        return conn

    def _execute_mapping(self, run: Run, mapping: dict[str, Any]) -> None:
        if mapping["target"].get("type") == "hive":
            self._execute_mapping_hive(run, mapping)
        else:
            self._execute_mapping_pg(run, mapping)

    def _execute_mapping_pg(self, run: Run, mapping: dict[str, Any]) -> None:
        source = mapping["source"]
        target = mapping["target"]
        fields = [f for f in mapping.get("fields", []) if f.get("enabled", True)]
        if not fields:
            raise ValueError("至少选择一个字段")
        source_hive = source.get("type") == "hive"
        source_schema = identifier(source.get("schema") or ("default" if source_hive else "public"), source_hive)
        target_schema = identifier(target.get("schema") or "public")
        target_table = identifier(target["table"])
        select = mapping.get("select")
        if select:
            if not select.lstrip().upper().startswith(("SELECT", "WITH")) or ";" in select:
                raise ValueError("仅允许单条只读 SELECT")
        else:
            source_table = identifier(source["table"], source_hive)
            select = f"SELECT {', '.join(identifier(f['source'], source_hive) for f in fields)} FROM {source_schema}.{source_table}"
        source_conn = self._connect(source)
        target_conn = self._connect(target)
        try:
            with source_conn.cursor() as src, target_conn.cursor() as dst:
                # Platform SQL uses named parameters (:city); psycopg uses %(city)s, Hive uses ?.
                params = mapping.get("parameters") or {}
                if source_hive:
                    order = [m.group(1) for m in PARAM.finditer(select)]
                    executable_select = PARAM.sub("?", select)
                    bind = [params.get(name) for name in order]
                    missing = [n for n, v in zip(order, bind) if v is None]
                    if missing:
                        raise ValueError("SELECT 参数缺失: " + ", ".join(missing))
                    src.execute(executable_select, bind)
                else:
                    executable_select = PARAM.sub(lambda match: "%(" + match.group(1) + ")s", select)
                    src.execute(executable_select, params)
                target_cols = ", ".join(identifier(f["target"]) for f in fields)
                if mapping.get("targetMode", "CREATE") == "CREATE":
                    ddl = mapping.get("createDdl")
                    if not ddl:
                        raise ValueError("自动建表必须提供建表语句")
                    dst.execute(qualify_create_table(ddl, target.get("schema") or "public"))
                mode = mapping.get("writeMode", "APPEND")
                if mode == "OVERWRITE":
                    dst.execute(f"TRUNCATE TABLE {target_schema}.{target_table}")
                keys = mapping.get("upsertKeys") or []
                def _pyval(v):
                    if v is None or isinstance(v, (int, float, str, bool)):
                        return v
                    return str(v)
                def _source_rows():
                    if source_hive:
                        while True:
                            row = src.fetchone()
                            if row is None:
                                return
                            yield tuple(_pyval(v) for v in row)
                    else:
                        yield from src
                for row in _source_rows():
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

    def _execute_mapping_hive(self, run: Run, mapping: dict[str, Any]) -> None:
        LOG.info("hive mapping start run=%s", run.id)
        source = mapping["source"]
        target = mapping["target"]
        fields = [f for f in mapping.get("fields", []) if f.get("enabled", True)]
        if not fields:
            raise ValueError("至少选择一个字段")
        if mapping.get("writeMode") == "UPSERT":
            raise ValueError("Hive 目标不支持更新插入")
        source_hive = source.get("type") == "hive"
        source_schema = identifier(source.get("schema") or "public", source_hive)
        target_schema = identifier(target.get("schema") or "default", True)
        target_table = identifier(target["table"], True)
        select = mapping.get("select")
        if select:
            if not select.lstrip().upper().startswith(("SELECT", "WITH")) or ";" in select:
                raise ValueError("仅允许单条只读 SELECT")
        else:
            source_table = identifier(source["table"], source_hive)
            select = f"SELECT {', '.join(identifier(f['source'], source_hive) for f in fields)} FROM {source_schema}.{source_table}"
        LOG.info("hive connecting run=%s", run.id)
        source_conn = self._connect(source)
        target_conn = self._connect(target)
        LOG.info("hive connected run=%s", run.id)
        try:
            src = source_conn.cursor()
            dst = target_conn.cursor()
            try:
                params = mapping.get("parameters") or {}
                if source_hive:
                    order = [m.group(1) for m in PARAM.finditer(select)]
                    executable_select = PARAM.sub("?", select)
                    bind = [params.get(name) for name in order]
                    missing = [n for n, v in zip(order, bind) if v is None]
                    if missing:
                        raise ValueError("SELECT 参数缺失: " + ", ".join(missing))
                    src.execute(executable_select, bind)
                else:
                    executable_select = PARAM.sub(lambda m: "%(" + m.group(1) + ")s", select)
                    src.execute(executable_select, params)
                LOG.info("hive source scanned run=%s", run.id)
                target_cols = ", ".join(identifier(f["target"], True) for f in fields)
                mode = mapping.get("writeMode", "APPEND")
                if mapping.get("targetMode", "CREATE") == "CREATE":
                    ddl = mapping.get("createDdl")
                    if not ddl:
                        raise ValueError("自动建表必须提供建表语句")
                    dst.execute(qualify_create_table(ddl, target.get("schema") or "default", True))
                elif mode == "OVERWRITE":
                    dst.execute(f"TRUNCATE TABLE {target_schema}.{target_table}")
                chunk: list[tuple] = []
                for row in src:
                    if run.cancel.is_set():
                        return
                    chunk.append(tuple(row))
                    if len(chunk) >= 200:
                        LOG.info("hive flush %d rows run=%s", len(chunk), run.id)
                        self._hive_flush(dst, target_schema, target_table, target_cols, fields, chunk, run)
                        chunk = []
                if chunk:
                    LOG.info("hive flush %d rows run=%s", len(chunk), run.id)
                    self._hive_flush(dst, target_schema, target_table, target_cols, fields, chunk, run)
                LOG.info("hive mapping done run=%s", run.id)
            finally:
                src.close()
                dst.close()
        finally:
            source_conn.close()
            target_conn.close()

    @staticmethod
    def _hive_flush(dst, target_schema: str, target_table: str, target_cols: str,
                    fields: list, chunk: list, run: Run) -> None:
        rows_sql = ", ".join("(" + ", ".join(["?"] * len(fields)) + ")" for _ in chunk)
        stmt = f"INSERT INTO {target_schema}.{target_table} ({target_cols}) VALUES {rows_sql}"
        flat = [Worker._jval(v) for row in chunk for v in row]
        dst.execute(stmt, flat)
        run.read_rows += len(chunk)
        run.written_rows += len(chunk)

    @staticmethod
    def _jval(v: Any) -> Any:
        """JDBC setObject 不识别 Python 原生日期/高精度类型，统一转字符串或数值。"""
        if isinstance(v, (datetime.datetime, datetime.date, datetime.time)):
            return str(v)
        if isinstance(v, decimal.Decimal):
            return float(v)
        return v

    # NOTE: DDL 由后端 EtlValidation.parseCreateTable 校验后原样下发，这里不做二次解析。


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
