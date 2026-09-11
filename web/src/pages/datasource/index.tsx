import { useCallback, useEffect, useState } from 'react';
import {
  Button, Card, Drawer, Form, Input, InputNumber, Message, Modal, Select, Space,
  Table, Tag, Typography,
} from '@arco-design/web-react';
import { IconPlus, IconRefresh } from '@arco-design/web-react/icon';
import {
  ColumnInfo, DataSourceInfo, createDatasource, deleteDatasource, listColumns,
  listDatasources, listTables, testDatasource,
} from '../../api/datasource';

const TYPE_LABEL: Record<string, string> = {
  postgresql: 'PostgreSQL',
  mysql: 'MySQL',
  sqlserver: 'SQL Server',
  oracle: 'Oracle',
};

const TYPE_OPTIONS = Object.entries(TYPE_LABEL).map(([value, label]) => ({ value, label }));

export default function DatasourcePage() {
  const [list, setList] = useState<DataSourceInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [createVisible, setCreateVisible] = useState(false);
  const [creating, setCreating] = useState(false);
  const [testingNew, setTestingNew] = useState(false);
  const [browseDs, setBrowseDs] = useState<DataSourceInfo | null>(null);
  const [tables, setTables] = useState<{ name: string }[]>([]);
  const [columns, setColumns] = useState<ColumnInfo[]>([]);
  const [currentTable, setCurrentTable] = useState<string | null>(null);
  const [form] = Form.useForm();

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

  async function onCreate(v: any) {
    setCreating(true);
    try {
      await createDatasource({
        name: v.name,
        type: v.type,
        host: v.host,
        port: v.port || undefined,
        databaseName: v.databaseName,
        schemaName: v.schemaName || undefined,
        username: v.username,
        password: v.password,
      });
      Message.success('数据源已创建');
      setCreateVisible(false);
      form.resetFields();
      reload();
    } catch (e: any) {
      Message.error(e.response?.data?.message || '创建失败');
    } finally {
      setCreating(false);
    }
  }

  async function onTestNew() {
    const v = await form.validate(['type', 'host', 'port', 'databaseName', 'schemaName', 'username', 'password']);
    setTestingNew(true);
    try {
      await fetch('/api/datasources/test', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem('daaspro.token') || ''}`,
        },
        body: JSON.stringify({
          type: v.type, host: v.host, port: v.port || undefined,
          databaseName: v.databaseName, schemaName: v.schemaName || undefined,
          username: v.username, password: v.password,
        }),
      }).then(async (r) => {
        if (!r.ok) throw Object.assign(new Error(), { response: { data: await r.json().catch(() => ({})) } });
      });
      Message.success('连接成功');
    } catch (e: any) {
      Message.error(e.response?.data?.message || '连接失败');
    } finally {
      setTestingNew(false);
    }
  }

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
    <Card
      title="数据源"
      extra={
        <Space>
          <Button icon={<IconRefresh />} onClick={reload} />
          <Button type="primary" icon={<IconPlus />} onClick={() => setCreateVisible(true)}>
            新建数据源
          </Button>
        </Space>
      }
    >
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

      <Modal
        title="新建数据源"
        visible={createVisible}
        onCancel={() => setCreateVisible(false)}
        footer={null}
        style={{ width: 480 }}
      >
        <Form form={form} onSubmit={onCreate} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item label="名称" field="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如：业务库" />
          </Form.Item>
          <Form.Item label="类型" field="type" initialValue="postgresql" rules={[{ required: true, message: '请选择类型' }]}>
            <Select options={TYPE_OPTIONS} />
          </Form.Item>
          <Form.Item label="主机" field="host" rules={[{ required: true, message: '请输入主机' }]}>
            <Input placeholder="如：192.168.1.10" />
          </Form.Item>
          <Form.Item label="端口（留空用默认）" field="port">
            <InputNumber placeholder="默认端口自动填" style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="数据库" field="databaseName" rules={[{ required: true, message: '请输入数据库名' }]}>
            <Input placeholder="database" />
          </Form.Item>
          <Form.Item label="Schema（PG 可选）" field="schemaName">
            <Input placeholder="public" />
          </Form.Item>
          <Form.Item label="用户名" field="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input placeholder="用户名" />
          </Form.Item>
          <Form.Item label="密码" field="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password placeholder="密码" />
          </Form.Item>
          <Space style={{ width: '100%', justifyContent: 'space-between' }}>
            <Button onClick={onTestNew} loading={testingNew}>测试连接</Button>
            <Button type="primary" htmlType="submit" loading={creating}>保存</Button>
          </Space>
        </Form>
      </Modal>

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
    </Card>
  );
}
