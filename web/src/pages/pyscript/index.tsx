import { useCallback, useEffect, useRef, useState } from 'react';
import { Editor } from '@monaco-editor/react';
import '../../monacoSetup';
import {
  Button, Card, Input, Message, Modal, Select, Space, Spin, Tag, Typography, Tree,
} from '@arco-design/web-react';
import {
  IconDelete, IconPlayArrow, IconPlus, IconSave, IconFile,
} from '@arco-design/web-react/icon';
import {
  ScriptInfo, ScriptDetail, RunStatus, RunHistoryItem, HdfsEntry,
  listScripts, getScript, createScript, updateScript, deleteScript,
  runScript, refreshRun, getRunLogs, runHistory, listHdfs,
} from '../../api/pyscript';
import { DataSourceInfo, listDatasources } from '../../api/datasource';

const SPARK_TEMPLATE = `# spark 变量已由平台注入，直接使用
result = spark.sql("SELECT 1 AS hello")
result.show()

# 读取 HDFS 文件示例：
# df = spark.read.parquet("hdfs://namenode:8020/path/to/data")
# df.show()

# 使用参数：\${city}
# df = spark.sql("SELECT * FROM sales.orders WHERE city = '\${city}'")
# df.show()
`;

const FLINK_TEMPLATE = `# env 和 st_env 变量已由平台注入
# 批处理示例：
# ds = env.from_elements(1, 2, 3, 4, 5)
# ds.map(lambda x: x * 2).print()

# 表 API 示例：
# st_env.execute_sql("SELECT 1").print()

print("Hello from PyFlink!")
`;

const { Text } = Typography;

function StatusTag({ status }: { status: string }) {
  const map: Record<string, { color: string; text: string }> = {
    QUEUED: { color: 'gray', text: '排队中' },
    RUNNING: { color: 'blue', text: '运行中' },
    SUCCESS: { color: 'green', text: '成功' },
    FAILED: { color: 'red', text: '失败' },
  };
  const s = map[status] ?? { color: 'gray', text: status };
  return <Tag color={s.color}>{s.text}</Tag>;
}

export default function PyScriptPage() {
  const [scripts, setScripts] = useState<ScriptInfo[]>([]);
  const [currentId, setCurrentId] = useState<number | null>(null);
  const [detail, setDetail] = useState<ScriptDetail | null>(null);
  const [content, setContent] = useState('');
  const [dsList, setDsList] = useState<DataSourceInfo[]>([]);
  const [selectedDs, setSelectedDs] = useState<number | undefined>();
  const [runStatus, setRunStatus] = useState<RunStatus | null>(null);
  const [runLogs, setRunLogs] = useState('');
  const [history, setHistory] = useState<RunHistoryItem[]>([]);
  const [running, setRunning] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [paramModal, setParamModal] = useState(false);
  const [paramValues, setParamValues] = useState<Record<string, string>>({});
  const [hdfsTree, setHdfsTree] = useState<any[]>([]);
  const [hdfsLoading, setHdfsLoading] = useState(false);
  const [renameId, setRenameId] = useState<number | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [newDialog, setNewDialog] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDs, setNewDs] = useState<number | undefined>();

  const logRef = useRef<HTMLPreElement>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const logOffsetRef = useRef(0);

  const computeDs = dsList.filter((d) => d.type === 'spark' || d.type === 'flink');
  const currentDs = computeDs.find((d) => d.id === (detail?.datasourceId ?? selectedDs));
  const isSpark = currentDs?.type === 'spark';

  const loadScripts = useCallback(async () => {
    try {
      setScripts(await listScripts());
    } catch { /* ignore */ }
    finally { setLoading(false); }
  }, []);

  const loadDs = useCallback(async () => {
    try {
      setDsList(await listDatasources());
    } catch { /* ignore */ }
  }, []);

  useEffect(() => { loadScripts(); loadDs(); }, [loadScripts, loadDs]);

  // load script detail
  useEffect(() => {
    if (currentId == null) { setDetail(null); setContent(''); return; }
    getScript(currentId).then((d) => {
      setDetail(d);
      setContent(d.content);
      setSelectedDs(d.datasourceId);
      runHistory(d.id).then(setHistory).catch(() => {});
    }).catch(() => {});
  }, [currentId]);

  // poll run status + logs
  const startPolling = useCallback((runId: number) => {
    logOffsetRef.current = 0;
    setRunLogs('');
    setRunning(true);
    if (pollRef.current) clearInterval(pollRef.current);
    pollRef.current = setInterval(async () => {
      try {
        const [status, logs] = await Promise.all([
          refreshRun(runId),
          getRunLogs(runId, logOffsetRef.current),
        ]);
        setRunStatus(status);
        if (logs.logs) {
          logOffsetRef.current = logs.offset;
          setRunLogs((prev) => prev + logs.logs);
          if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
        }
        if (status.status === 'SUCCESS' || status.status === 'FAILED') {
          if (pollRef.current) clearInterval(pollRef.current);
          setRunning(false);
          if (currentId != null) runHistory(currentId).then(setHistory).catch(() => {});
        }
      } catch { /* retry next tick */ }
    }, 2000);
  }, [currentId]);

  useEffect(() => () => { if (pollRef.current) clearInterval(pollRef.current); }, []);

  const handleRun = useCallback(async () => {
    if (currentId == null || !currentDs) return;
    if (detail && detail.params.length > 0) {
      setParamValues(Object.fromEntries(detail.params.map((p) => [p, ''])));
      setParamModal(true);
      return;
    }
    try {
      const r = await runScript(currentId, {});
      startPolling(r.runId);
    } catch (e: any) { Message.error(e?.response?.data?.message ?? '运行失败'); }
  }, [currentId, currentDs, detail, startPolling]);

  const handleRunWithParams = useCallback(async () => {
    if (currentId == null) return;
    setParamModal(false);
    try {
      const r = await runScript(currentId, paramValues);
      startPolling(r.runId);
    } catch (e: any) { Message.error(e?.response?.data?.message ?? '运行失败'); }
  }, [currentId, paramValues, startPolling]);

  const handleSave = useCallback(async () => {
    if (currentId == null) return;
    setSaving(true);
    try {
      await updateScript(currentId, { content, datasourceId: selectedDs });
      Message.success('保存成功');
      if (currentId != null) getScript(currentId).then(setDetail).catch(() => {});
    } catch (e: any) { Message.error(e?.response?.data?.message ?? '保存失败'); }
    finally { setSaving(false); }
  }, [currentId, content, selectedDs]);

  const handleNew = useCallback(async () => {
    if (!newName.trim() || !newDs) { Message.warning('请填写名称并选择数据源'); return; }
    try {
      const ds = dsList.find((d) => d.id === newDs);
      const tpl = ds?.type === 'flink' ? FLINK_TEMPLATE : SPARK_TEMPLATE;
      const d = await createScript({ name: newName.trim(), datasourceId: newDs, content: tpl });
      setNewDialog(false); setNewName(''); setNewDs(undefined);
      await loadScripts();
      setCurrentId(d.id);
    } catch (e: any) { Message.error(e?.response?.data?.message ?? '创建失败'); }
  }, [newName, newDs, dsList, loadScripts]);

  const handleDelete = useCallback(async (id: number) => {
    if (!window.confirm('确定删除该脚本及其运行历史？')) return;
    try {
      await deleteScript(id);
      if (currentId === id) { setCurrentId(null); setDetail(null); setContent(''); }
      await loadScripts();
    } catch (e: any) { Message.error(e?.response?.data?.message ?? '删除失败'); }
  }, [currentId, loadScripts]);

  const handleRename = useCallback(async () => {
    if (renameId == null || !renameValue.trim()) return;
    try {
      await updateScript(renameId, { name: renameValue.trim() });
      setRenameId(null);
      await loadScripts();
      if (currentId === renameId) getScript(renameId).then(setDetail).catch(() => {});
    } catch (e: any) { Message.error(e?.response?.data?.message ?? '重命名失败'); }
  }, [renameId, renameValue, currentId, loadScripts]);

  // HDFS tree lazy load
  const loadHdfsNode = useCallback(async (path: string): Promise<any[]> => {
    if (detail == null) return [];
    try {
      const entries = await listHdfs(detail.datasourceId, path);
      return entries.map((e: HdfsEntry) => ({
        key: e.path,
        title: e.name + (e.isDir ? '/' : ''),
        isLeaf: !e.isDir,
        icon: e.isDir ? undefined : <IconFile />,
      }));
    } catch { return []; }
  }, [detail]);

  useEffect(() => {
    if (isSpark && detail?.datasourceId) {
      setHdfsLoading(true);
      loadHdfsNode('/').then((nodes) => { setHdfsTree(nodes); setHdfsLoading(false); }).catch(() => setHdfsLoading(false));
    } else { setHdfsTree([]); }
  }, [isSpark, detail?.datasourceId, loadHdfsNode]);

  const insertAtCursor = useCallback((text: string) => {
    // Simple approach: append to content. A full cursor-aware insert needs editor ref.
    setContent((prev) => prev + '\n' + text);
  }, []);

  if (loading) return <div style={{ padding: 40 }}><Spin /></div>;

  return (
    <div style={{ display: 'flex', gap: 12, height: 'calc(100vh - 60px)', padding: '12px 16px' }}>
      {/* Left: script list + HDFS tree */}
      <div style={{ width: 240, flexShrink: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
        <Card size="small" title="脚本" extra={<Button size="mini" icon={<IconPlus />} onClick={() => setNewDialog(true)} />} style={{ flex: '0 0 auto' }}>
          <div style={{ maxHeight: 300, overflowY: 'auto' }}>
            {scripts.length === 0 && <Text type="secondary">暂无脚本</Text>}
            {scripts.map((s) => (
              <div key={s.id} style={{
                padding: '4px 8px', borderRadius: 4, cursor: 'pointer',
                background: s.id === currentId ? 'var(--color-fill-2)' : undefined,
              }}
                onClick={() => setCurrentId(s.id)}
              >
                {renameId === s.id ? (
                  <Input size="mini" value={renameValue} onChange={setRenameValue}
                    onPressEnter={handleRename} onBlur={handleRename} autoFocus />
                ) : (
                  <span onDoubleClick={() => { setRenameId(s.id); setRenameValue(s.name); }}>{s.name}</span>
                )}
                <Button size="mini" type="text" icon={<IconDelete />} style={{ float: 'right', color: 'var(--color-text-3)' }}
                  onClick={(e) => { e.stopPropagation(); handleDelete(s.id); }} />
              </div>
            ))}
          </div>
        </Card>
        {isSpark && detail != null && (
          <Card size="small" title="HDFS 文件" style={{ flex: 1, overflow: 'auto' }}>
            <Spin loading={hdfsLoading} style={{ display: 'block' }}>
              {hdfsTree.length === 0 && !hdfsLoading && <Text type="secondary">无法加载目录</Text>}
              <Tree
                treeData={hdfsTree}
                size="small"
                loadMore={async (node) => {
                  const children = await loadHdfsNode((node as any)._key as string);
                  (node as any).children = children;
                }}
                onSelect={(_, node) => {
                  const path = (node as any)._key as string;
                  if (!(node as any).isLeaf) return;
                  insertAtCursor(`spark.read.parquet("${path}")`);
                }}
              />
            </Spin>
          </Card>
        )}
      </div>

      {/* Right: editor + results */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 }}>
        <Card size="small" style={{ flex: '0 0 auto' }}>
          <Space size="medium" align="center">
            <Select
              placeholder="选择数据源"
              style={{ width: 200 }}
              value={selectedDs}
              onChange={(v) => setSelectedDs(v)}
              options={computeDs.map((d) => ({ value: d.id, label: `${d.name}（${d.type === 'spark' ? 'Spark' : 'Flink'}）` }))}
              disabled={currentId != null && detail != null}
            />
            <Button type="primary" icon={<IconPlayArrow />} loading={running} disabled={currentId == null || !selectedDs} onClick={handleRun}>
              运行
            </Button>
            <Button icon={<IconSave />} loading={saving} disabled={currentId == null} onClick={handleSave}>
              保存
            </Button>
          </Space>
        </Card>

        <Card size="small" style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
          <div style={{ flex: 1, minHeight: 120 }}>
            <Editor
              language="python"
              theme="vs"
              value={content}
              onChange={(v) => setContent(v ?? '')}
              options={{
                minimap: { enabled: false },
                fontSize: 13,
                lineNumbers: 'on',
                scrollBeyondLastLine: false,
                wordWrap: 'on',
                tabSize: 4,
              }}
              height="100%"
            />
          </div>
        </Card>

        <Card size="small" title={runStatus ? `运行 #${runStatus.runId}` : '输出'} extra={runStatus ? <StatusTag status={runStatus.status} /> : undefined}
          style={{ flex: '0 0 auto', maxHeight: 300, overflow: 'auto' }}>
          <pre ref={logRef} style={{
            margin: 0, padding: 8, background: 'var(--color-fill-1)', borderRadius: 4,
            fontSize: 12, lineHeight: 1.5, whiteSpace: 'pre-wrap', wordBreak: 'break-all',
            minHeight: 60, maxHeight: 200, overflowY: 'auto',
          }}>
            {runLogs || '点击"运行"查看脚本输出'}
          </pre>
          {runStatus?.error && (
            <div style={{ marginTop: 8, color: 'var(--danger-6)' }}>
              <Text type="error">{runStatus.error}</Text>
            </div>
          )}
        </Card>

        {history.length > 0 && (
          <Card size="small" title="历史运行" style={{ flex: '0 0 auto' }}>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              {history.slice(0, 10).map((h) => (
                <Space key={h.id} size={4}>
                  <Text style={{ fontSize: 12 }}>#{h.id}</Text>
                  <StatusTag status={h.status} />
                </Space>
              ))}
            </div>
          </Card>
        )}
      </div>

      {/* New script modal */}
      <Modal title="新建脚本" visible={newDialog} onCancel={() => setNewDialog(false)}
        onOk={handleNew} okText="创建" cancelText="取消">
        <Space direction="vertical" style={{ width: '100%' }}>
          <Input placeholder="脚本名称" value={newName} onChange={setNewName} />
          <Select placeholder="选择 Spark / Flink 数据源" value={newDs} onChange={setNewDs} style={{ width: '100%' }}
            options={computeDs.map((d) => ({ value: d.id, label: `${d.name}（${d.type === 'spark' ? 'Spark' : 'Flink'}）` }))} />
        </Space>
      </Modal>

      {/* Parameter modal */}
      <Modal title="运行参数" visible={paramModal} onCancel={() => setParamModal(false)}
        onOk={handleRunWithParams} okText="运行" cancelText="取消">
        <Space direction="vertical" style={{ width: '100%' }}>
          {detail?.params.map((p) => (
            <Input key={p} addBefore={p} placeholder="值" value={paramValues[p] ?? ''}
              onChange={(v) => setParamValues((prev) => ({ ...prev, [p]: v }))} />
          ))}
        </Space>
      </Modal>
    </div>
  );
}
