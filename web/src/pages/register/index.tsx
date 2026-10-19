import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { Form, Input, Button, Message } from '@arco-design/web-react';
import { registerTenant, login } from '../../api/auth';
import AuthLayout from '../auth/AuthLayout';

export default function RegisterPage() {
  const nav = useNavigate();
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  async function onSubmit(v: { name: string; adminUsername: string; adminPassword: string }) {
    setLoading(true);
    try {
      const slug = await registerTenant(v.name, v.adminUsername, v.adminPassword);
      // 注册即登录：后端返回 slug 并直接登录
      const r = await login(v.adminUsername, v.adminPassword);
      localStorage.setItem('Datalore.token', r.token);
      localStorage.setItem('Datalore.user', JSON.stringify(r.user));
      localStorage.setItem('Datalore.slug', slug);
      Message.success('工作区已创建');
      nav('/workspace');
    } catch (e: any) {
      Message.error(e.response?.data?.message || '注册失败');
    } finally {
      setLoading(false);
    }
  }

  return (
    <AuthLayout>
      <h2>创建你的数据工作区</h2>
      <p className="auth-panel-sub">只需团队名称和管理员账号，注册后立即进入工作台</p>
      <Form form={form} onSubmit={onSubmit} layout="vertical" className="auth-form">
        <Form.Item label="企业 / 团队名称" field="name" rules={[{ required: true, message: '请输入名称' }]}>
          <Input placeholder="如：某某数据团队" />
        </Form.Item>
        <Form.Item label="管理员用户名" field="adminUsername" rules={[{ required: true, message: '请输入管理员用户名' }]}>
          <Input placeholder="admin" />
        </Form.Item>
        <Form.Item
          label="管理员密码"
          field="adminPassword"
          rules={[{ required: true, message: '请输入密码' }, { minLength: 8, message: '至少 8 位' }]}
        >
          <Input.Password placeholder="至少 8 位" />
        </Form.Item>
        <Button type="primary" htmlType="submit" long loading={loading} className="auth-submit">
          创建工作区
        </Button>
      </Form>
      <div className="auth-alt">
        已有工作区？<Link to="/login">直接登录</Link>
      </div>
    </AuthLayout>
  );
}
