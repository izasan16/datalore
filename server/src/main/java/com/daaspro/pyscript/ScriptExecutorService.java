package com.daaspro.pyscript;

import com.daaspro.common.exception.BizException;
import com.daaspro.datasource.entity.DataSourceEntity;
import com.daaspro.pyscript.entity.PyscriptRunEntity;
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
                    "name", "daaspro-script-" + localRunId
            ));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/batches"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) throw new BizException(400, "Spark 提交失败: " + resp.body());
            JsonNode node = objectMapper.readTree(resp.body());
            return String.valueOf(node.path("id").asLong());
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
                case "dead", "error", "killed" -> {
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
        String baseUrl = flinkUrl(ds);
        try {
            // PyFlink submission via REST is not natively supported for .py files.
            // We use a lightweight approach: POST /jars/:id/run with programArgs
            // For now, we'll use Flink REST API /jars/upload + run pattern.
            // But without a pre-uploaded jar containing PyFlink runner, we can't submit.
            // This is a known limitation - for now return error with helpful message.
            // TODO: implement wrapper jar approach in next iteration
            throw new BizException(400, "Flink 脚本提交暂未实现，需要部署 wrapper jar。当前版本仅支持 Spark (Livy)。");
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "Flink 提交失败: " + e.getMessage()); }
    }

    private void refreshFlink(PyscriptRunEntity run, DataSourceEntity ds) {
        // Same as submitFlink - not implemented yet
        run.setStatus("FAILED");
        run.setError("Flink 状态刷新暂未实现");
        run.setFinishedAt(LocalDateTime.now());
    }

    private String flinkUrl(DataSourceEntity ds) {
        String host = ds.getHost();
        Integer port = ds.getPort();
        if (host == null || host.isBlank()) throw new BizException(400, "Flink 数据源缺少 JobManager 地址");
        return "http://" + host + ":" + (port != null ? port : 8081);
    }
}
