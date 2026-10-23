import { useCallback, useEffect, useRef, useState } from 'react';
import { Editor } from '@monaco-editor/react';
import '../../monacoSetup';
import {
  Button, Card, Input, Message, Modal, Select, Space, Spin, Tag, Typography, Tree,
} from '@arco-design/web-react';
import {
  IconClose, IconDelete, IconPlayArrow, IconPlus, IconSave, IconFile, IconStop, IconLoading,
} from '@arco-design/web-react/icon';
import {
  ScriptInfo, ScriptDetail, RunStatus, RunHistoryItem, HdfsEntry,
  listScripts, getScript, createScript, updateScript, deleteScript,
  runScript, runAdhoc, refreshRun, cancelRun, getRunLogs, runHistory, listHdfs,
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

const FLINK_TEMPLATE = `from pyflink.datastream import StreamExecutionEnvironment

env = StreamExecutionEnvironment.get_execution_environment()
env.set_parallelism(1)

ds = env.from_collection([1, 2, 3, 4, 5])
ds.map(lambda x: x * 10).print()

print("PYFLINK_OK")
env.execute("datalore_test")
`;

const TABS_KEY = 'Datalore.pyscript.tabs';
const MAX_TABS = 20;

const { Text } = Typography;

interface PyTab {
  key: string;
  scriptId: number | null;
  name: string;
  content: string;
  baselineName: string;
  baselineContent: string;
  datasourceId: number | undefined;
  params: string[];
  runStatus: RunStatus | null;
  runLogs: string;
  running: boolean;
  logOffset: number;
  history: RunHistoryItem[];
  paramValues: Record<string, string>;
}

function genKey() {
  return typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID()
    : 'k' + Date.now().toString(36) + Math.random().toString(36).slice(2);
}

function isDirty(t: PyTab) {
  return t.name !== t.baselineName || t.content !== t.baselineContent;
}

function isTerminal(s: string | undefined) {
  return s === 'SUCCESS' || s === 'FAILED' || s === 'CANCELED';
}

function StatusTag({ status }: { status: string }) {
  const map: Record<string, { color: string; text: string }> = {
    QUEUED: { color: 'gray', text: '排队中' },
    CANCELED: { color: 'orange', text: '已取消' },
    RUNNING: { color: 'blue', text: '运行中' },
    SUCCESS: { color: 'green', text: '成功' },
    FAILED: { color: 'red', text: '失败' },
  };
  const s = map[status] ?? { color: 'gray', text: status };
  return <Tag color={s.color}>{s.text}</Tag>;
}

export default function PyScriptPage() {
  const [scripts, setScripts] = useState<ScriptInfo[]>([]);
  const [tabs, setTabs] = useState<PyTab[]>([]);
  const [activeKey, setActiveKey] = useState<string>('');
  const [dsList, setDsList] = useState<DataSourceInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [paramModalTab, setParamModalTab] = useState<string | null>(null);
  const [paramModalAdhoc, setParamModalAdhoc] = useState(false);
  const [saveDialogTab, setSaveDialogTab] = useState<string | null>(null);
  const [saveName, setSaveName] = useState('');
  const [hdfsTree, setHdfsTree] = useState<any[]>([]);
  const [hdfsLoading, setHdfsLoading] = useState(false);
  const [renameId, setRenameId] = useState<number | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [renamingTabKey, setRenamingTabKey] = useState<string | null>(null);
  const [tabRenameValue, setTabRenameValue] = useState('');
  const [newDialog, setNewDialog] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDs, setNewDs] = useState<number | undefined>();

  const logRef = useRef<HTMLPreElement>(null);
  const editorRef = useRef<import('monaco-editor').editor.IStandaloneCodeEditor | null>(null);
  const tabsRef = useRef<PyTab[]>([]);
  tabsRef.current = tabs;
  const activeRef = useRef<string>('');
  activeRef.current = activeKey;
  // Monaco 在百分比高度容器里测不到尺寸（会塌成 5px），用视口像素高兜底
  const editorH = Math.round(Math.min(400, Math.max(200, window.innerHeight * 0.42)));

  const computeDs = dsList.filter((d) => d.type === 'spark' || d.type === 'flink');
  const active = tabs.find((t) => t.key === activeKey) || null;
  const activeDs = computeDs.find((d) => d.id === active?.datasourceId);
  const isSpark = activeDs?.type === 'spark';

  function nextTempName() {
    let max = 0;
    for (const t of tabsRef.current) {
      const m = /^未命名脚本 (\d+)$/.exec(t.name.trim());
      if (m) max = Math.max(max, parseInt(m[1], 10));
    }
    return '未命名脚本 ' + (max + 1);
  }

  function makeTab(partial: Partial<PyTab> = {}): PyTab {
    const name = partial.name ?? nextTempName();
    return {
      key: partial.key ?? genKey(),
      scriptId: partial.scriptId ?? null,
      name,
      content: partial.content ?? '',
      baselineName: partial.baselineName ?? name,
      baselineContent: partial.baselineContent ?? partial.content ?? '',
      datasourceId: partial.datasourceId,
      params: partial.params ?? [],
      runStatus: null,
      runLogs: '',
      running: false,
      logOffset: 0,
      history: [],
      paramValues: partial.paramValues ?? {},
    };
  }

  function updateTab(key: string, patch: Partial<PyTab>) {
    setTabs((ts) => ts.map((t) => (t.key === key ? { ...t, ...patch } : t)));
  }

  // ---- 本地持久化 ----
  useEffect(() => {
    let restored = false;
    try {
      const raw = localStorage.getItem(TABS_KEY);
      if (raw) {
        const saved = JSON.parse(raw);
        if (Array.isArray(saved.tabs) && saved.tabs.length > 0) {
          const restoredTabs: PyTab[] = saved.tabs.map((t: Partial<PyTab>) => ({
            ...makeTab(t),
            key: t.key ?? genKey(),
          }));
          setTabs(restoredTabs);
          const ak = saved.activeKey && restoredTabs.some((t) => t.key === saved.activeKey)
            ? saved.activeKey
            : restoredTabs[0].key;
          setActiveKey(ak);
          restored = true;
        }
      }
    } catch {
      // 忽略损坏的本地缓存
    }
    if (!restored) {
      const t = makeTab({ name: '未命名脚本 1' });
      setTabs([t]);
      setActiveKey(t.key);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!activeKey) return;
    try {
      localStorage.setItem(TABS_KEY, JSON.stringify({
        tabs: tabs.map((t) => ({
          key: t.key, scriptId: t.scriptId, name: t.name, content: t.content,
          baselineName: t.baselineName, baselineContent: t.baselineContent,
          datasourceId: t.datasourceId, params: t.params, paramValues: t.paramValues,
        })),
        activeKey,
      }));
    } catch {
      // 存储失败不影响使用
    }
  }, [tabs, activeKey]);

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

  // ---- 全局轮询：遍历所有运行中的页签 ----
  useEffect(() => {
    const timer = setInterval(async () => {
      const runningTabs = tabsRef.current.filter((t) => t.running && t.runStatus);
      for (const t of runningTabs) {
        const runId = t.runStatus!.runId;
        try {
          const [status, logs] = await Promise.all([
            refreshRun(runId),
            getRunLogs(runId, t.logOffset),
          ]);
          let appended = '';
          let offset = t.logOffset;
          if (logs.logs) {
            offset = logs.offset;
            appended = logs.logs;
          }
          if (isTerminal(status.status)) {
            try {
              const finalLogs = await getRunLogs(runId, offset);
              if (finalLogs.logs) {
                offset = finalLogs.offset;
                appended += finalLogs.logs;
              }
            } catch { /* ignore */ }
            if (t.scriptId != null) {
              runHistory(t.scriptId).then((h) => updateTab(t.key, { history: h })).catch(() => {});
            }
          }
          updateTab(t.key, {
            runStatus: status,
            running: !isTerminal(status.status),
            logOffset: offset,
            runLogs: t.runLogs + appended,
          });
        } catch { /* retry next tick */ }
      }
      if (runningTabs.length > 0 && logRef.current) {
        logRef.current.scrollTop = logRef.current.scrollHeight;
      }
    }, 2000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    const relayout = () => editorRef.current?.layout();
    const t1 = window.setTimeout(relayout, 50);
    const t2 = window.setTimeout(relayout, 300);
    window.addEventListener('resize', relayout);
    return () => {
      window.clearTimeout(t1);
      window.clearTimeout(t2);
      window.removeEventListener('resize', relayout);
    };
  }, [editorH, activeKey]);

  const clientParams = useCallback((code: string) => {
    const out: string[] = [];
    const re = /\$\{(\w+)\}/g;
    let m;
    while ((m = re.exec(code)) !== null) out.push(m[1]);
    return out;
  }, []);

  // ---- 运行 ----
  function startTabRun(tab: PyTab, runId: number) {
    updateTab(tab.key, { running: true, runStatus: { runId, status: 'QUEUED', error: '', startedAt: '', finishedAt: '' }, runLogs: '', logOffset: 0 });
  }

  const handleRun = useCallback((t: PyTab) => {
    if (!t.content.trim()) { Message.warning('请输入脚本内容'); return; }
    if (t.datasourceId == null) { Message.warning('请先选择 Spark / Flink 数据源'); return; }
    if (t.scriptId == null) {
      // 临时页签直接运行（对齐 SQL 分析）
      const ps = clientParams(t.content);
      if (ps.length > 0) {
        setParamModalAdhoc(true);
        setParamModalTab(t.key);
        updateTab(t.key, { paramValues: Object.fromEntries(ps.map((p) => [p, ''])) });
        return;
      }
      runAdhoc(t.datasourceId, t.content)
        .then((r) => startTabRun(t, r.runId))
        .catch((e: any) => Message.error(e?.response?.data?.message ?? '运行失败'));
      return;
    }
    if (t.params.length > 0) {
      setParamModalAdhoc(false);
      setParamModalTab(t.key);
      updateTab(t.key, { paramValues: Object.fromEntries(t.params.map((p) => [p, ''])) });
      return;
    }
    runScript(t.scriptId, {})
      .then((r) => startTabRun(t, r.runId))
      .catch((e: any) => Message.error(e?.response?.data?.message ?? '运行失败'));
  }, [clientParams]);

  const handleRunWithParams = useCallback(async () => {
    const t = tabsRef.current.find((x) => x.key === paramModalTab);
    setParamModalTab(null);
    if (!t) return;
    try {
      if (paramModalAdhoc) {
        const r = await runAdhoc(t.datasourceId!, t.content, t.paramValues);
        startTabRun(t, r.runId);
      } else {
        const r = await runScript(t.scriptId!, t.paramValues);
        startTabRun(t, r.runId);
      }
    } catch (e: any) { Message.error(e?.response?.data?.message ?? '运行失败'); }
  }, [paramModalAdhoc, paramModalTab]);

  function stopTab(t: PyTab) {
    if (!t.runStatus) return;
    cancelRun(t.runStatus.runId)
      .then((st) => {
        updateTab(t.key, { runStatus: st });
        Message.success('已发送停止请求');
      })
      .catch((e: any) => Message.info(e?.response?.data?.message ?? '无法取消'));
  }

  // ---- 保存 / 改名 ----
  function saveTab(t: PyTab) {
    if (t.scriptId == null) {
      if (t.datasourceId == null) { Message.warning('请先选择 Spark / Flink 数据源'); return; }
      if (!t.content.trim()) { Message.warning('请输入脚本内容'); return; }
      setSaveName(t.name.trim());
      setSaveDialogTab(t.key);
      return;
    }
    setSaving(true);
    updateScript(t.scriptId, { content: t.content, datasourceId: t.datasourceId })
      .then(() => {
        updateTab(t.key, { baselineContent: t.content });
        Message.success('保存成功');
      })
      .catch((e: any) => Message.error(e?.response?.data?.message ?? '保存失败'))
      .finally(() => setSaving(false));
  }

  const handleSaveAsNew = useCallback(async () => {
    const t = tabsRef.current.find((x) => x.key === saveDialogTab);
    if (!t || !saveName.trim() || t.datasourceId == null) { Message.warning('请填写名称'); return; }
    setSaveDialogTab(null);
    setSaving(true);
    try {
      const d = await createScript({ name: saveName.trim(), datasourceId: t.datasourceId, content: t.content });
      updateTab(t.key, {
        scriptId: d.id, name: d.name, baselineName: d.name, baselineContent: t.content,
        params: d.params ?? [],
      });
      setSaveName('');
      await loadScripts();
      Message.success('已保存');
    } catch (e: any) { Message.error(e?.response?.data?.message ?? '保存失败'); }
    finally { setSaving(false); }
  }, [saveDialogTab, saveName, loadScripts]);

  /** 双击页签改名：临时页签只改本地标题；已保存页签直接落库 */
  function commitTabRename(t: PyTab) {
    const n = tabRenameValue.trim();
    setRenamingTabKey(null);
    if (!n || n === t.name) return;
    if (t.scriptId == null) {
      updateTab(t.key, { name: n });
      return;
    }
    updateScript(t.scriptId, { name: n })
      .then(() => {
        updateTab(t.key, { name: n, baselineName: n });
        Message.success('已重命名');
        loadScripts();
      })
      .catch((e: any) => Message.error(e?.response?.data?.message ?? '重命名失败'));
  }

  // ---- 页签管理 ----
  function newTab() {
    if (tabs.length >= MAX_TABS) {
      Message.warning('最多同时打开 ' + MAX_TABS + ' 个页签');
      return;
    }
    const t = makeTab();
    setTabs((ts) => [...ts, t]);
    setActiveKey(t.key);
  }

  async function openScript(s: ScriptInfo) {
    const existing = tabsRef.current.find((t) => t.scriptId === s.id);
    if (existing) {
      setActiveKey(existing.key);
      return;
    }
    if (tabsRef.current.length >= MAX_TABS) {
      Message.warning('最多同时打开 ' + MAX_TABS + ' 个页签');
      return;
    }
    try {
      const d: ScriptDetail = await getScript(s.id);
      const t = makeTab({
        scriptId: d.id,
        name: d.name,
        content: d.content,
        baselineName: d.name,
        baselineContent: d.content,
        datasourceId: d.datasourceId,
        params: d.params ?? [],
      });
      setTabs((ts) => [...ts, t]);
      setActiveKey(t.key);
      runHistory(d.id).then((h) => updateTab(t.key, { history: h })).catch(() => {});
    } catch (e: any) {
      Message.error(e?.response?.data?.message ?? '加载脚本失败');
    }
  }

  function closeTab(target: PyTab, force = false) {
    const doClose = () => {
      setTabs((ts) => {
        const idx = ts.findIndex((t) => t.key === target.key);
        const next = ts.filter((t) => t.key !== target.key);
        if (next.length === 0) {
          const t = makeTab();
          setActiveKey(t.key);
          return [t];
        }
        if (target.key === activeRef.current) {
          const neighbor = next[Math.min(idx, next.length - 1)];
          setActiveKey(neighbor.key);
        }
        return next;
      });
    };
    if (!force && isDirty(target)) {
      Modal.confirm({
        title: '有未保存的修改',
        content: '关闭后当前编辑内容将被丢弃，确定继续吗？',
        okText: '放弃修改并关闭',
        cancelText: '留在当前',
        okButtonProps: { status: 'warning' },
        onOk: doClose,
      });
      return;
    }
    doClose();
  }

  const handleNew = useCallback(async () => {
    if (!newName.trim() || !newDs) { Message.warning('请填写名称并选择数据源'); return; }
    try {
      const ds = dsList.find((d) => d.id === newDs);
      const tpl = ds?.type === 'flink' ? FLINK_TEMPLATE : SPARK_TEMPLATE;
      const d = await createScript({ name: newName.trim(), datasourceId: newDs, content: tpl });
      setNewDialog(false); setNewName(''); setNewDs(undefined);
      await loadScripts();
      const t = makeTab({
        scriptId: d.id, name: d.name, content: d.content,
        baselineName: d.name, baselineContent: d.content,
        datasourceId: d.datasourceId, params: d.params ?? [],
      });
      setTabs((ts) => [...ts, t]);
      setActiveKey(t.key);
    } catch (e: any) { Message.error(e?.response?.data?.message ?? '创建失败'); }
  }, [newName, newDs, dsList, loadScripts]);

  const handleDelete = useCallback(async (id: number) => {
    if (!window.confirm('确定删除该脚本及其运行历史？')) return;
    try {
      await deleteScript(id);
      // 同步关闭已打开的页签（脏则确认一次）
      const opened = tabsRef.current.filter((t) => t.scriptId === id);
      if (opened.some(isDirty)) {
        Modal.confirm({
          title: '有未保存的修改',
          content: '脚本已被删除，相关页签的未保存内容将丢弃，确定继续吗？',
          okText: '丢弃并关闭页签',
          okButtonProps: { status: 'warning' },
          onOk: () => opened.forEach((t) => closeTab(t, true)),
        });
      } else {
        opened.forEach((t) => closeTab(t, true));
      }
      await loadScripts();
    } catch (e: any) { Message.error(e?.response?.data?.message ?? '删除失败'); }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loadScripts]);

  const handleRename = useCallback(async () => {
    if (renameId == null || !renameValue.trim()) return;
    try {
      await updateScript(renameId, { name: renameValue.trim() });
      setRenameId(null);
      await loadScripts();
      setTabs((ts) => ts.map((t) => (t.scriptId === renameId ? { ...t, name: renameValue.trim(), baselineName: renameValue.trim() } : t)));
    } catch (e: any) { Message.error(e?.response?.data?.message ?? '重命名失败'); }
  }, [renameId, renameValue, loadScripts]);

  // HDFS tree lazy load（跟随活跃页签的数据源）
  const loadHdfsNode = useCallback(async (dsId: number, path: string): Promise<any[]> => {
    try {
      const entries = await listHdfs(dsId, path);
      return entries.map((e: HdfsEntry) => ({
        key: e.path,
        title: e.name + (e.isDir ? '/' : ''),
        isLeaf: !e.isDir,
        icon: e.isDir ? undefined : <IconFile />,
      }));
    } catch { return []; }
  }, []);

  useEffect(() => {
    if (isSpark && active?.datasourceId) {
      setHdfsLoading(true);
      loadHdfsNode(active.datasourceId, '/').then((nodes) => { setHdfsTree(nodes); setHdfsLoading(false); }).catch(() => setHdfsLoading(false));
    } else { setHdfsTree([]); }
  }, [isSpark, active?.datasourceId, loadHdfsNode]);

  const insertAtCursor = useCallback((text: string) => {
    const key = activeRef.current;
    setTabs((ts) => ts.map((t) => (t.key === key ? { ...t, content: t.content + '\n' + text } : t)));
  }, []);

  if (loading) return <div style={{ padding: 40 }}><Spin /></div>;

  function tabTitle(t: PyTab) {
    return t.name.trim() || '未命名脚本';
  }

  return (
    <div className="page-fill">
      {/* Left: script list + HDFS tree */}
      <div style={{ width: 250, flexShrink: 0, display: 'flex', flexDirection: 'column', gap: 16, minHeight: 0 }}>
        <Card title="脚本" className="card-fill" style={{ flexShrink: 0, maxHeight: 300 }} bodyStyle={{ padding: '4px 0', overflowY: 'auto' }} extra={<Button size="mini" type="primary" icon={<IconPlus />} onClick={() => setNewDialog(true)}>新建</Button>}>
          {scripts.length === 0 && <Text type="secondary" style={{ display: 'block', padding: '8px 16px' }}>还没有脚本，可直接编写并运行，保存后加入列表</Text>}
            {scripts.map((s) => {
              const selected = tabs.some((t) => t.scriptId === s.id && t.key === activeKey);
              return (
                <div
                  key={s.id}
                  className={`script-item${selected ? ' selected' : ''}`}
                  style={{ background: selected ? 'var(--color-primary-light-1)' : undefined }}
                  onClick={() => openScript(s)}
                  onDoubleClick={(e) => { e.stopPropagation(); setRenameId(s.id); setRenameValue(s.name); }}
                  title="双击重命名"
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
                    {renameId === s.id ? (
                      <Input size="mini" value={renameValue} autoFocus
                        onClick={(e) => e.stopPropagation()}
                        onChange={setRenameValue}
                        onKeyDown={(e) => { if (e.key === 'Enter') handleRename(); if (e.key === 'Escape') setRenameId(null); }}
                        onBlur={handleRename}
                        style={{ flex: 1, minWidth: 0 }} />
                    ) : (
                      <span style={{
                        flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                        color: selected ? 'var(--Datalore-primary)' : undefined,
                        fontWeight: selected ? 600 : 400,
                      }}>
                        {s.name}
                      </span>
                    )}
                    <button
                      type="button"
                      className="script-item-del"
                      title="删除脚本"
                      style={{ border: 0, padding: 0, background: 'transparent', cursor: 'pointer', lineHeight: 1 }}
                      onClick={(e) => { e.stopPropagation(); handleDelete(s.id); }}
                    >
                      <IconDelete />
                    </button>
                  </div>
                  <div style={{ fontSize: 11, color: 'var(--color-text-3)' }}>
                    {s.updatedAt.replace('T', ' ').slice(5, 16)}
                  </div>
                </div>
              );
            })}
        </Card>
        {isSpark && active?.datasourceId != null && (
          <Card title="HDFS 文件" className="card-fill" style={{ flex: 1, minHeight: 0 }} bodyStyle={{ padding: 4, overflowY: 'auto' }}>
            <Spin loading={hdfsLoading} style={{ display: 'block' }}>
              {hdfsTree.length === 0 && !hdfsLoading && <Text type="secondary">无法加载目录</Text>}
              <Tree
                treeData={hdfsTree}
                size="small"
                loadMore={async (node) => {
                  const children = await loadHdfsNode(active!.datasourceId!, (node as any)._key as string);
                  (node as any).children = children;
                }}
                onSelect={(_, node) => {
                  const path = (node as any)._key as string;
                  if (!(node as any).isLeaf) return;
                  insertAtCursor('spark.read.parquet("' + path + '")');
                }}
              />
            </Spin>
          </Card>
        )}
      </div>

      {/* Right: tab bar + editor + results */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 }}>
        <Card size="small" bodyStyle={{ padding: 8 }} style={{ flex: '0 0 auto' }}>
          {/* 页签栏 */}
          <div
            style={{
              display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'nowrap', overflowX: 'auto',
              borderBottom: '1px solid var(--color-border-2)', paddingBottom: 0, marginBottom: 8,
            }}
          >
            {tabs.map((t) => {
              const selected = t.key === activeKey;
              const dirty = isDirty(t);
              return (
                <div
                  key={t.key}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 4,
                    padding: '5px 10px', borderRadius: '6px 6px 0 0', cursor: 'pointer',
                    whiteSpace: 'nowrap', minWidth: 0, maxWidth: 200,
                    borderBottom: selected ? '2px solid rgb(var(--primary-6))' : '2px solid transparent',
                    background: selected ? 'var(--color-fill-1)' : 'transparent',
                    fontWeight: selected ? 600 : 400,
                  }}
                  onClick={() => setActiveKey(t.key)}
                  onDoubleClick={(e) => { e.stopPropagation(); setRenamingTabKey(t.key); setTabRenameValue(t.name); }}
                  title="双击重命名"
                >
                  {t.running && <IconLoading spin style={{ fontSize: 12, color: 'rgb(var(--primary-6))' }} />}
                  {renamingTabKey === t.key ? (
                    <Input
                      size="mini"
                      value={tabRenameValue}
                      autoFocus
                      onClick={(e) => e.stopPropagation()}
                      onChange={(v) => setTabRenameValue(v)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') commitTabRename(t);
                        if (e.key === 'Escape') setRenamingTabKey(null);
                      }}
                      onBlur={() => commitTabRename(t)}
                      style={{ width: 120 }}
                    />
                  ) : (
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 130 }}>
                      {tabTitle(t)}
                      {dirty && <span style={{ color: 'rgb(var(--orange-6))', marginLeft: 4 }} title="有未保存的修改">●</span>}
                    </span>
                  )}
                  <button
                    type="button"
                    title="关闭页签"
                    aria-label={`关闭页签：${tabTitle(t)}`}
                    style={{ border: 0, padding: 0, background: 'transparent', cursor: 'pointer', lineHeight: 1, display: 'inline-flex' }}
                    onClick={(e) => { e.stopPropagation(); closeTab(t); }}
                  >
                    <IconClose style={{ fontSize: 12, color: 'var(--color-text-3)' }} />
                  </button>
                </div>
              );
            })}
            <Button
              size="mini"
              icon={<IconPlus />}
              title="新建页签"
              onClick={newTab}
              style={{ marginLeft: 4, marginBottom: 2, flexShrink: 0 }}
            />
          </div>

          {active && (
            <>
              <Space size="medium" align="center">
                <Select
                  placeholder="选择数据源"
                  style={{ width: 200 }}
                  value={active.datasourceId}
                  onChange={(v) => updateTab(active.key, { datasourceId: v as number })}
                  options={computeDs.map((d) => ({ value: d.id, label: `${d.name}（${d.type === 'spark' ? 'Spark' : 'Flink'}）` }))}
                  disabled={active.scriptId != null}
                />
                {active.running && active.runStatus && (active.runStatus.status === 'RUNNING' || active.runStatus.status === 'QUEUED') ? (
                  <Button
                    type="primary"
                    status="danger"
                    icon={<IconStop />}
                    onClick={() => stopTab(active)}
                  >
                    停止
                  </Button>
                ) : (
                  <Button type="primary" icon={<IconPlayArrow />} loading={active.running} disabled={active.datasourceId == null} onClick={() => handleRun(active)}>
                    运行
                  </Button>
                )}
                <Button icon={<IconSave />} loading={saving} onClick={() => saveTab(active)}>
                  保存
                </Button>
                {isDirty(active) ? <Tag color="orange">未保存</Tag> : active.scriptId != null ? <Tag color="green">已保存</Tag> : null}
              </Space>
            </>
          )}
        </Card>

        {active && (
          <>
            <Card size="small" style={{ flex: '0 0 auto' }}>
              <div style={{ height: editorH }}>
                <Editor
                  language="python"
                  theme="vs"
                  key={active.key}
                  value={active.content}
                  onChange={(v) => updateTab(active.key, { content: v ?? '' })}
                  onMount={(editor, monaco) => {
                    editorRef.current = editor;
                    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => handleRun(active));
                    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => saveTab(active));
                  }}
                  options={{
                    minimap: { enabled: false },
                    fontSize: 13,
                    lineNumbers: 'on',
                    scrollBeyondLastLine: false,
                    wordWrap: 'on',
                    tabSize: 4,
                  }}
                  height={editorH}
                />
              </div>
            </Card>

            <Card size="small" title={active.runStatus ? `运行 #${active.runStatus.runId}` : '输出'} extra={active.runStatus ? <StatusTag status={active.runStatus.status} /> : undefined}
              style={{ flex: '0 0 auto', maxHeight: 300, overflow: 'auto' }}>
              <pre ref={logRef} style={{
                margin: 0, padding: 8, background: 'var(--color-fill-1)', borderRadius: 4,
                fontSize: 12, lineHeight: 1.5, whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                minHeight: 60, maxHeight: 200, overflowY: 'auto',
              }}>
                {active.runLogs || '点击"运行"查看脚本输出'}
              </pre>
              {active.runStatus?.error && (
                <div style={{ marginTop: 8, color: 'var(--danger-6)' }}>
                  <Text type="error">{active.runStatus.error}</Text>
                </div>
              )}
            </Card>

            {active.history.length > 0 && (
              <Card size="small" title="历史运行" style={{ flex: '0 0 auto' }}>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                  {active.history.slice(0, 10).map((h) => (
                    <Space key={h.id} size={4}>
                      <Text style={{ fontSize: 12 }}>#{h.id}</Text>
                      <StatusTag status={h.status} />
                    </Space>
                  ))}
                </div>
              </Card>
            )}
          </>
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

      {/* Save new script modal（临时页签保存即命名） */}
      <Modal title="保存脚本" visible={saveDialogTab != null} onCancel={() => setSaveDialogTab(null)}
        onOk={handleSaveAsNew} okText="保存" cancelText="取消">
        <Input placeholder="脚本名称" value={saveName} onChange={setSaveName} />
      </Modal>

      {/* Parameter modal */}
      <Modal title="运行参数" visible={paramModalTab != null} onCancel={() => setParamModalTab(null)}
        onOk={handleRunWithParams} okText="运行" cancelText="取消">
        <Space direction="vertical" style={{ width: '100%' }}>
          {(() => {
            const t = tabs.find((x) => x.key === paramModalTab);
            if (!t) return null;
            const ps = paramModalAdhoc ? clientParams(t.content) : t.params;
            return ps.map((p) => (
              <Input key={p} addBefore={p} placeholder="值" value={t.paramValues[p] ?? ''}
                onChange={(v) => updateTab(t.key, { paramValues: { ...t.paramValues, [p]: v } })} />
            ));
          })()}
        </Space>
      </Modal>
    </div>
  );
}