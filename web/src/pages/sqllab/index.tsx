import { useCallback, useEffect, useRef, useState } from 'react';
import { Editor } from '@monaco-editor/react';
import '../../monacoSetup';
import {
  Button, Card, Input, Message, Popconfirm, Select, Space, Spin, Table, Tag, Typography,
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
  const [dsList, setDsList] = useState<DataSourceInfo[]>([]);
  const [datasourceId, setDatasourceId] = useState<number | undefined>();
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<QueryResult | null>(null);
  const [error, setError] = useState('');
  const runRef = useRef<() => void>(() => {});

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

  function newScript() {
    setCurrentId(null);
    setName('');
    setContent('');
    setResult(null);
    setError('');
  }

  async function openScript(s: SqlScriptInfo) {
    try {
      const d = await getScript(s.id);
      setCurrentId(d.id);
      setName(d.name);
      setContent(d.content);
      setResult(null);
      setError('');
    } catch (e: any) {
      Message.error(e.response?.data?.message || '加载脚本失败');
    }
  }

  async function save() {
    try {
      if (currentId == null) {
        if (!name.trim()) {
          Message.warning('请先在上方输入脚本名称');
          return;
        }
        const r = await createScript(name.trim(), content);
        setCurrentId(r.id);
        Message.success('脚本已保存');
        reloadScripts();
      } else {
        await updateScript(currentId, name.trim() || undefined, content);
        Message.success('已保存');
        reloadScripts();
      }
    } catch (e: any) {
      Message.error(e.response?.data?.message || '保存失败');
    }
  }

  async function removeScript(s: SqlScriptInfo) {
    try {
      await deleteScript(s.id);
      if (currentId === s.id) newScript();
      reloadScripts();
    } catch (e: any) {
      Message.error(e.response?.data?.message || '删除失败');
    }
  }

  function exportCsv() {
    if (!result || !result.isQuery) return;
    const esc = (v: string) => `"${(v ?? '').replace(/"/g, '""')}"`;
    const lines = [
      result.columns.map(esc).join(','),
      ...result.rows.map((row) => row.map(esc).join(',')),
    ];
    const blob = new Blob(['' + lines.join('\n')], { type: 'text/csv;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${(name || 'query').replace(/[\\/:*?"<>|]/g, '_')}.csv`;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  return (
    <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
      <Card
        title="我的脚本"
        style={{ width: 240, flexShrink: 0 }}
        extra={
          <Button size="mini" type="primary" icon={<IconPlus />} onClick={newScript}>
            新建
          </Button>
        }
      >
        {scripts.length === 0 ? (
          <Typography.Text type="secondary">还没有保存的脚本</Typography.Text>
        ) : (
          scripts.map((s) => (
            <div
              key={s.id}
              onClick={() => openScript(s)}
              style={{
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                padding: '8px 6px', cursor: 'pointer', borderRadius: 6,
                background: currentId === s.id ? 'var(--color-primary-light-1)' : undefined,
                color: currentId === s.id ? 'var(--daaspro-primary)' : undefined,
                fontWeight: currentId === s.id ? 600 : 400,
              }}
            >
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {s.name}
              </span>
              <Popconfirm title="删除该脚本？" onOk={() => removeScript(s)}>
                <IconDelete style={{ color: 'var(--color-text-3)' }} onClick={(e) => e.stopPropagation()} />
              </Popconfirm>
            </div>
          ))
        )}
      </Card>

      <Card style={{ flex: 1, minWidth: 0 }} bodyStyle={{ padding: 12 }}>
        <Space style={{ marginBottom: 10 }} wrap>
          <Input
            value={name}
            onChange={setName}
            placeholder="脚本名称（保存用）"
            style={{ width: 220 }}
          />
          <Select
            style={{ width: 200 }}
            value={datasourceId}
            onChange={(v) => setDatasourceId(v as number)}
            placeholder="选择数据源"
            options={dsList.map((d) => ({
              value: d.id,
              label: d.builtin ? `${d.name}（内置，可写）` : `${d.name}（${d.type}，只读）`,
            }))}
          />
          <Button type="primary" icon={<IconPlayArrow />} loading={running} onClick={() => runRef.current()}>
            运行 (Ctrl+Enter)
          </Button>
          <Button icon={<IconSave />} onClick={save}>
            保存
          </Button>
          {result?.isQuery && (
            <Button icon={<IconDownload />} onClick={exportCsv}>
              导出 CSV
            </Button>
          )}
        </Space>

        <div style={{ border: '1px solid var(--color-border-2)', borderRadius: 8, overflow: 'hidden' }}>
          <Editor
            height="240px"
            language="sql"
            value={content}
            onChange={(v) => setContent(v ?? '')}
            loading={<Spin style={{ margin: 40 }} />}
            onMount={(editor, monaco) => {
              editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => runRef.current());
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
