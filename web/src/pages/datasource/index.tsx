import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button, Drawer, Message, Modal, Space,
  Table, Tag, Typography,
} from '@arco-design/web-react';
import { IconPlus, IconRefresh } from '@arco-design/web-react/icon';
import {
  ColumnInfo, DataSourceInfo, deleteDatasource, listColumns,
  listDatasources, listTables, testDatasource,
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
  const [browseDs, setBrowseDs] = useState<DataSourceInfo | null>(null);
  const [tables, setTables] = useState<{ name: string }[]>([]);
  const [columns, setColumns] = useState<ColumnInfo[]>([]);
  const [currentTable, setCurrentTable] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setList(await listDatasources());
    } catch (e: any) {
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

  async function openBrowse(ds: DataSourceInfo) {
    setBrowseDs(ds);
    setCurrentTable(null);
    setColumns([]);
    try {
      setTables(await listTables(ds.id));
    } catch (e: any) {
      Message.error(e.response?.data?.message || '读取表列表失败');
      setTables([]);
    }
  }

  async function onPickTable(t: string) {
    if (!browseDs) return;
    setCurrentTable(t);
    setColumns([]);
    try {
      setColumns(await listColumns(browseDs.id, t));
    } catch (e: any) {
      Message.error(e.response?.data?.message || '读取列信息失败');
    }
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

      <section className="page-section" aria-labelledby="datasource-list-title">
        <Typography.Title id="datasource-list-title" heading={6} style={{ margin: '0 0 14px' }}>
          已接入的数据源
        </Typography.Title>
        <Table
          loading={loading}
          data={list}
          rowKey="id"
          pagination={false}
          columns={[
            {
              title: '名称', dataIndex: 'name',
              render: (v: string, row: DataSourceInfo) => (
                <Space>
                  {v}
                  {row.builtin && <Tag color="arcoblue" size="small">内置</Tag>}
                </Space>
              ),
            },
            { title: '类型', dataIndex: 'type', width: 130, render: (t: string) => TYPE_LABEL[t] || t },
            {
              title: '连接', dataIndex: 'host', width: 240,
              render: (_: string, row: DataSourceInfo) =>
                row.builtin ? `平台内置 · ${row.schemaName}` : `${row.host}:${row.port}/${row.databaseName}`,
            },
            {
              title: '操作', width: 240,
              render: (_: unknown, row: DataSourceInfo) => (
                <Space>
                  <Button size="mini" onClick={() => onTestExisting(row)}>测连接</Button>
                  <Button size="mini" type="text" onClick={() => openBrowse(row)}>表浏览</Button>
                  {!row.builtin && (
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
      </section>

      <Drawer
        title={browseDs ? `表浏览 · ${browseDs.name}` : ''}
        visible={!!browseDs}
        onCancel={() => setBrowseDs(null)}
        width={560}
        footer={null}
      >
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          点击表名查看列结构
        </Typography.Text>
        <Table
          style={{ marginTop: 8 }}
          data={tables}
          rowKey="name"
          size="small"
          pagination={{ pageSize: 12 }}
          onRow={(record) => ({ onClick: () => onPickTable(record.name), style: { cursor: 'pointer' } })}
          columns={[
            { title: '表名', dataIndex: 'name' },
            {
              title: '', width: 60,
              render: (_: unknown, row: { name: string }) =>
                currentTable === row.name ? <Tag size="small" color="arcoblue">列</Tag> : null,
            },
          ]}
        />
        {currentTable && (
          <div style={{ marginTop: 16 }}>
            <Typography.Title heading={6}>列结构 · {currentTable}</Typography.Title>
            <Table
              data={columns}
              rowKey="name"
              size="small"
              pagination={false}
              columns={[
                { title: '列名', dataIndex: 'name' },
                { title: '类型', dataIndex: 'type', width: 160 },
              ]}
            />
          </div>
        )}
      </Drawer>
    </div>
  );
}
