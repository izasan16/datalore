import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { Card, Form, Input, Button, Message } from '@arco-design/web-react';
import { login } from '../../api/auth';

export default function LoginPage() {
  const nav = useNavigate();
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  async function onSubmit(v: { tenantSlug: string; username: string; password: string }) {
    setLoading(true);
    try {
      const r = await login(v.tenantSlug, v.username, v.password);
      localStorage.setItem('daaspro.token', r.token);
      localStorage.setItem('daaspro.user', JSON.stringify(r.user));
      Message.success('登录成功');
      nav('/workspace');
    } catch (e: any) {
      Message.error(e.response?.data?.message || '登录失败');
    } finally {
      setLoading(false);
    }
  }

  return (
    <Card style={{ width: 400, margin: '120px auto' }} title="登录 daaspro">
      <Form form={form} onSubmit={onSubmit} layout="vertical">
        <Form.Item label="租户标识" field="tenantSlug" rules={[{ required: true, message: '请输入租户标识' }]}>
          <Input placeholder="注册时填写的租户标识" />
        </Form.Item>
        <Form.Item label="用户名" field="username" rules={[{ required: true, message: '请输入用户名' }]}>
          <Input placeholder="用户名" />
        </Form.Item>
        <Form.Item label="密码" field="password" rules={[{ required: true, message: '请输入密码' }]}>
          <Input.Password placeholder="密码" />
        </Form.Item>
        <Button type="primary" htmlType="submit" long loading={loading}>
          登录
        </Button>
        <div style={{ marginTop: 12, textAlign: 'center' }}>
          <Link to="/register">注册新租户</Link>
        </div>
      </Form>
    </Card>
  );
}
