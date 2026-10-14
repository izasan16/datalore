import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button, Message, Modal, Space,
  Table, Typography,
} from '@arco-design/web-react';
import { IconPlus, IconRefresh } from '@arco-design/web-react/icon';
import {
  DataSourceInfo, deleteDatasource,
  listDatasources, testDatasource,
} from '../../api/datasource';

const TYPE_LABEL: Record<string, string> = {
  postgresql: 'PostgreSQL',
  mysql: 'MySQL',
  sqlserver: 'SQL Server',
  oracle: 'Oracle',
};

export default function DatasourcePage() {
  const nav = useNavigate();
  const [list, setList] = useState<DataSourceInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [listError, setListError] = useState(false);

  const reload = useCallback(async () => {
    setLoading(true);
    setListError(false);
    try {
      setList(await listDatasources());
    } catch (e: any) {
      setListError(true);
      Message.error(e.response?.data?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  async function onTestExisting(ds: DataSourceInfo) {
    try {
      await testDatasource(ds.id);
      Message.success(`「${ds.name}」连接成功`);
    } catch (e: any) {
      Message.error(e.response?.data?.message || '连接失败');
    }
  }

  function onDelete(ds: DataSourceInfo) {
    Modal.confirm({
      title: '删除数据源',
      content: `确定删除「${ds.name}」吗？该操作不可恢复。`,
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        try {
          await deleteDatasource(ds.id);
          Message.success('已删除');
          reload();
        } catch (e: any) {
          Message.error(e.response?.data?.message || '删除失败');
        }
      },
    });
  }

  return (
    <div className="page-shell">
      <div className="page-header">
        <div className="page-header-main">
          <Typography.Title heading={4} style={{ margin: 0 }}>
            数据源
          </Typography.Title>
          <Typography.Text type="secondary">
            接入外部数据库并浏览库表结构
          </Typography.Text>
        </div>
        <div className="page-header-actions">
          <Button icon={<IconRefresh />} onClick={reload}>刷新</Button>
          <Button type="primary" icon={<IconPlus />} onClick={() => nav('/datasource/new')}>
            新建数据源
          </Button>
        </div>
      </div>

      <section className="page-section natural-height" aria-labelledby="datasource-list-title">
        <Typography.Title id="datasource-list-title" heading={6} style={{ margin: '0 0 14px' }}>
          已接入的数据源
        </Typography.Title>
        {listError && !loading ? (
          <div className="page-error" role="alert">
            <Typography.Text>数据源列表加载失败，请重试。</Typography.Text>
            <div style={{ marginTop: 10 }}>
              <Button size="small" onClick={reload}>重试</Button>
            </div>
          </div>
        ) : (
          <>
            <Table
              loading={loading}
              data={list}
              rowKey="id"
              pagination={false}
              columns={[
                {
                  title: '名称', dataIndex: 'name',
                  render: (v: string) => (
                    <Space>
                      {v}
                    </Space>
                  ),
                },
                { title: '类型', dataIndex: 'type', width: 130, render: (t: string) => TYPE_LABEL[t] || t },
                {
                  title: '连接', dataIndex: 'host', width: 240,
                  render: (_: string, row: DataSourceInfo) =>
                    `${row.host}:${row.port}/${row.databaseName}`,
                },
                {
                  title: '操作', width: 240,
                  render: (_: unknown, row: DataSourceInfo) => (
                    <Space>
                      <Button size="mini" onClick={() => onTestExisting(row)}>测连接</Button>
                      <Button size="mini" type="text" onClick={() => nav(`/datasource/${row.id}/edit`)}>编辑</Button>
                      {(
                        <Button size="mini" type="text" status="danger" onClick={() => onDelete(row)}>删除</Button>
                      )}
                    </Space>
                  ),
                },
              ]}
            />
            {list.length === 0 && !loading && (
              <div className="page-empty" style={{ marginTop: 12 }}>
                <Typography.Text type="secondary">
                  还没有数据源，先接入一个外部数据库吧
                </Typography.Text>
                <div style={{ marginTop: 12 }}>
                  <Button type="primary" size="small" icon={<IconPlus />} onClick={() => nav('/datasource/new')}>
                    新建数据源
                  </Button>
                </div>
              </div>
            )}
          </>
        )}
      </section>
    </div>
  );
}
