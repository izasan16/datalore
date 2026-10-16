package com.daaspro.pyscript;

import com.daaspro.common.exception.BizException;
import com.daaspro.common.security.AesGcmCipher;
import com.daaspro.datasource.entity.DataSourceEntity;
import com.daaspro.datasource.mapper.DataSourceMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class HdfsBrowserService {

    private final DataSourceMapper dsMapper;
    private final AesGcmCipher cipher;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HdfsBrowserService(DataSourceMapper dsMapper, AesGcmCipher cipher) {
        this.dsMapper = dsMapper;
        this.cipher = cipher;
    }

    public record HdfsEntry(String name, String path, boolean isDir, long size, String modificationTime) {}

    public List<HdfsEntry> list(Long datasourceId, String path) {
        DataSourceEntity ds = dsMapper.selectById(datasourceId);
        if (ds == null) throw new BizException(404, "数据源不存在");
        if (!"spark".equals(ds.getType())) throw new BizException(400, "文件浏览仅支持 Spark 数据源");
        String webhdfsUrl = extractWebhdfsUrl(ds);
        String p = path == null || path.isBlank() || "/".equals(path) ? "/" : path;
        String encoded = p.split("/").length > 1
                ? java.util.Arrays.stream(p.split("/", 2)).skip(1).map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8)).reduce("", (a, b) -> "/" + a + "/" + b)
                : "";
        String url = webhdfsUrl + "/webhdfs/v1" + encoded + "?op=LISTSTATUS";
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) throw new BizException(400, "HDFS 访问失败 (HTTP " + resp.statusCode() + ")");
            JsonNode node = objectMapper.readTree(resp.body());
            List<HdfsEntry> result = new ArrayList<>();
            JsonNode statuses = node.path("FileStatuses").path("FileStatus");
            if (statuses.isArray()) {
                for (JsonNode f : statuses) {
                    String name = f.path("pathSuffix").asText();
                    if (name.isEmpty()) continue;
                    boolean isDir = f.path("type").asText().equals("DIRECTORY");
                    result.add(new HdfsEntry(name,
                            (p.endsWith("/") ? p : p + "/") + name,
                            isDir,
                            f.path("length").asLong(),
                            f.path("modificationTime").asText()));
                }
            }
            // directories first, then files, each alphabetically
            result.sort((a, b) -> {
                if (a.isDir() != b.isDir()) return a.isDir() ? -1 : 1;
                return a.name().compareTo(b.name());
            });
            return result;
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "HDFS 访问失败: " + e.getMessage()); }
    }

    static String extractWebhdfsUrl(DataSourceEntity ds) {
        if (ds.getExtraConfig() == null || ds.getExtraConfig().isBlank()) {
            throw new BizException(400, "数据源未配置 WebHDFS 地址");
        }
        try {
            var mapper = new ObjectMapper();
            var node = mapper.readTree(ds.getExtraConfig());
            String url = node.path("webhdfsUrl").asText();
            if (url == null || url.isBlank()) throw new BizException(400, "数据源未配置 WebHDFS 地址");
            return url.replaceAll("/+$", "");
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "WebHDFS 配置解析失败"); }
    }
}
