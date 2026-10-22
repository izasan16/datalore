package com.datalore.pyscript;

import com.datalore.common.exception.BizException;
import com.datalore.datasource.entity.DataSourceEntity;
import com.datalore.pyscript.entity.PyscriptRunEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class ScriptExecutorService {

    private static final Logger log = LoggerFactory.getLogger(ScriptExecutorService.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Submit script to Spark Livy or Flink REST, return remote job ID. */
    public String submit(DataSourceEntity ds, String code, long localRunId) {
        if ("spark".equals(ds.getType())) return submitSpark(ds, code, localRunId);
        if ("flink".equals(ds.getType())) return submitFlink(ds, code, localRunId);
        throw new BizException(400, "不支持的计算引擎类型");
    }

    /** Poll remote status and update run entity (status, logText, error). */
    public void refresh(PyscriptRunEntity run, DataSourceEntity ds) {
        // 终态（含手动取消）不再被远端状态覆盖
        if ("SUCCESS".equals(run.getStatus()) || "FAILED".equals(run.getStatus())
                || "CANCELED".equals(run.getStatus())) {
            return;
        }
        try {
            if ("spark".equals(ds.getType())) refreshSpark(run, ds);
            else if ("flink".equals(ds.getType())) refreshFlink(run, ds);
        } catch (Exception e) {
            log.warn("Script run refresh failed: {}", e.getMessage());
            run.setStatus("FAILED");
            run.setError("状态刷新失败: " + e.getMessage());
            run.setFinishedAt(LocalDateTime.now());
        }
    }

    /** 取消远端作业：Spark 走 Livy DELETE /batches/{id}，Flink 走脚本服务 /cancel/{jobId}。 */
    public void cancel(PyscriptRunEntity run, DataSourceEntity ds) {
        String remoteId = run.getRemoteJobId();
        if (remoteId == null || remoteId.isBlank()) {
            throw new BizException(400, "缺少远端作业 ID，无法取消");
        }
        if ("spark".equals(ds.getType())) {
            String url = livyUrl(ds) + "/batches/" + remoteId;
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).DELETE().build();
            HttpResponse<String> resp = send(req, "Spark 取消失败");
            // 404 = 批任务已结束/已被清理，视为成功（本地状态照常置为 CANCELED）
            if (resp.statusCode() >= 300 && resp.statusCode() != 404) {
                throw new BizException(400, "Spark 取消失败 (HTTP " + resp.statusCode() + ")");
            }
        } else if ("flink".equals(ds.getType())) {
            String url = flinkScriptUrl(ds) + "/cancel/" + remoteId;
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> resp = send(req, "Flink 取消失败");
            if (resp.statusCode() >= 300 && resp.statusCode() != 404) {
                throw new BizException(400, "Flink 取消失败 (HTTP " + resp.statusCode() + ")");
            }
        } else {
            throw new BizException(400, "不支持的计算引擎类型");
        }
    }

    private HttpResponse<String> send(HttpRequest req, String errorPrefix) {
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp;
        } catch (Exception e) {
            throw new BizException(400, errorPrefix + ": " + e.getMessage());
        }
    }

    // === Spark Livy ===
    private String submitSpark(DataSourceEntity ds, String code, long localRunId) {
        String baseUrl = livyUrl(ds);
        try {
            // Write script to temp and submit as batch
            // Livy batch requires a file URI or inline code via "code" param for interactive sessions.
            // For batch, we use "file" or "pyFiles". Simplest: use Livy session + statement approach.
            // But for a lightweight editor, batch with inline code is better.
            // Livy batch: POST /batches { "code": "...", "kind": "pyspark" }
            // Note: Livy 0.8+ supports "code" in batch submission
            String body = objectMapper.writeValueAsString(Map.of(
                    "kind", "pyspark",
                    "code", code,
                    "name", "Datalore-script-" + localRunId
            ));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/batches"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) throw new BizException(400, "Spark 提交失败: " + resp.body());
            JsonNode node = objectMapper.readTree(resp.body());
            return node.path("id").asText();
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "Spark 提交失败: " + e.getMessage()); }
    }

    private void refreshSpark(PyscriptRunEntity run, DataSourceEntity ds) {
        String baseUrl = livyUrl(ds);
        String batchId = run.getRemoteJobId();
        if (batchId == null || batchId.isBlank()) {
            run.setStatus("FAILED");
            run.setError("缺少远端作业 ID");
            run.setFinishedAt(LocalDateTime.now());
            return;
        }
        try {
            // Get state
            HttpRequest stateReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/batches/" + batchId + "/state"))
                    .GET().build();
            HttpResponse<String> stateResp = httpClient.send(stateReq, HttpResponse.BodyHandlers.ofString());
            if (stateResp.statusCode() >= 300) {
                // batch might have been cleaned up
                run.setStatus("FAILED");
                run.setError("Livy 状态查询失败 (HTTP " + stateResp.statusCode() + ")");
                run.setFinishedAt(LocalDateTime.now());
                return;
            }
            JsonNode stateNode = objectMapper.readTree(stateResp.body());
            String state = stateNode.path("state").asText();
            switch (state) {
                case "success" -> {
                    run.setStatus("SUCCESS");
                    run.setFinishedAt(LocalDateTime.now());
                }
                case "killed" -> {
                    run.setStatus("CANCELED");
                    run.setError("Spark 作业已被取消");
                    run.setFinishedAt(LocalDateTime.now());
                }
                case "dead", "error" -> {
                    run.setStatus("FAILED");
                    run.setError("Spark 作业状态: " + state);
                    run.setFinishedAt(LocalDateTime.now());
                }
                default -> run.setStatus("RUNNING");
            }
            // Append logs
            HttpRequest logReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/batches/" + batchId + "/log?from=0&size=10000"))
                    .GET().build();
            HttpResponse<String> logResp = httpClient.send(logReq, HttpResponse.BodyHandlers.ofString());
            if (logResp.statusCode() < 300) {
                JsonNode logNode = objectMapper.readTree(logResp.body());
                if (logNode.has("log")) {
                    StringBuilder sb = new StringBuilder();
                    logNode.get("log").forEach(line -> sb.append(line.asText()).append("\n"));
                    run.setLogText(sb.toString());
                }
            }
        } catch (Exception e) {
            log.warn("Spark refresh error for batch {}: {}", batchId, e.getMessage());
        }
    }

    private String livyUrl(DataSourceEntity ds) {
        String host = ds.getHost();
        Integer port = ds.getPort();
        if (host == null || host.isBlank()) throw new BizException(400, "Spark 数据源缺少 Livy 地址");
        return "http://" + host + ":" + (port != null ? port : 8998);
    }

    // === Flink REST ===
    private String submitFlink(DataSourceEntity ds, String code, long localRunId) {
        String baseUrl = flinkScriptUrl(ds);
        try {
            String body = objectMapper.writeValueAsString(Map.of("code", code));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/submit"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) throw new BizException(400, "Flink 提交失败: " + resp.body());
            JsonNode node = objectMapper.readTree(resp.body());
            return node.path("jobId").asText();
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "Flink 提交失败: " + e.getMessage()); }
    }

    private void refreshFlink(PyscriptRunEntity run, DataSourceEntity ds) {
        String baseUrl = flinkScriptUrl(ds);
        String jobId = run.getRemoteJobId();
        if (jobId == null || jobId.isBlank()) {
            run.setStatus("FAILED");
            run.setError("缺少远端作业 ID");
            run.setFinishedAt(LocalDateTime.now());
            return;
        }
        try {
            HttpRequest statusReq = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/status/" + jobId)).GET().build();
            HttpResponse<String> statusResp = httpClient.send(statusReq, HttpResponse.BodyHandlers.ofString());
            if (statusResp.statusCode() >= 300) {
                run.setStatus("FAILED");
                run.setError("Flink 状态查询失败 (HTTP " + statusResp.statusCode() + ")");
                run.setFinishedAt(LocalDateTime.now());
                return;
            }
            JsonNode stateNode = objectMapper.readTree(statusResp.body());
            String state = stateNode.path("state").asText();
            switch (state) {
                case "FINISHED" -> {
                    run.setStatus("SUCCESS");
                    run.setFinishedAt(LocalDateTime.now());
                }
                case "CANCELED" -> {
                    run.setStatus("CANCELED");
                    run.setError("Flink 作业已被取消");
                    run.setFinishedAt(LocalDateTime.now());
                }
                case "FAILED", "error" -> {
                    run.setStatus("FAILED");
                    run.setError("Flink 作业状态: " + state);
                    run.setFinishedAt(LocalDateTime.now());
                }
                default -> run.setStatus("RUNNING");
            }
            // Append logs
            HttpRequest logReq = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/logs/" + jobId)).GET().build();
            HttpResponse<String> logResp = httpClient.send(logReq, HttpResponse.BodyHandlers.ofString());
            if (logResp.statusCode() < 300) {
                JsonNode logNode = objectMapper.readTree(logResp.body());
                if (logNode.has("logs")) {
                    StringBuilder sb = new StringBuilder();
                    logNode.get("logs").forEach(line -> sb.append(line.asText()).append("\n"));
                    run.setLogText(sb.toString());
                }
            }
        } catch (Exception e) {
            log.warn("Flink refresh error for job {}: {}", jobId, e.getMessage());
        }
    }

    private String flinkScriptUrl(DataSourceEntity ds) {
        String host = ds.getHost();
        Integer port = ds.getPort();
        if (host == null || host.isBlank()) throw new BizException(400, "Flink 数据源缺少 JobManager 地址");
        // Script server port: from extraConfig or default = port + 1
        Integer scriptPort = null;
        if (ds.getExtraConfig() != null && !ds.getExtraConfig().isBlank()) {
            try {
                var cfg = objectMapper.readTree(ds.getExtraConfig());
                scriptPort = cfg.path("scriptServerPort").asInt(0);
            } catch (Exception ignored) {}
        }
        if (scriptPort == null || scriptPort <= 0) scriptPort = (port != null ? port : 8081) + 1;
        return "http://" + host + ":" + scriptPort;
    }

}
