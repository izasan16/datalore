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
    # Wait briefly and try to extract job ID from log
    import time; time.sleep(3)
    job_id = None
    try:
        with open(log, "r") as f:
            content = f.read()
        # Flink output: "Job has been submitted with JobID xxxxx"
        for line in content.split("\n"):
            if "JobID" in line:
                job_id = line.split("JobID")[-1].strip()
                break
    except:
        pass
    if not job_id:
        job_id = sid  # fallback
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