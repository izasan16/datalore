FROM dockerproxy.net/apache/spark:3.5.1
USER root
RUN pip install --no-cache-dir flask
COPY server.py /opt/spark-rest/server.py
EXPOSE 8998
WORKDIR /opt/spark
CMD ["python3", "/opt/spark-rest/server.py"]