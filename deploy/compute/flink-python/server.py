import os, subprocess, urllib.request, json, uuid
from flask import Flask, request, jsonify

app = Flask(__name__)
SCRIPT_DIR = "/tmp/flink-scripts"
os.makedirs(SCRIPT_DIR, exist_ok=True)
FLINK_REST = "http://localhost:8081"

runs = {}  # jobId -> {script, log}

@app.route("/health")
def health():
    return jsonify({"status": "ok"})

@app.route("/submit", methods=["POST"])
def submit():
    body = request.get_json(force=True)
    code = body.get("code", "")
    sid = str(uuid.uuid4())[:8]
    script = os.path.join(SCRIPT_DIR, f"job-{sid}.py")
    log = os.path.join(SCRIPT_DIR, f"job-{sid}.log")
    with open(script, "w") as f:
        f.write(code)
    proc = subprocess.Popen(
        ["flink", "run", "-py", script],
        stdout=open(log, "w"), stderr=subprocess.STDOUT)
    # Wait (up to 20s) for the real JobID: "Job has been submitted with JobID xxxxx".
    # flink run needs ~10s+ for JVM startup; a fixed short sleep caused bogus IDs.
    import time
    deadline = time.time() + 20
    job_id = None
    while time.time() < deadline:
        if proc.poll() is not None:
            break
        try:
            with open(log, "r") as f:
                for line in f:
                    if "JobID" in line:
                        job_id = line.split("JobID")[-1].strip()
                        break
        except FileNotFoundError:
            pass
        if job_id:
            break
        time.sleep(0.5)
    if not job_id:
        if proc.poll() is None:
            proc.kill()
        tail = ""
        try:
            tail = "".join(open(log).readlines()[-10:])
        except Exception:
            pass
        return jsonify({"error": "flink run 未提交作业即退出", "detail": tail}), 500
    runs[job_id] = {"script": script, "log": log, "proc": proc}
    return jsonify({"jobId": job_id}), 201

@app.route("/status/<job_id>")
def status(job_id):
    r = runs.get(job_id)
    if not r:
        # Try Flink REST directly
        try:
            resp = urllib.request.urlopen(f"{FLINK_REST}/jobs/{job_id}")
            data = json.loads(resp.read())
            state = data.get("state", "unknown")
            return jsonify({"jobId": job_id, "state": state})
        except Exception:
            return jsonify({"jobId": job_id, "state": "error"}), 200
    rc = r["proc"].poll()
    if rc is not None:
        # Process finished, check exit code
        if rc == 0:
            return jsonify({"jobId": job_id, "state": "FINISHED"})
        else:
            return jsonify({"jobId": job_id, "state": "FAILED"})
    # Check Flink REST for actual state
    try:
        resp = urllib.request.urlopen(f"{FLINK_REST}/jobs/{job_id}")
        data = json.loads(resp.read())
        state = data.get("state", "RUNNING")
        return jsonify({"jobId": job_id, "state": state})
    except Exception:
        return jsonify({"jobId": job_id, "state": "RUNNING"})

@app.route("/cancel/<job_id>", methods=["POST"])
def cancel(job_id):
    r = runs.get(job_id)
    killed_local = False
    if r:
        if r["proc"].poll() is None:
            r["proc"].kill()
            killed_local = True
    # Flink REST cancel: PATCH /jobs/{id} with EMPTY body (body causes 400 EmptyRequestBody mismatch)
    try:
        req = urllib.request.Request(f"{FLINK_REST}/jobs/{job_id}", method="PATCH",
                                     data=b"", headers={"Content-Type": "application/json"})
        urllib.request.urlopen(req, timeout=10)
        return jsonify({"jobId": job_id, "canceled": True, "localKilled": killed_local})
    except urllib.error.HTTPError as e:
        # 404/409: job already finished or gone — treat as terminal, not an error
        if e.code in (404, 409):
            return jsonify({"jobId": job_id, "canceled": False, "alreadyFinished": True})
        return jsonify({"error": f"cancel failed: HTTP {e.code}"}), 502
    except Exception as e:
        return jsonify({"error": f"cancel failed: {e}"}), 502
@app.route("/logs/<job_id>")
def logs(job_id):
    r = runs.get(job_id)
    if not r:
        return jsonify({"jobId": job_id, "logs": []})
    try:
        with open(r["log"], "r") as f:
            lines = f.readlines()
    except FileNotFoundError:
        lines = []
    return jsonify({"jobId": job_id, "logs": [l.rstrip("\n") for l in lines]})

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8090, threaded=True)