import { useCallback, useEffect, useRef, useState } from 'react';
import { Editor } from '@monaco-editor/react';
import '../../monacoSetup';
import {
  Button, Card, Input, Message, Modal, Select, Space, Spin, Table, Tag, Typography,
} from '@arco-design/web-react';
import {
  IconDelete, IconDownload, IconPlayArrow, IconPlus, IconSave,
} from '@arco-design/web-react/icon';
import { QueryResult, SqlScriptInfo, createScript, deleteScript, getScript, listScripts, runSql, updateScript } from '../../api/sqllab';
import { DataSourceInfo, listDatasources } from '../../api/datasource';

export default function SqlLabPage() {
  const [scripts, setScripts] = useState<SqlScriptInfo[]>([]);
  const [currentId, setCurrentId] = useState<number | null>(null);
  const [name, setName] = useState('');
  const [content, setContent] = useState('select 1;');
  const [baseline, setBaseline] = useState({ name: '', content: 'select 1;' });
  const [dsList, setDsList] = useState<DataSourceInfo[]>([]);
  const [datasourceId, setDatasourceId] = useState<number | undefined>();
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<QueryResult | null>(null);
  const [error, setError] = useState('');
  const runRef = useRef<() => void>(() => {});
  const saveRef = useRef<() => void>(() => {});

  const dirty = name !== baseline.name || content !== baseline.content;

  useEffect(() => {
    listScripts().then(setScripts).catch(() => {});
    listDatasources().then((list) => {
      setDsList(list);
      const builtin = list.find((d) => d.builtin);
      if (builtin) setDatasourceId(builtin.id);
    }).catch(() => {});
  }, []);

  const reloadScripts = useCallback(() => {
    listScripts().then(setScripts).catch(() => {});
  }, []);

  async function run() {
    if (!content.trim()) {
      Message.warning('请输入 SQL');
      return;
    }
    setRunning(true);
    setError('');
    try {
      const r = await runSql(datasourceId, content);
      setResult(r);
    } catch (e: any) {
      setResult(null);
      setError(e.response?.data?.message || '执行失败');
    } finally {
      setRunning(false);
    }
  }
  runRef.current = run;

  async function save() {
    const n = name.trim();
    if (!n) {
      Message.warning('请先填写脚本名称');
      return;
    }
    try {
      if (currentId == null) {
        const r = await createScript(n, content);
        setCurrentId(r.id);
      } else {
        await updateScript(currentId, n, content);
      }
      setBaseline({ name: n, content });
      Message.success('已保存');
      reloadScripts();
    } catch (e: any) {
      Message.error(e.response?.data?.message || '保存失败');
    }
  }
  saveRef.current = save;

  /** 有未保存修改时先确认，再执行切换动作 */
  function guardUnsaved(action: () => void) {
    if (!dirty) {
      action();
      return;
    }
    Modal.confirm({
      title: '有未保存的修改',
      content: '切换后当前编辑内容将被丢弃，确定继续吗？也可以先点「保存」。',
      okText: '放弃修改并切换',
      cancelText: '留在当前',
      okButtonProps: { status: 'warning' },
      onOk: action,
    });
  }

  function newScript() {
    guardUnsaved(() => {
      setCurrentId(null);
      setName('');
      setContent('');
      setBaseline({ name: '', content: '' });
      setResult(null);
      setError('');
    });
  }

  async function openScript(s: SqlScriptInfo) {
    if (s.id === currentId) return;
    guardUnsaved(async () => {
      try {
        const d = await getScript(s.id);
        setName(d.name);
        setContent(d.content);
        setBaseline({ name: d.name, content: d.content });
        setCurrentId(d.id);
        setResult(null);
        setError('');
      } catch (e: any) {
        Message.error(e.response?.data?.message || '加载脚本失败');
      }
    });
  }

  function confirmDelete(s: SqlScriptInfo) {
    Modal.confirm({
      title: '删除脚本',
      content: `确定删除「${s.name}」吗？`,
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        try {
          await deleteScript(s.id);
          if (currentId === s.id) newScript();
          reloadScripts();
        } catch (e: any) {
          Message.error(e.response?.data?.message || '删除失败');
        }
      },
    });
  }

  function exportCsv() {
    if (!result || !result.isQuery) return;
    const esc = (v: string) => `"${(v ?? '').replace(/"/g, '""')}"`;
    const lines = [
      result.columns.map(esc).join(','),
      ...result.rows.map((row) => row.map(esc).join(',')),
    ];
    const blob = new Blob(['\ufeff' + lines.join('\n')], { type: 'text/csv;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${(name || 'query').replace(/[\\/:*?"<>|]/g, '_')}.csv`;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  return (
    <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
      <Card
        title="脚本"
        style={{ width: 250, flexShrink: 0 }}
        bodyStyle={{ padding: '4px 0' }}
        extra={
          <Button size="mini" type="primary" icon={<IconPlus />} onClick={newScript}>
            新建
          </Button>
        }
      >
        {scripts.length === 0 ? (
          <Typography.Text type="secondary" style={{ display: 'block', padding: '8px 16px' }}>
            还没有脚本，点「新建」开始
          </Typography.Text>
        ) : (
          scripts.map((s) => {
            const selected = s.id === currentId;
            return (
              <div
                key={s.id}
                className={`script-item${selected ? ' selected' : ''}`}
                style={{
                  background: selected ? 'var(--color-primary-light-1)' : undefined,
                }}
                onClick={() => openScript(s)}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
                  <span
                    style={{
                      flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                      color: selected ? 'var(--daaspro-primary)' : undefined,
                      fontWeight: selected ? 600 : 400,
                    }}
                  >
                    {s.name}
                    {selected && dirty && (
                      <span style={{ color: 'rgb(var(--orange-6))', marginLeft: 4 }} title="有未保存的修改">●</span>
                    )}
                  </span>
                  <IconDelete
                    className="script-item-del"
                    onClick={(e) => { e.stopPropagation(); confirmDelete(s); }}
                  />
                </div>
                <div style={{ fontSize: 11, color: 'var(--color-text-3)' }}>
                  {s.updatedAt.replace('T', ' ').slice(5, 16)}
                </div>
              </div>
            );
          })
        )}
      </Card>

      <Card style={{ flex: 1, minWidth: 0 }} bodyStyle={{ padding: 12 }}>
        <Space style={{ marginBottom: 10 }} wrap>
          <Input
            value={name}
            onChange={setName}
            placeholder="脚本名称"
            style={{ width: 220 }}
            status={dirty && !name.trim() ? 'warning' : undefined}
          />
          <Select
            style={{ width: 200 }}
            value={datasourceId}
            onChange={(v) => setDatasourceId(v as number)}
            placeholder="选择数据源"
            options={dsList.map((d) => ({
              value: d.id,
              label: d.builtin ? `${d.name}（内置，可写入）` : `${d.name}（${d.type}，只读）`,
            }))}
          />
          <Button type="primary" icon={<IconPlayArrow />} loading={running} onClick={() => runRef.current()}>
            运行 (Ctrl+Enter)
          </Button>
          <Button icon={<IconSave />} onClick={() => saveRef.current()}>
            保存 (Ctrl+S)
          </Button>
          {result?.isQuery && (
            <Button icon={<IconDownload />} onClick={exportCsv}>
              导出 CSV
            </Button>
          )}
          {dirty ? <Tag color="orange">未保存</Tag> : currentId != null ? <Tag color="green">已保存</Tag> : null}
        </Space>

        <div style={{ border: '1px solid var(--color-border-2)', borderRadius: 8, overflow: 'hidden' }}>
          <Editor
            height="240px"
            language="sql"
            value={content}
            onChange={(v) => setContent(v ?? '')}
            onMount={(editor, monaco) => {
              editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => runRef.current());
              editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => saveRef.current());
            }}
            options={{ minimap: { enabled: false }, fontSize: 14, scrollBeyondLastLine: false }}
          />
        </div>

        {error && (
          <Typography.Text type="error" style={{ display: 'block', marginTop: 12, whiteSpace: 'pre-wrap' }}>
            {error}
          </Typography.Text>
        )}

        {running && <Spin style={{ margin: '20px auto', display: 'block' }} size={24} />}

        {result && !running && (
          <div style={{ marginTop: 14 }}>
            <Space style={{ marginBottom: 8 }} wrap>
              {result.isQuery ? (
                <>
                  <Tag color="arcoblue">{result.rowCount} 行</Tag>
                  {result.truncated && <Tag color="orange">结果已截断（达到行数上限）</Tag>}
                </>
              ) : (
                <Tag color="green">影响 {result.affectedRows} 行</Tag>
              )}
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                耗时 {result.elapsedMs} ms
              </Typography.Text>
            </Space>
            {result.isQuery && (
              <Table
                size="small"
                data={result.rows.map((row, i) => ({ __i: i, row }))}
                rowKey="__i"
                pagination={{ pageSize: 20 }}
                scroll={{ x: true }}
                columns={result.columns.map((c, idx) => ({
                  title: c,
                  render: (_: unknown, record: { row: string[] }) => record.row[idx] ?? '',
                  ellipsis: true,
                  width: 160,
                }))}
              />
            )}
          </div>
        )}
      </Card>
    </div>
  );
}
