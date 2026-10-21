#!/bin/bash
# Start Flink JobManager in background
/opt/flink/bin/jobmanager.sh start
sleep 5
# Start Flink TaskManager
/opt/flink/bin/taskmanager.sh start
sleep 3
# Start script server in foreground
exec python3 /opt/flink-script-server/server.py