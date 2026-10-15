import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Button, Descriptions, Message, Modal, Space, Switch, Tag, Typography,
} from '@arco-design/web-react';
import { ApiCredential, ApiPubInfo, deleteApi, getApi, getCredential, resetCredential, updateApi } from '../../api/apipub';

export default function ApiPubDetailPage() {
  const nav = useNavigate();
  const { id } = useParams();
  const apiId = Number(id);
  const [api, setApi] = useState<ApiPubInfo | null>(null);
  const [apiLoading, setApiLoading] = useState(true);
  const [apiLoadError, setApiLoadError] = useState(false);
  const [cred, setCred] = useState<ApiCredential | null>(null);
  const [showSecret, setShowSecret] = useState(false);
  const [resetting, setResetting] = useState(false);

  const reload = useCallback(async () => {
    setApiLoading(true);
    setApiLoadError(false);
    try {
      const a = await getApi(apiId);
      setApi(a);
      setCred(await getCredential(apiId));
      setShowSecret(false);
      setApiLoading(false);
    } catch {
      setApiLoadError(true);
      setApiLoading(false);
    }
  }, [apiId]);

  useEffect(() => {
    reload();
  }, [reload]);

  async function onToggleStatus(checked: boolean) {
    try {
      setApi(await updateApi(apiId, { status: checked ? 1 : 0 }));
      Message.success(checked ? '已启用' : '已停用');
    } catch (e: any) {
      Message.error(e.response?.data?.message || '操作失败');
    }
  }

  function onReset() {
    Modal.confirm({
      title: '重置调用凭证',
      content: '将生成新的 Key 和 Secret，旧凭证立即失效。确定继续吗？',
      okText: '重置',
      onOk: async () => {
        setResetting(true);
        try {
          const c = await resetCredential(apiId);
          setCred(c);
          setShowSecret(true);
          Message.success('凭证已重置');
        } catch (e: any) {
          Message.error(e.response?.data?.message || '重置失败');
        } finally {
          setResetting(false);
        }
      },
    });
  }

  function onDelete() {
    Modal.confirm({
      title: '删除 API',
      content: `确定删除「${api?.name}」吗？调用方将无法再访问该接口。该操作不可恢复。`,
      okText: '删除',
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        try {
          await deleteApi(apiId);
          Message.success('已删除');
          nav('/apipub');
        } catch (e: any) {
          Message.error(e.response?.data?.message || '删除失败');
        }
      },
    });
  }

  if (apiLoading) {
    return <div className="page-shell"><Typography.Text type="secondary">加载中…</Typography.Text></div>;
  }
  if (apiLoadError || !api) {
    return (
      <div className="page-shell">
        <div className="page-error" role="alert">
          <Typography.Text>API 加载失败，请重试。</Typography.Text>
          <div style={{ marginTop: 10 }}>
            <Button size="mini" onClick={reload}>重试</Button>
          </div>
        </div>
      </div>
    );
  }

  const isSql = api.sourceType === 'SQL';
  // 示例值统一 URL 编码：Tomcat 会拒绝请求行里的非 ASCII 原始字节
  const paramQuery = (api.params ?? [])
    .filter((p) => p.required)
    .map((p) => `${p.name}=${p.type === 'number' ? 1 : encodeURIComponent('北京')}`)
    .join('&');
  const tableQuery = api.filterColumns.length ? `${api.filterColumns[0]}=${encodeURIComponent('北京')}&` : '';
  const curl = isSql
    ? `curl "http://localhost:8080/api/open/${api.path}${paramQuery ? `?${paramQuery}` : ''}" \\
  -H "X-Api-Key: ${cred?.apiKey ?? '<your-api-key>'}" \\
  -H "X-Api-Secret: ${showSecret && cred?.apiSecret ? cred.apiSecret : '<your-api-secret>'}"`
    : `curl "http://localhost:8080/api/open/${api.path}?${tableQuery}page=1&size=20" \\
  -H "X-Api-Key: ${cred?.apiKey ?? '<your-api-key>'}" \\
  -H "X-Api-Secret: ${showSecret && cred?.apiSecret ? cred.apiSecret : '<your-api-secret>'}"`;

  return (
    <div className="page-shell">
      <div className="page-header">
        <div className="page-header-main">
          <Typography.Title heading={4} style={{ margin: 0 }}>
            {api.name}
            <Tag color={api.status === 1 ? 'green' : 'gray'} style={{ marginLeft: 12 }}>
              {api.status === 1 ? '启用' : '停用'}
            </Tag>
          </Typography.Title>
          <Typography.Text type="secondary">
            {isSql ? 'SQL 查询 API' : '数据表 API'} · /api/open/{api.path} · 更新于 {api.updatedAt.replace('T', ' ').slice(0, 16)}
          </Typography.Text>
        </div>
        <div className="page-header-actions">
          <Space>
            <Switch checked={api.status === 1} onChange={onToggleStatus} checkedText="启用" uncheckedText="停用" />
            <Button size="mini" onClick={() => nav(`/apipub/${apiId}/edit`)}>编辑</Button>
            <Button size="mini" status="danger" onClick={onDelete}>删除</Button>
          </Space>
        </div>
      </div>

      <section className="page-section natural-height" aria-labelledby="api-cfg-title">
        <Typography.Title id="api-cfg-title" heading={6} style={{ margin: '0 0 14px' }}>发布配置</Typography.Title>
        {isSql ? (
          <Descriptions
            column={1}
            data={[
              { label: '数据源', value: api.datasourceName },
              { label: '调用参数', value: (api.params ?? []).map((p) => `${p.name}（${p.type === 'number' ? '数字' : '字符串'}${p.required ? '，必填' : '，可选'}）`).join('、') || '无' },
              { label: '行数上限', value: String(api.maxRows) },
            ]}
          />
        ) : (
          <Descriptions
            column={1}
            data={[
              { label: '数据源', value: api.datasourceName },
              { label: '数据表', value: `${api.schemaName ? `${api.schemaName}.` : ''}${api.tableName}` },
              { label: '暴露列', value: api.columns.join('、') },
              { label: '可过滤列', value: api.filterColumns.join('、') || '无（仅分页全量查询）' },
              { label: '行数上限', value: String(api.maxRows) },
            ]}
          />
        )}
        {isSql && (
          <pre style={{
            background: 'var(--color-fill-2)', padding: 12, borderRadius: 6,
            fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-all', margin: '12px 0 0',
          }}>
            {api.sqlText}
          </pre>
        )}
      </section>

      <section className="page-section natural-height" aria-labelledby="api-cred-title">
        <div className="page-header">
          <div className="page-header-main">
            <Typography.Title id="api-cred-title" heading={6} style={{ margin: 0 }}>调用凭证</Typography.Title>
            <Typography.Text type="secondary" style={{ display: 'block', marginTop: 6, fontSize: 12 }}>
              请求头携带 X-Api-Key 与 X-Api-Secret 调用；重置后旧凭证立即失效。
            </Typography.Text>
          </div>
          <div className="page-header-actions">
            <Button size="mini" loading={resetting} onClick={onReset}>重置凭证</Button>
          </div>
        </div>
        <Descriptions
          column={1}
          style={{ marginTop: 16 }}
          data={[
            { label: 'X-Api-Key', value: <code>{cred?.apiKey ?? '-'}</code> },
            {
              label: 'X-Api-Secret',
              value: cred?.secretSet ? (
                showSecret ? (
                  <Space>
                    <code>{cred.apiSecret}</code>
                    <Button type="text" size="mini" onClick={() => setShowSecret(false)}>隐藏</Button>
                  </Space>
                ) : (
                  <Button type="text" size="mini" onClick={() => setShowSecret(true)}>揭示</Button>
                )
              ) : (
                <Typography.Text type="secondary">尚未生成，请点击「重置凭证」</Typography.Text>
              ),
            },
          ]}
        />
      </section>

      <section className="page-section natural-height" aria-labelledby="api-example-title">
        <Typography.Title id="api-example-title" heading={6} style={{ margin: '0 0 14px' }}>调用示例</Typography.Title>
        <pre style={{
          background: 'var(--color-fill-2)', padding: 12, borderRadius: 6,
          fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-all', margin: 0,
        }}>
          {curl}
        </pre>
        <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
          {isSql
            ? 'SQL 模式按声明的参数传参（可选参数可省略），返回行数受行数上限截断，不支持分页。'
            : '表模式支持参数：page、size、order_by（暴露列）、order=asc|desc，以及每个可过滤列的等值参数。'}
          <Typography.Text type="secondary" style={{ display: 'block', marginTop: 4, fontSize: 12 }}>
            注意：中文参数值必须 URL 编码（如 北京 → %E5%8C%97%E4%BA%AC），否则服务器会直接拒绝请求。
          </Typography.Text>
        </Typography.Text>
      </section>
    </div>
  );
}
