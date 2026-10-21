FROM dockerproxy.net/apache/flink:1.18
USER root
RUN apt-get update && apt-get install -y python3 python3-pip && \
    ln -sf /usr/bin/python3 /usr/bin/python && \
    pip3 install --no-cache-dir flask apache-flink==1.18.0 && \
    mkdir -p /opt/flink-script-server
COPY server.py /opt/flink-script-server/server.py
EXPOSE 8081 8090
COPY entrypoint.sh /opt/flink-script-server/entrypoint.sh
RUN chmod +x /opt/flink-script-server/entrypoint.sh
ENTRYPOINT ["/opt/flink-script-server/entrypoint.sh"]