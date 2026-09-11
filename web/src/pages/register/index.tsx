import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { Card, Form, Input, Button, Message } from '@arco-design/web-react';
import { registerTenant } from '../../api/auth';

export default function RegisterPage() {
  const nav = useNavigate();
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  async function onSubmit(v: { name: string; slug: string; adminUsername: string; adminPassword: string }) {
    setLoading(true);
    try {
      await registerTenant(v.name, v.slug, v.adminUsername, v.adminPassword);
      Message.success('注册成功，请登录');
      nav('/login');
    } catch (e: any) {
      Message.error(e.response?.data?.message || '注册失败');
    } finally {
      setLoading(false);
    }
  }

  return (
    <Card style={{ width: 440, margin: '100px auto' }} title="注册新租户">
      <Form form={form} onSubmit={onSubmit} layout="vertical">
        <Form.Item label="企业/团队名称" field="name" rules={[{ required: true, message: '请输入名称' }]}>
          <Input placeholder="如：某某数据团队" />
        </Form.Item>
        <Form.Item label="租户标识" field="slug" rules={[{ required: true, message: '小写字母开头，3-32 位' }]}>
          <Input placeholder="小写字母开头，含小写字母/数字/下划线" />
        </Form.Item>
        <Form.Item label="管理员用户名" field="adminUsername" rules={[{ required: true, message: '请输入管理员用户名' }]}>
          <Input placeholder="admin" />
        </Form.Item>
        <Form.Item label="管理员密码" field="adminPassword" rules={[{ required: true, message: '至少 8 位' }]}>
          <Input.Password placeholder="至少 8 位" />
        </Form.Item>
        <Button type="primary" htmlType="submit" long loading={loading}>
          注册
        </Button>
        <div style={{ marginTop: 12, textAlign: 'center' }}>
          <Link to="/login">已有账号，去登录</Link>
        </div>
      </Form>
    </Card>
  );
}
