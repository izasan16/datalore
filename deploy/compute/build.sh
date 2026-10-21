#!/bin/bash
set -e
echo "Building Spark mini-Livy image..."
docker build -t datalore/spark-rest:latest deploy/compute/spark-rest/
echo "Building Flink PyFlink image..."
docker build -t datalore/flink-python:latest deploy/compute/flink-python/
echo "Done."