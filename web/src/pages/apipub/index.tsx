import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Button, Table, Tag, Typography } from '@arco-design/web-react';
import { IconPlus, IconRefresh } from '@arco-design/web-react/icon';
import { ApiPubInfo, listApis } from '../../api/apipub';

export default function ApiPubPage() {
  const nav = useNavigate();
  const [list, setList] = useState<ApiPubInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(false);
    try {
      setList(await listApis());
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  return (
    <div className="page-shell">
      <div className="page-header">
        <div className="page-header-main">
          <Typography.Title heading={4} style={{ margin: 0 }}>
            API 发布
          </Typography.Title>
          <Typography.Text type="secondary">
            把数据源中的表发布为只读 REST API，外部应用通过 AppKey/Secret 调用 GET /api/open/{'{path}'}
          </Typography.Text>
        </div>
        <div className="page-header-actions">
          <Button size="mini" icon={<IconRefresh />} onClick={reload}>刷新</Button>
          <Button size="mini" onClick={() => nav('/apipub/apps')}>应用管理</Button>
          <Button size="mini" type="primary" icon={<IconPlus />} onClick={() => nav('/apipub/new')}>
            新建发布
          </Button>
        </div>
      </div>

      <section className="page-section natural-height" aria-labelledby="api-pub-list-title">
        <Typography.Title id="api-pub-list-title" heading={6} style={{ margin: '0 0 14px' }}>
          已发布 API
        </Typography.Title>
        {error ? (
          <div className="page-error" role="alert">
            <Typography.Text>API 列表加载失败，请重试。</Typography.Text>
            <div style={{ marginTop: 10 }}>
              <Button size="mini" onClick={reload}>重试</Button>
            </div>
          </div>
        ) : list.length === 0 && !loading ? (
          <div className="page-empty">
            <Typography.Text>还没有发布 API。</Typography.Text>
            <div style={{ marginTop: 10 }}>
              <Link to="/apipub/new">新建 API 发布</Link>
            </div>
          </div>
        ) : (
          <Table
            size="small"
            rowKey="id"
            loading={loading}
            data={list}
            pagination={false}
            columns={[
              {
                title: '名称',
                dataIndex: 'name',
                render: (_, r) => <Link to={`/apipub/${r.id}`}>{r.name}</Link>,
              },
              { title: '路径', dataIndex: 'path', render: (p: string) => <code>/api/open/{p}</code> },
              { title: '数据表', render: (_: unknown, row: ApiPubInfo) => `${row.datasourceName} · ${row.schemaName ? `${row.schemaName}.` : ''}${row.tableName}` },
              { title: '暴露列数', dataIndex: 'columns', render: (c: string[]) => c.length },
              { title: '授权应用', dataIndex: 'grantCount' },
              { title: '行数上限', dataIndex: 'maxRows' },
              {
                title: '状态',
                dataIndex: 'status',
                render: (s: number) => (s === 1 ? <Tag color="green">启用</Tag> : <Tag color="gray">停用</Tag>),
              },
              {
                title: '更新时间',
                dataIndex: 'updatedAt',
                render: (t: string) => (t ? t.replace('T', ' ').slice(0, 16) : ''),
              },
              {
                title: '操作',
                width: 90,
                render: (_, r) => (
                  <Button type="text" size="mini" onClick={() => nav(`/apipub/${r.id}`)}>
                    管理
                  </Button>
                ),
              },
            ]}
          />
        )}
      </section>
    </div>
  );
}
