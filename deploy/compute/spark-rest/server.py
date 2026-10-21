import os, subprocess, threading, uuid
from flask import Flask, request, jsonify

app = Flask(__name__)
BATCH_DIR = "/tmp/livy-batches"
os.makedirs(BATCH_DIR, exist_ok=True)

batches = {}  # id -> {pid, script, log}

@app.route("/batches", methods=["GET"])
def list_batches():
    return jsonify({"from": 0, "total": len(batches), "sessions": [], "batches": [{"id": bid, "state": "success" if b["proc"].poll() == 0 else ("running" if b["proc"].poll() is None else "error")} for bid, b in batches.items()]})

@app.route("/batches", methods=["POST"])
def create_batch():
    body = request.get_json(force=True)
    code = body.get("code", "")
    bid = str(uuid.uuid4())[:8]
    script = os.path.join(BATCH_DIR, f"batch-{bid}.py")
    log = os.path.join(BATCH_DIR, f"batch-{bid}.log")
    with open(script, "w") as f:
        f.write(code)
    proc = subprocess.Popen(
        ["/opt/spark/bin/spark-submit", "--master", "local[*]", script],
        stdout=open(log, "w"), stderr=subprocess.STDOUT,
        env={**os.environ, "PYTHONPATH": "/opt/spark/python:/opt/spark/python/lib/py4j-0.10.9.7-src.zip"})
    batches[bid] = {"pid": proc.pid, "proc": proc, "script": script, "log": log}
    return jsonify({"id": bid, "state": "starting"}), 201

@app.route("/batches/<bid>/state")
def batch_state(bid):
    b = batches.get(bid)
    if not b: return jsonify({"error": "not found"}), 404
    rc = b["proc"].poll()
    if rc is None:
        state = "running"
    elif rc == 0:
        state = "success"
    else:
        state = "error"
    return jsonify({"id": bid, "state": state})

@app.route("/batches/<bid>/log")
def batch_log(bid):
    b = batches.get(bid)
    if not b: return jsonify({"error": "not found"}), 404
    try:
        with open(b["log"], "r") as f:
            lines = f.readlines()
    except FileNotFoundError:
        lines = []
    return jsonify({"id": bid, "log": [l.rstrip("\n") for l in lines]})

@app.route("/batches/<bid>", methods=["DELETE"])
def kill_batch(bid):
    b = batches.pop(bid, None)
    if not b: return jsonify({"error": "not found"}), 404
    b["proc"].kill()
    return jsonify({"msg": "killed"})

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8998, threaded=True)