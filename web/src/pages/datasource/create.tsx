import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Button, Form, Grid, Input, InputNumber, Message, Select, Space, Typography } from '@arco-design/web-react';
import { createDatasource, listDatasources, updateDatasource } from '../../api/datasource';
import { http } from '../../api/http';

const { Row, Col } = Grid;

const TYPE_LABEL: Record<string, string> = {
  postgresql: 'PostgreSQL',
  mysql: 'MySQL',
  sqlserver: 'SQL Server',
  oracle: 'Oracle',
};

const TYPE_OPTIONS = Object.entries(TYPE_LABEL).map(([value, label]) => ({ value, label }));

export default function DatasourceCreatePage() {
  const nav = useNavigate();
  const { id } = useParams();
  const editingId = id ? Number(id) : undefined;
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [loaded, setLoaded] = useState(!editingId);
  const [form] = Form.useForm();

  if (editingId && !loaded) {
    listDatasources().then((list) => {
      const ds = list.find((d) => d.id === editingId);
      if (ds) {
        form.setFieldsValue({
          name: ds.name, type: ds.type, host: ds.host, port: ds.port,
          databaseName: ds.databaseName, schemaName: ds.schemaName || undefined,
          username: undefined, password: undefined,
        });
      }
      setLoaded(true);
    }).catch(() => setLoaded(true));
  }

  async function onTest() {
    // 只校验连接相关字段，避免被未填的「名称」阻塞；校验失败 Arco 自动标红
    let v: any;
    try {
      v = await form.validate(['type', 'host', 'port', 'databaseName', 'schemaName', 'username', 'password']);
    } catch {
      return;
    }
    setTesting(true);
    try {
      await http.post('/datasources/test', {
        type: v.type, host: v.host, port: v.port || undefined,
        databaseName: v.databaseName, schemaName: v.schemaName || undefined,
        username: v.username, password: v.password,
      });
      Message.success('连接成功');
    } catch (e: any) {
      Message.error(e.response?.data?.message || '连接失败');
    } finally {
      setTesting(false);
    }
  }

  async function onSubmit(v: any) {
    setSaving(true);
    try {
      if (editingId) {
        await updateDatasource(editingId, {
          name: v.name,
          host: v.host,
          port: v.port || undefined,
          databaseName: v.databaseName,
          schemaName: v.schemaName || undefined,
          username: v.username || undefined,
          password: v.password || undefined,
        });
        Message.success('数据源已更新');
      } else {
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
      }
      nav('/datasource');
    } catch (e: any) {
      Message.error(e.response?.data?.message || (editingId ? '更新失败' : '创建失败'));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="page-shell">
      <div className="page-header">
        <div className="page-header-main">
          <Typography.Title heading={4} style={{ margin: 0 }}>
            {editingId ? '编辑数据源' : '新建数据源'}
          </Typography.Title>
          <Typography.Text type="secondary">
            {editingId ? '修改连接信息，密码留空保持不变' : '填写数据库连接信息，保存前可先测试连通性'}
          </Typography.Text>
        </div>
        <div className="page-header-actions">
          <Button type="text" onClick={() => nav('/datasource')}>
            返回列表
          </Button>
        </div>
      </div>

      <section className="page-section" style={{ maxWidth: 800, width: '100%', margin: '0 auto' }}>
        <Form form={form} onSubmit={onSubmit} layout="vertical" initialValues={{ type: 'postgresql' }}>
          <Typography.Title heading={6} style={{ margin: '0 0 16px' }}>
            基本信息
          </Typography.Title>
          <Row gutter={[16, 0]}>
            <Col xs={24} sm={12}>
              <Form.Item label="名称" field="name" rules={[{ required: true, message: '请输入名称' }]}>
                <Input placeholder="如：业务库" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item label="类型" field="type" rules={[{ required: true, message: '请选择类型' }]}>
                <Select options={TYPE_OPTIONS} disabled={!!editingId} />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Title heading={6} style={{ margin: '8px 0 16px' }}>
            连接信息
          </Typography.Title>
          <Row gutter={[16, 0]}>
            <Col xs={24} sm={12}>
              <Form.Item label="主机" field="host" rules={[{ required: true, message: '请输入主机' }]}>
                <Input placeholder="如：192.168.1.10" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item label="端口（留空用默认）" field="port">
                <InputNumber placeholder="默认端口自动填" style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item label="数据库" field="databaseName" rules={[{ required: true, message: '请输入数据库名' }]}>
                <Input placeholder="database" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item label="Schema（PG 可选）" field="schemaName">
                <Input placeholder="public" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item label="用户名" field="username" rules={[{ required: true, message: '请输入用户名' }]}>
                <Input placeholder="用户名" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item label={editingId ? '密码（留空保持不变）' : '密码'} field="password"
                rules={editingId ? [] : [{ required: true, message: '请输入密码' }]}>
                <Input.Password placeholder={editingId ? '留空保持原密码' : '密码'} />
              </Form.Item>
            </Col>
          </Row>
          <Space style={{ marginTop: 8 }} wrap>
            <Button onClick={onTest} loading={testing}>
              测试连接
            </Button>
            <Button type="primary" htmlType="submit" loading={saving}>
              保存
            </Button>
          </Space>
        </Form>
      </section>
    </div>
  );
}
