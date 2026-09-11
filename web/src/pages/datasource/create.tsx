import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Card, Form, Input, InputNumber, Message, Select, Space } from '@arco-design/web-react';
import { createDatasource } from '../../api/datasource';
import { http } from '../../api/http';

const TYPE_LABEL: Record<string, string> = {
  postgresql: 'PostgreSQL',
  mysql: 'MySQL',
  sqlserver: 'SQL Server',
  oracle: 'Oracle',
};

const TYPE_OPTIONS = Object.entries(TYPE_LABEL).map(([value, label]) => ({ value, label }));

export default function DatasourceCreatePage() {
  const nav = useNavigate();
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [form] = Form.useForm();

  async function onTest() {
    const v = await form.validate();
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
      nav('/datasource');
    } catch (e: any) {
      Message.error(e.response?.data?.message || '创建失败');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Card
      title="新建数据源"
      style={{ maxWidth: 640 }}
      extra={
        <Button onClick={() => nav('/datasource')} type="text">
          返回列表
        </Button>
      }
    >
      <Form form={form} onSubmit={onSubmit} layout="vertical" className="auth-form">
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
        <Space style={{ marginTop: 8 }}>
          <Button onClick={onTest} loading={testing}>
            测试连接
          </Button>
          <Button type="primary" htmlType="submit" loading={saving}>
            保存
          </Button>
        </Space>
      </Form>
    </Card>
  );
}
