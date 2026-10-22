package com.datalore.pyscript;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datalore.common.exception.BizException;
import com.datalore.datasource.entity.DataSourceEntity;
import com.datalore.datasource.mapper.DataSourceMapper;
import com.datalore.pyscript.entity.PyscriptEntity;
import com.datalore.pyscript.entity.PyscriptRunEntity;
import com.datalore.pyscript.mapper.PyscriptMapper;
import com.datalore.pyscript.mapper.PyscriptRunMapper;
import com.datalore.tenant.context.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PyScriptService {

    static final Pattern PARAM_PATTERN = Pattern.compile("\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)}");

    private final PyscriptMapper scriptMapper;
    private final PyscriptRunMapper runMapper;
    private final DataSourceMapper dsMapper;
    private final ScriptExecutorService executor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PyScriptService(PyscriptMapper scriptMapper, PyscriptRunMapper runMapper,
                           DataSourceMapper dsMapper, ScriptExecutorService executor) {
        this.scriptMapper = scriptMapper;
        this.runMapper = runMapper;
        this.dsMapper = dsMapper;
        this.executor = executor;
    }

    public record ScriptInfo(long id, String name, Long datasourceId, String updatedAt) {}
    public record ScriptDetail(long id, String name, Long datasourceId, String content,
                               List<String> params, String updatedAt) {}

    public List<ScriptInfo> list() {
        return scriptMapper.selectList(new LambdaQueryWrapper<PyscriptEntity>()
                .select(PyscriptEntity::getId, PyscriptEntity::getName,
                        PyscriptEntity::getDatasourceId, PyscriptEntity::getUpdatedAt)
                .eq(PyscriptEntity::getTenantId, TenantContext.requireTenantId())
                .orderByDesc(PyscriptEntity::getUpdatedAt))
                .stream().map(s -> new ScriptInfo(s.getId(), s.getName(), s.getDatasourceId(),
                        s.getUpdatedAt() == null ? "" : s.getUpdatedAt().toString())).toList();
    }

    public ScriptDetail get(Long id) {
        PyscriptEntity s = getOwned(id);
        return new ScriptDetail(s.getId(), s.getName(), s.getDatasourceId(), s.getContent(),
                extractParams(s.getContent()), s.getUpdatedAt() == null ? "" : s.getUpdatedAt().toString());
    }

    public PyscriptEntity getOwned(Long id) {
        PyscriptEntity s = scriptMapper.selectById(id);
        if (s == null || !s.getTenantId().equals(TenantContext.requireTenantId())) {
            throw new BizException(404, "脚本不存在");
        }
        return s;
    }

    public record CreateReq(String name, Long datasourceId, String content) {}

    public ScriptDetail create(CreateReq req) {
        if (req.name() == null || req.name().isBlank()) throw new BizException(400, "脚本名称不能为空");
        if (req.datasourceId() == null) throw new BizException(400, "请选择数据源");
        requireOwnedDatasource(req.datasourceId());
        Long dup = scriptMapper.selectCount(new LambdaQueryWrapper<PyscriptEntity>()
                .eq(PyscriptEntity::getTenantId, TenantContext.requireTenantId())
                .eq(PyscriptEntity::getName, req.name().trim()));
        if (dup > 0) throw new BizException(400, "同名脚本已存在");
        PyscriptEntity s = new PyscriptEntity();
        s.setTenantId(TenantContext.requireTenantId());
        s.setName(req.name().trim());
        s.setDatasourceId(req.datasourceId());
        s.setContent(req.content() == null ? "" : req.content());
        s.setParamsJson(toJson(extractParams(s.getContent())));
        s.setUpdatedAt(LocalDateTime.now());
        scriptMapper.insert(s);
        return get(s.getId());
    }

    public record UpdateReq(String name, Long datasourceId, String content) {}

    public ScriptDetail update(Long id, UpdateReq req) {
        PyscriptEntity s = getOwned(id);
        if (req.name() != null && !req.name().isBlank() && !req.name().trim().equals(s.getName())) {
            Long dup = scriptMapper.selectCount(new LambdaQueryWrapper<PyscriptEntity>()
                    .eq(PyscriptEntity::getTenantId, TenantContext.requireTenantId())
                    .eq(PyscriptEntity::getName, req.name().trim())
                    .ne(PyscriptEntity::getId, id));
            if (dup > 0) throw new BizException(400, "同名脚本已存在");
            s.setName(req.name().trim());
        }
        if (req.datasourceId() != null) {
            requireOwnedDatasource(req.datasourceId());
            s.setDatasourceId(req.datasourceId());
        }
        if (req.content() != null) {
            s.setContent(req.content());
            s.setParamsJson(toJson(extractParams(req.content())));
        }
        s.setUpdatedAt(LocalDateTime.now());
        scriptMapper.updateById(s);
        return get(id);
    }

    public void delete(Long id) {
        getOwned(id);
        // delete runs
        runMapper.delete(new LambdaQueryWrapper<PyscriptRunEntity>()
                .eq(PyscriptRunEntity::getScriptId, id));
        scriptMapper.deleteById(id);
    }

    public record RunReq(Map<String, String> params) {}
    public record RunResult(long runId, String status) {}

    public RunResult run(Long scriptId, RunReq req) {
        PyscriptEntity s = getOwned(scriptId);
        DataSourceEntity ds = requireOwnedDatasource(s.getDatasourceId());
        String code = s.getContent();
        List<String> declared = extractParams(code);
        // substitute params
        for (String p : declared) {
            String val = req != null && req.params() != null ? req.params().get(p) : null;
            if (val == null) val = "";
            code = code.replace(" + p + ", val);
        }
        PyscriptRunEntity run = new PyscriptRunEntity();
        run.setScriptId(scriptId);
        run.setDatasourceId(s.getDatasourceId());
        run.setStatus("QUEUED");
        run.setLogText("");
        run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);

        String remoteJobId = executor.submit(ds, code, run.getId());
        run.setRemoteJobId(remoteJobId);
        run.setStatus("RUNNING");
        run.setStartedAt(LocalDateTime.now());
        runMapper.updateById(run);
        return new RunResult(run.getId(), run.getStatus());
    }

    public record RunStatus(long runId, String status, String error, String startedAt, String finishedAt) {}

    public record AdhocReq(Long datasourceId, String content, Map<String, String> params) {}

    /** 临时运行：不绑定脚本（对齐 SQL 分析的直接执行），运行记录保留但 script_id 为空 */
    public RunResult runAdhoc(AdhocReq req) {
        if (req.datasourceId() == null) throw new BizException(400, "请选择数据源");
        if (req.content() == null || req.content().isBlank()) throw new BizException(400, "请输入脚本内容");
        DataSourceEntity ds = requireOwnedDatasource(req.datasourceId());
        String code = req.content();
        List<String> declared = extractParams(code);
        for (String p : declared) {
            String val = req.params() != null ? req.params().get(p) : null;
            if (val == null) val = "";
            code = code.replace(" + p + ", val);
        }
        PyscriptRunEntity run = new PyscriptRunEntity();
        run.setDatasourceId(req.datasourceId());
        run.setStatus("QUEUED");
        run.setLogText("");
        run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);

        String remoteJobId = executor.submit(ds, code, run.getId());
        run.setRemoteJobId(remoteJobId);
        run.setStatus("RUNNING");
        run.setStartedAt(LocalDateTime.now());
        runMapper.updateById(run);
        return new RunResult(run.getId(), run.getStatus());
    }

    public RunStatus refresh(Long runId) {
        PyscriptRunEntity run = getRun(runId);
        if ("RUNNING".equals(run.getStatus()) || "QUEUED".equals(run.getStatus())) {
            DataSourceEntity ds = requireOwnedDatasource(run.getDatasourceId());
            executor.refresh(run, ds);
            runMapper.updateById(run);
        }
        return new RunStatus(run.getId(), run.getStatus(), run.getError(),
                run.getStartedAt() == null ? "" : run.getStartedAt().toString(),
                run.getFinishedAt() == null ? "" : run.getFinishedAt().toString());
    }

    public String logs(Long runId, int offset) {
        PyscriptRunEntity run = getRun(runId);
        String text = run.getLogText() == null ? "" : run.getLogText();
        if (offset >= text.length()) return "";
        return text.substring(offset);
    }

    /** 取消运行中的脚本：调远端取消接口，成功后本地状态置为 CANCELED。 */
    public RunStatus cancelRun(Long runId) {
        PyscriptRunEntity run = getRun(runId);
        if (!"RUNNING".equals(run.getStatus()) && !"QUEUED".equals(run.getStatus())) {
            throw new BizException(400, "运行已结束，无法取消");
        }
        DataSourceEntity ds = requireOwnedDatasource(run.getDatasourceId());
        executor.cancel(run, ds);
        run.setStatus("CANCELED");
        run.setFinishedAt(java.time.LocalDateTime.now());
        runMapper.updateById(run);
        return new RunStatus(run.getId(), run.getStatus(), run.getError(),
                run.getStartedAt() == null ? "" : run.getStartedAt().toString(),
                run.getFinishedAt() == null ? "" : run.getFinishedAt().toString());
    }
    public record RunHistoryItem(long id, String status, String startedAt, String finishedAt) {}

    public List<RunHistoryItem> history(Long scriptId) {
        getOwned(scriptId);
        return runMapper.selectList(new LambdaQueryWrapper<PyscriptRunEntity>()
                .eq(PyscriptRunEntity::getScriptId, scriptId)
                .orderByDesc(PyscriptRunEntity::getId)
                .last("LIMIT 50"))
                .stream().map(r -> new RunHistoryItem(r.getId(), r.getStatus(),
                        r.getStartedAt() == null ? "" : r.getStartedAt().toString(),
                        r.getFinishedAt() == null ? "" : r.getFinishedAt().toString())).toList();
    }

    private PyscriptRunEntity getRun(Long runId) {
        PyscriptRunEntity run = runMapper.selectById(runId);
        if (run == null) throw new BizException(404, "运行记录不存在");
        if (run.getScriptId() != null) {
            PyscriptEntity s = scriptMapper.selectById(run.getScriptId());
            if (s == null || !s.getTenantId().equals(TenantContext.requireTenantId())) {
                throw new BizException(404, "运行记录不存在");
            }
        } else {
            // 临时运行：通过数据源校验租户归属
            DataSourceEntity ds = dsMapper.selectById(run.getDatasourceId());
            if (ds == null || !ds.getTenantId().equals(TenantContext.requireTenantId())) {
                throw new BizException(404, "运行记录不存在");
            }
        }
        return run;
    }

    private DataSourceEntity requireOwnedDatasource(Long dsId) {
        DataSourceEntity ds = dsMapper.selectById(dsId);
        if (ds == null || !ds.getTenantId().equals(TenantContext.requireTenantId())) {
            throw new BizException(404, "数据源不存在");
        }
        if (!"spark".equals(ds.getType()) && !"flink".equals(ds.getType())) {
            throw new BizException(400, "仅支持 Spark / Flink 计算引擎");
        }
        return ds;
    }

    static List<String> extractParams(String content) {
        if (content == null || content.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        Matcher m = PARAM_PATTERN.matcher(content);
        while (m.find()) {
            String p = m.group(1);
            if (!result.contains(p)) result.add(p);
        }
        return result;
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }
}
