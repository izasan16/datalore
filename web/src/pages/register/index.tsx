import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { Form, Input, Button, Message } from '@arco-design/web-react';
import { registerTenant } from '../../api/auth';
import AuthLayout from '../auth/AuthLayout';

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
    <AuthLayout>
      <h2>创建你的数据工作区</h2>
      <p className="auth-panel-sub">注册后自动获得独立的数据空间与管理员账号</p>
      <Form form={form} onSubmit={onSubmit} layout="vertical" className="auth-form">
        <Form.Item label="企业 / 团队名称" field="name" rules={[{ required: true, message: '请输入名称' }]}>
          <Input placeholder="如：某某数据团队" />
        </Form.Item>
        <Form.Item
          label="租户标识"
          field="slug"
          rules={[
            { required: true, message: '请输入租户标识' },
            {
              validator: (value: string | undefined, callback: (error?: React.ReactNode) => void) => {
                const v = value || '';
                if (!/^[a-z][a-z0-9_]{2,31}$/.test(v)) {
                  callback('小写字母开头，3-32 位小写字母/数字/下划线');
                } else {
                  callback();
                }
              },
            },
          ]}
        >
          <Input placeholder="如：myteam（之后用它登录）" />
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
