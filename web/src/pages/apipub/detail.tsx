import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Button, Checkbox, Descriptions, Input, InputNumber, Message, Modal, Space, Switch, Tag, Typography,
} from '@arco-design/web-react';
import {
  ApiAppInfo, ApiPubInfo, ColumnDef, datasetColumns, deleteApi, getApi, getApiGrants,
  listApps, revealAppSecret, setApiGrants, updateApi,
} from '../../api/apipub';

const CheckboxGroup = Checkbox.Group;

export default function ApiPubDetailPage() {
  const nav = useNavigate();
  const { id } = useParams();
  const apiId = Number(id);
  const [api, setApi] = useState<ApiPubInfo | null>(null);
  const [cols, setCols] = useState<ColumnDef[]>([]);
  const [apps, setApps] = useState<ApiAppInfo[]>([]);
  const [appsLoading, setAppsLoading] = useState(false);
  const [appsLoadError, setAppsLoadError] = useState(false);
  const [grantIds, setGrantIds] = useState<number[]>([]);
  const [savingCfg, setSavingCfg] = useState(false);
  const [savingGrant, setSavingGrant] = useState(false);
  const [name, setName] = useState('');
  const [columns, setColumns] = useState<string[]>([]);
  const [filterColumns, setFilterColumns] = useState<string[]>([]);
  const [maxRows, setMaxRows] = useState(1000);

  const loadApps = useCallback(async () => {
    setAppsLoading(true);
    setAppsLoadError(false);
    try {
      setApps(await listApps());
    } catch {
      setAppsLoadError(true);
    } finally {
      setAppsLoading(false);
    }
  }, []);

  const reload = useCallback(async () => {
    const a = await getApi(apiId);
    setApi(a);
    setName(a.name);
    setColumns(a.columns);
    setFilterColumns(a.filterColumns);
    setMaxRows(a.maxRows);
    try {
      setCols(await datasetColumns(a.datasetId));
    } catch {
      setCols([]);
    }
    await loadApps();
    setGrantIds(await getApiGrants(apiId));
  }, [apiId, loadApps]);

  useEffect(() => {
    reload().catch(() => Message.error('加载失败'));
  }, [reload]);

  async function onSaveCfg() {
    setSavingCfg(true);
    try {
      setApi(await updateApi(apiId, { name, columns, filterColumns, maxRows }));
      Message.success('已保存');
    } catch (e: any) {
      Message.error(e.response?.data?.message || '保存失败');
    } finally {
      setSavingCfg(false);
    }
  }

  async function onToggleStatus(checked: boolean) {
    try {
      setApi(await updateApi(apiId, { status: checked ? 1 : 0 }));
      Message.success(checked ? '已启用' : '已停用');
    } catch (e: any) {
      Message.error(e.response?.data?.message || '操作失败');
    }
  }

  async function onSaveGrants() {
    setSavingGrant(true);
    try {
      await setApiGrants(apiId, grantIds);
      Message.success('授权已更新');
      reload();
    } catch (e: any) {
      Message.error(e.response?.data?.message || '保存失败');
    } finally {
      setSavingGrant(false);
    }
  }

  async function showSecret(app: ApiAppInfo) {
    try {
      const s = await revealAppSecret(app.id);
      Modal.info({
        title: `「${app.name}」凭证`,
        content: (
          <div style={{ wordBreak: 'break-all' }}>
            <p style={{ fontFamily: 'monospace' }}>AppKey: {app.appKey}</p>
            <p style={{ fontFamily: 'monospace' }}>AppSecret: {s}</p>
          </div>
        ),
      });
    } catch (e: any) {
      Message.error(e.response?.data?.message || '获取失败');
    }
  }

  function onDelete() {
    Modal.confirm({
      title: '删除 API',
      content: `确定删除「${api?.name}」吗？授权记录一并删除。`,
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        await deleteApi(apiId);
        nav('/apipub');
      },
    });
  }

  if (!api) return null;

  const origin = window.location.origin;
  const sampleFilter = api.filterColumns.length ? `&${api.filterColumns[0]}=值` : '';
  const curl = [
    `curl "${origin}/api/open/${api.path}?page=1&size=100${sampleFilter}" \\`,
    `  -H "X-Api-Key: <AppKey>" -H "X-Api-Secret: <AppSecret>"`,
  ].join('\n');

  return (
    <div className="page-shell">
      <div className="page-header">
        <div className="page-header-main">
          <Button type="text" onClick={() => nav('/apipub')}>← 返回列表</Button>
          <Space align="center" wrap style={{ marginTop: 8 }}>
            <Typography.Title heading={4} style={{ margin: 0 }}>{api.name}</Typography.Title>
            <Tag color={api.status === 1 ? 'green' : 'gray'}>
              {api.status === 1 ? '启用中' : '已停用'}
            </Tag>
          </Space>
          <Space wrap style={{ marginTop: 8 }}>
            <Typography.Text type="secondary">端点</Typography.Text>
            <code>{origin}/api/open/{api.path}</code>
            <Tag color="blue">GET</Tag>
            <Typography.Text type="secondary">数据集</Typography.Text>
            <Typography.Text>{api.datasetName}（{api.datasetId}）</Typography.Text>
          </Space>
        </div>
        <div className="page-header-actions">
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <Switch size="small" checked={api.status === 1} onChange={onToggleStatus} />
            启停
          </span>
          <Button status="danger" size="mini" onClick={onDelete}>删除</Button>
        </div>
      </div>

      <section className="page-section natural-height" aria-labelledby="api-config-title">
        <Typography.Title id="api-config-title" heading={6} style={{ margin: '0 0 14px' }}>
          调用配置
        </Typography.Title>
        <Descriptions
          column={1}
          data={[
            { label: '路径', value: <code>{origin}/api/open/{api.path}</code> },
            { label: '数据集', value: `${api.datasetName}（${api.datasetId}）` },
          ]}
          style={{ marginBottom: 16 }}
        />
        <Space style={{ width: '100%', marginBottom: 8 }} wrap>
          <Input value={name} onChange={setName} style={{ width: 220 }} placeholder="API 名称" />
          <InputNumber value={maxRows} onChange={setMaxRows} min={1} max={10000} style={{ width: 140 }} />
          <span style={{ color: 'var(--color-text-3)', fontSize: 12, alignSelf: 'center' }}>行数上限</span>
          <Button type="primary" size="small" loading={savingCfg} onClick={onSaveCfg}>保存配置</Button>
        </Space>
        {cols.length > 0 && (
          <>
            <Typography.Text type="secondary" style={{ display: 'block', margin: '8px 0 4px', fontSize: 12 }}>
              暴露列
            </Typography.Text>
            <CheckboxGroup
              value={columns}
              onChange={(v) => {
                setColumns(v as string[]);
                setFilterColumns((filterColumns || []).filter((f) => (v as string[]).includes(f)));
              }}
              options={cols.map((c) => ({ label: `${c.name} (${c.type})`, value: c.name }))}
            />
            <Typography.Text type="secondary" style={{ display: 'block', margin: '12px 0 4px', fontSize: 12 }}>
              可过滤列（等值，须为暴露列）
            </Typography.Text>
            <CheckboxGroup
              value={filterColumns}
              onChange={(v) => setFilterColumns(v as string[])}
              options={cols.filter((c) => columns.includes(c.name)).map((c) => ({ label: c.name, value: c.name }))}
            />
          </>
        )}
      </section>

      <section className="page-section natural-height" aria-labelledby="api-grants-title">
        <div className="page-header">
          <div className="page-header-main">
            <Typography.Title id="api-grants-title" heading={6} style={{ margin: 0 }}>
              授权应用
            </Typography.Title>
            <Typography.Text type="secondary" style={{ display: 'block', marginTop: 6, fontSize: 12 }}>
              未授权的应用调用该 API 会收到 404。
            </Typography.Text>
          </div>
          <div className="page-header-actions">
            <Button size="mini" onClick={() => nav('/apipub/apps')}>应用管理</Button>
            <Button size="mini" type="primary" loading={savingGrant} onClick={onSaveGrants}>保存授权</Button>
          </div>
        </div>
        <div style={{ marginTop: 16 }}>
          {appsLoadError && !appsLoading ? (
            <div className="page-error" role="alert">
              <Typography.Text>应用列表加载失败，请重试。</Typography.Text>
              <div style={{ marginTop: 10 }}>
                <Button size="mini" onClick={loadApps}>重试</Button>
              </div>
            </div>
          ) : appsLoading ? (
            <Typography.Text type="secondary">正在加载应用…</Typography.Text>
          ) : apps.length === 0 ? (
            <div className="page-empty">
              <Typography.Text>还没有应用，先创建一个调用方应用。</Typography.Text>
              <div style={{ marginTop: 10 }}>
                <Button size="mini" type="primary" onClick={() => nav('/apipub/apps')}>
                  去创建应用
                </Button>
              </div>
            </div>
          ) : (
            <CheckboxGroup value={grantIds} onChange={(v) => setGrantIds(v as number[])}>
              <Space direction="vertical" style={{ width: '100%' }}>
                {apps.map((a) => (
                  <Checkbox key={a.id} value={a.id}>
                    <span>{a.name}</span>
                    <Typography.Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>
                      {a.appKey}
                    </Typography.Text>
                    <Button
                      type="text"
                      size="mini"
                      title="查看应用密钥"
                      aria-label={`查看 ${a.name} 的应用密钥`}
                      onClick={(e) => { e.preventDefault(); showSecret(a); }}
                    >
                      查看密钥
                    </Button>
                  </Checkbox>
                ))}
              </Space>
            </CheckboxGroup>
          )}
        </div>
      </section>

      <section className="page-section natural-height" aria-labelledby="api-example-title">
        <Typography.Title id="api-example-title" heading={6} style={{ margin: '0 0 14px' }}>
          调用示例
        </Typography.Title>
        <pre style={{
          background: 'var(--color-fill-2)', padding: 12, borderRadius: 6,
          fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-all', margin: 0,
        }}>
          {curl}
        </pre>
        <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
          支持参数：page、size、order_by（暴露列）、order=asc|desc，以及每个可过滤列的等值参数。
        </Typography.Text>
      </section>
    </div>
  );
}
