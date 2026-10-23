import { useCallback, useEffect, useRef, useState } from 'react';
import { Editor } from '@monaco-editor/react';
import '../../monacoSetup';
import {
  Button, Card, Input, Message, Modal, Select, Space, Spin, Table, Tag, Typography,
} from '@arco-design/web-react';
import {
  IconClose, IconDelete, IconDownload, IconLoading, IconPlayArrow, IconPlus, IconSave, IconStop,
} from '@arco-design/web-react/icon';
import { QueryResult, SqlScriptInfo, cancelSql, createScript, deleteScript, getScript, listScripts, runSql, updateScript } from '../../api/sqllab';
import { DataSourceInfo, isRelationalDs, listDatasources } from '../../api/datasource';

const TABS_KEY = 'Datalore.sqllab.tabs';
const MAX_TABS = 20;

interface SqlTab {
  key: string;
  scriptId: number | null;
  name: string;
  content: string;
  baselineName: string;
  baselineContent: string;
  datasourceId: number | undefined;
  result: QueryResult | null;
  error: string;
  running: boolean;
  runToken?: string;
}

function genKey() {
  return typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID()
    : 'k' + Date.now().toString(36) + Math.random().toString(36).slice(2);
}

function isDirty(t: SqlTab) {
  return t.name !== t.baselineName || t.content !== t.baselineContent;
}

export default function SqlLabPage() {
  const [scripts, setScripts] = useState<SqlScriptInfo[]>([]);
  const [tabs, setTabs] = useState<SqlTab[]>([]);
  const [activeKey, setActiveKey] = useState<string>('');
  const [dsList, setDsList] = useState<DataSourceInfo[]>([]);
  const [scriptsLoading, setScriptsLoading] = useState(true);
  const [scriptsError, setScriptsError] = useState(false);
  const [datasourcesLoading, setDatasourcesLoading] = useState(false);
  const [datasourcesError, setDatasourcesError] = useState(false);
  const [renamingId, setRenamingId] = useState<number | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [renamingTabKey, setRenamingTabKey] = useState<string | null>(null);
  const [tabRenameValue, setTabRenameValue] = useState('');
  const [saveDialogTab, setSaveDialogTab] = useState<string | null>(null);
  const [saveName, setSaveName] = useState('');
  const runRef = useRef<() => void>(() => {});
  const saveRef = useRef<() => void>(() => {});
  const editorRef = useRef<import('monaco-editor').editor.IStandaloneCodeEditor | null>(null);
  const tabsRef = useRef<SqlTab[]>([]);
  tabsRef.current = tabs;
  // Monaco 在百分比高度容器里测不到尺寸（会塌成 5px），用视口像素高兜底
  const editorH = Math.round(Math.min(260, Math.max(140, window.innerHeight * 0.38)));

  const active = tabs.find((t) => t.key === activeKey) || null;

  function nextTempName() {
    let max = 0;
    for (const t of tabsRef.current) {
      const m = /^未命名查询 (\d+)$/.exec(t.name.trim());
      if (m) max = Math.max(max, parseInt(m[1], 10));
    }
    return '未命名查询 ' + (max + 1);
  }

  function makeTab(partial: Partial<SqlTab> = {}): SqlTab {
    const name = partial.name ?? nextTempName();
    return {
      key: partial.key ?? genKey(),
      scriptId: partial.scriptId ?? null,
      name,
      content: partial.content ?? '',
      baselineName: partial.baselineName ?? name,
      baselineContent: partial.baselineContent ?? partial.content ?? '',
      datasourceId: partial.datasourceId,
      result: null,
      error: '',
      running: false,
      runToken: undefined,
    };
  }

  function updateTab(key: string, patch: Partial<SqlTab>) {
    setTabs((ts) => ts.map((t) => (t.key === key ? { ...t, ...patch } : t)));
  }

  // ---- 本地持久化：页签全集 + 活跃页签 ----
  useEffect(() => {
    let restored = false;
    try {
      const raw = localStorage.getItem(TABS_KEY);
      if (raw) {
        const saved = JSON.parse(raw);
        if (Array.isArray(saved.tabs) && saved.tabs.length > 0) {
          const restoredTabs: SqlTab[] = saved.tabs.map((t: Partial<SqlTab>) => ({
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
      const t = makeTab({ name: '未命名查询 1', content: 'select 1;', baselineName: '未命名查询 1', baselineContent: 'select 1;' });
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
          baselineName: t.baselineName, baselineContent: t.baselineContent, datasourceId: t.datasourceId,
        })),
        activeKey,
      }));
    } catch {
      // 存储失败不影响使用
    }
  }, [tabs, activeKey]);

  // automaticLayout 在该 webview 内不生效：挂载后与窗口变化时手动重新测量
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

  const loadScripts = useCallback(async () => {
    setScriptsLoading(true);
    setScriptsError(false);
    try {
      setScripts(await listScripts());
    } catch {
      setScriptsError(true);
    } finally {
      setScriptsLoading(false);
    }
  }, []);

  const loadDatasources = useCallback(async () => {
    setDatasourcesLoading(true);
    setDatasourcesError(false);
    try {
      const list = await listDatasources();
      setDsList(list.filter((d) => isRelationalDs(d.type)));
    } catch {
      setDatasourcesError(true);
    } finally {
      setDatasourcesLoading(false);
    }
  }, []);

  useEffect(() => {
    loadScripts();
    loadDatasources();
  }, [loadDatasources, loadScripts]);

  const reloadScripts = loadScripts;

  // ---- 运行 / 停止 ----
  async function runTab(t: SqlTab) {
    if (!t.content.trim()) {
      Message.warning('请输入 SQL');
      return;
    }
    const token = genKey();
    updateTab(t.key, { running: true, runToken: token, error: '' });
    try {
      const r = await runSql(t.datasourceId, t.content, token);
      updateTab(t.key, { running: false, runToken: undefined, result: r });
    } catch (e: any) {
      const msg = e.response?.data?.message || '执行失败';
      updateTab(t.key, { running: false, runToken: undefined, result: null, error: msg });
    }
  }
  runRef.current = () => {
    if (active) runTab(active);
  };

  function stopTab(t: SqlTab) {
    cancelSql(t.runToken)
      .catch((e: any) => Message.error(e.response?.data?.message || '停止失败'))
      .finally(() => {
        // 取消后由被中断的 run 请求收尾更新状态
      });
  }

  // ---- 保存 / 改名 ----
  async function saveTab(t: SqlTab) {
    if (t.scriptId == null) {
      setSaveName(t.name.trim());
      setSaveDialogTab(t.key);
      return;
    }
    await doSaveTab(t, t.name.trim(), t.scriptId);
  }
  saveRef.current = () => {
    if (active) saveTab(active);
  };

  async function doSaveTab(t: SqlTab, n: string, id: number | null) {
    if (!n) {
      Message.warning('请输入脚本名称');
      return;
    }
    try {
      if (id == null) {
        const r = await createScript(n, t.content);
        updateTab(t.key, { scriptId: r.id, name: n, baselineName: n, baselineContent: t.content });
      } else {
        await updateScript(id, n, t.content);
        updateTab(t.key, { name: n, baselineName: n, baselineContent: t.content });
      }
      Message.success('已保存');
      reloadScripts();
    } catch (e: any) {
      Message.error(e.response?.data?.message || '保存失败');
    }
  }

  /** 双击页签改名：临时页签只改本地标题；已保存页签直接落库 */
  function commitTabRename(t: SqlTab) {
    const n = tabRenameValue.trim();
    setRenamingTabKey(null);
    if (!n || n === t.name) return;
    if (t.scriptId == null) {
      updateTab(t.key, { name: n });
      return;
    }
    updateScript(t.scriptId, n, undefined)
      .then(() => {
        updateTab(t.key, { name: n, baselineName: n });
        Message.success('已重命名');
        reloadScripts();
      })
      .catch((e: any) => Message.error(e.response?.data?.message || '重命名失败'));
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

  function closeTab(target: SqlTab, force = false) {
    const doClose = () => {
      setTabs((ts) => {
        const idx = ts.findIndex((t) => t.key === target.key);
        const next = ts.filter((t) => t.key !== target.key);
        if (next.length === 0) {
          const t = makeTab();
          setActiveKey(t.key);
          return [t];
        }
        if (target.key === activeKey) {
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

  async function openScript(s: SqlScriptInfo) {
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
      const d = await getScript(s.id);
      const t = makeTab({
        scriptId: d.id,
        name: d.name,
        content: d.content,
        baselineName: d.name,
        baselineContent: d.content,
      });
      setTabs((ts) => [...ts, t]);
      setActiveKey(t.key);
    } catch (e: any) {
      Message.error(e.response?.data?.message || '加载脚本失败');
    }
  }

  function commitRename(s: SqlScriptInfo) {
    const n = renameValue.trim();
    setRenamingId(null);
    if (!n || n === s.name) return;
    // 同步已打开页签的标题（内容不变，直接落库）
    updateScript(s.id, n, undefined)
      .then(() => {
        Message.success('已重命名');
        reloadScripts();
        setTabs((ts) => ts.map((t) => (t.scriptId === s.id ? { ...t, name: n, baselineName: n } : t)));
      })
      .catch((e: any) => Message.error(e.response?.data?.message || '重命名失败'));
  }

  function confirmDelete(s: SqlScriptInfo) {
    Modal.confirm({
      title: '删除脚本',
      content: `确定删除「${s.name}」吗？`,
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        try {
          await deleteScript(s.id);
          // 同步关闭已打开的页签（脏则确认一次）
          const opened = tabsRef.current.filter((t) => t.scriptId === s.id);
          const anyDirty = opened.some(isDirty);
          const afterDelete = () => {
            opened.forEach((t) => closeTab(t, true));
            reloadScripts();
          };
          if (anyDirty) {
            Modal.confirm({
              title: '有未保存的修改',
              content: '脚本已被删除，相关页签的未保存内容将丢弃，确定继续吗？',
              okText: '丢弃并关闭页签',
              okButtonProps: { status: 'warning' },
              onOk: afterDelete,
            });
          } else {
            afterDelete();
          }
        } catch (e: any) {
          Message.error(e.response?.data?.message || '删除失败');
        }
      },
    });
  }

  function exportCsv() {
    if (!active || !active.result || !active.result.isQuery) return;
    const r = active.result;
    const esc = (v: string) => '"' + (v ?? '').replace(/"/g, '""') + '"';
    const lines = [
      r.columns.map(esc).join(','),
      ...r.rows.map((row) => row.map(esc).join(',')),
    ];
    const blob = new Blob(['\ufeff' + lines.join('\n')], { type: 'text/csv;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    const fname = (active.name || 'query').replace(/[\\/:*?"<>|]/g, '_');
    a.download = fname + '.csv';
    a.click();
    URL.revokeObjectURL(a.href);
  }

  function tabTitle(t: SqlTab) {
    return t.name.trim() || '未命名查询';
  }

  return (
    <div className="page-fill">
      <Card
        title="脚本"
        className="card-fill"
        style={{ width: 250, flexShrink: 0 }}
        bodyStyle={{ padding: '4px 0', overflowY: 'auto' }}
        extra={
          <Button size="mini" type="primary" icon={<IconPlus />} onClick={newTab}>
            新建
          </Button>
        }
      >
        {scriptsLoading ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 16px' }}>
            <Spin size={16} />
            <Typography.Text type="secondary">正在加载脚本…</Typography.Text>
          </div>
        ) : scriptsError ? (
          <div className="page-error" role="alert">
            <Typography.Text>脚本列表加载失败，请重试。</Typography.Text>
            <div style={{ marginTop: 10 }}>
              <Button size="small" loading={scriptsLoading} onClick={loadScripts}>重试</Button>
            </div>
          </div>
        ) : scripts.length === 0 ? (
          <Typography.Text type="secondary" style={{ display: 'block', padding: '8px 16px' }}>
            还没有脚本，点「新建」开始
          </Typography.Text>
        ) : (
          scripts.map((s) => {
            const selected = tabs.some((t) => t.scriptId === s.id && t.key === activeKey);
            return (
              <div
                key={s.id}
                className={`script-item${selected ? ' selected' : ''}`}
                style={{
                  background: selected ? 'var(--color-primary-light-1)' : undefined,
                }}
                onClick={() => openScript(s)}
                onDoubleClick={(e) => { e.stopPropagation(); setRenamingId(s.id); setRenameValue(s.name); }}
                title="双击重命名"
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
                  {renamingId === s.id ? (
                    <Input
                      size="mini"
                      value={renameValue}
                      autoFocus
                      onClick={(e) => e.stopPropagation()}
                      onChange={(v) => setRenameValue(v)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') commitRename(s);
                        if (e.key === 'Escape') setRenamingId(null);
                      }}
                      onBlur={() => commitRename(s)}
                      style={{ flex: 1, minWidth: 0 }}
                    />
                  ) : (
                    <span
                      style={{
                        flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                        color: selected ? 'var(--Datalore-primary)' : undefined,
                        fontWeight: selected ? 600 : 400,
                      }}
                    >
                      {s.name}
                    </span>
                  )}
                  <button
                    type="button"
                    className="script-item-del"
                    title="删除脚本"
                    aria-label={`删除脚本：${s.name}`}
                    style={{ border: 0, padding: 0, background: 'transparent', cursor: 'pointer', lineHeight: 1 }}
                    onClick={(e) => { e.stopPropagation(); confirmDelete(s); }}
                  >
                    <IconDelete />
                  </button>
                </div>
                <div style={{ fontSize: 11, color: 'var(--color-text-3)' }}>
                  {s.updatedAt.replace('T', ' ').slice(5, 16)}
                </div>
              </div>
            );
          })
        )}
      </Card>

      <Card className="card-fill" style={{ flex: 1, minWidth: 0 }} bodyStyle={{ padding: 12, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        {/* 页签栏 */}
        <div
          style={{
            display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'nowrap', overflowX: 'auto',
            borderBottom: '1px solid var(--color-border-2)', flexShrink: 0, paddingBottom: 0, marginBottom: 10,
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
            <Space style={{ marginBottom: 10, flexShrink: 0 }} wrap>
              <Select
                style={{ width: 200 }}
                value={active.datasourceId}
                onChange={(v) => updateTab(active.key, { datasourceId: v as number })}
                placeholder="选择数据源"
                options={dsList.map((d) => ({
                  value: d.id,
                  label: d.name,
                }))}
                showSearch

              />
              {active.running ? (
                <Button status="danger" icon={<IconStop />} onClick={() => stopTab(active)}>
                  停止
                </Button>
              ) : (
                <Button type="primary" icon={<IconPlayArrow />} onClick={() => runRef.current()}>
                  运行 (Ctrl+Enter)
                </Button>
              )}
              <Button icon={<IconSave />} onClick={() => saveRef.current()}>
                保存 (Ctrl+S)
              </Button>
              {active.result?.isQuery && (
                <Button icon={<IconDownload />} onClick={exportCsv}>
                  导出 CSV
                </Button>
              )}
              {isDirty(active) ? <Tag color="orange">未保存</Tag> : active.scriptId != null ? <Tag color="green">已保存</Tag> : null}
            </Space>

            {datasourcesError && !datasourcesLoading && (
              <div className="page-error" role="alert" style={{ marginBottom: 10, padding: '8px 12px' }}>
                <Typography.Text>数据源加载失败，请重试。</Typography.Text>
                <Button size="small" loading={datasourcesLoading} onClick={loadDatasources} style={{ marginLeft: 10 }}>
                  重试
                </Button>
              </div>
            )}

            <div
              style={{
                border: '1px solid var(--color-border-2)',
                borderRadius: 8,
                overflow: 'hidden',
                flex: '0 0 auto',
                height: editorH,
              }}
            >
              <Editor
                height={editorH}
                language="sql"
                key={active.key}
                value={active.content}
                onChange={(v) => updateTab(active.key, { content: v ?? '' })}
                onMount={(editor, monaco) => {
                  editorRef.current = editor;
                  window.setTimeout(() => editor.layout(), 0);
                  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => runRef.current());
                  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => saveRef.current());
                }}
                options={{ minimap: { enabled: false }, fontSize: 14, scrollBeyondLastLine: false, automaticLayout: true }}
              />
            </div>

            <div className="scroll-y" style={{ flex: 1, marginTop: 14, position: 'relative' }}>
              {active.running && <Spin style={{ display: 'block', margin: '24px auto' }} size={24} />}

              {!active.running && !active.result && !active.error && (
                <div className="page-empty" style={{ marginTop: 12 }}>
                  编写 SQL 后点「运行」查看结果
                </div>
              )}

              {!active.running && active.error && (
                <div className="page-error" style={{ marginTop: 12, whiteSpace: 'pre-wrap' }}>
                  {active.error}
                </div>
              )}

              {active.result && !active.running && (
                <div>
                  <Space style={{ marginBottom: 8 }} wrap>
                    {active.result.isQuery ? (
                      <>
                        <Tag color="arcoblue">{active.result.rowCount} 行</Tag>
                        {active.result.truncated && <Tag color="orange">结果已截断（达到行数上限）</Tag>}
                      </>
                    ) : (
                      <Tag color="green">影响 {active.result.affectedRows} 行</Tag>
                    )}
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      耗时 {active.result.elapsedMs} ms
                    </Typography.Text>
                  </Space>
                  {active.result.isQuery && active.result.rows.length === 0 ? (
                    <div className="page-empty" style={{ marginTop: 12, padding: 12 }}>
                      查询结果为空
                    </div>
                  ) : active.result.isQuery && (
                    <Table
                      size="small"
                      data={active.result.rows.map((row, i) => ({ __i: i, row }))}
                      rowKey="__i"
                      pagination={{ pageSize: 20 }}
                      scroll={{ x: true }}
                      columns={active.result.columns.map((c, idx) => ({
                        title: c,
                        render: (_: unknown, record: { row: string[] }) => record.row[idx] ?? '',
                        ellipsis: true,
                      }))}
                    />
                  )}
                </div>
              )}
            </div>
          </>
        )}
      </Card>

      <Modal
        title="保存脚本"
        visible={saveDialogTab != null}
        onCancel={() => setSaveDialogTab(null)}
        onOk={async () => {
          const t = tabsRef.current.find((x) => x.key === saveDialogTab);
          if (!t) return;
          const n = saveName.trim();
          if (!n) {
            Message.warning('请输入脚本名称');
            return;
          }
          setSaveDialogTab(null);
          await doSaveTab(t, n, null);
        }}
        okText="保存"
      >
        <Input
          value={saveName}
          placeholder="脚本名称"
          onChange={(v) => setSaveName(v)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              const t = tabsRef.current.find((x) => x.key === saveDialogTab);
              const n = saveName.trim();
              if (!t) return;
              if (!n) { Message.warning('请输入脚本名称'); return; }
              setSaveDialogTab(null);
              doSaveTab(t, n, null);
            }
          }}
        />
      </Modal>
    </div>
  );
}