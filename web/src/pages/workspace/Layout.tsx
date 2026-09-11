import { useState } from 'react';
import { useNavigate, Outlet, Navigate } from 'react-router-dom';
import { Layout, Menu, Typography, Button } from '@arco-design/web-react';
import { me, logout } from '../../api/auth';

const { Sider, Header, Content } = Layout;

export default function WorkspaceLayout() {
  const nav = useNavigate();
  const [user, setUser] = useState<{ username: string; role: string; tenantId: number } | null>(null);

  function onLogout() {
    logout().finally(() => {
      localStorage.removeItem('daaspro.token');
      localStorage.removeItem('daaspro.user');
      nav('/login');
    });
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider collapsedWidth={60} width={200} style={{ background: 'var(--color-bg-2)' }}>
        <div style={{ padding: '16px 16px 8px', fontWeight: 600, fontSize: 18 }}>daaspro</div>
        <Menu
          defaultSelectedKeys={['workspace']}
          style={{ width: '100%' }}
          onClickMenuItem={(key) => nav(`/${key}`)}
        >
          <Menu.Item key="workspace">工作台</Menu.Item>
        </Menu>
      </Sider>
      <Layout>
        <Header
          style={{
            display: 'flex',
            justifyContent: 'flex-end',
            alignItems: 'center',
            gap: 12,
            background: 'var(--color-bg-2)',
          }}
        >
          <Typography.Text>{user ? `${user.username}（${user.role}）` : ''}</Typography.Text>
          <Button size="small" onClick={onLogout}>
            退出登录
          </Button>
        </Header>
        <Content style={{ padding: 20 }}>
          <UserGate onReady={setUser}>
            <Outlet />
          </UserGate>
        </Content>
      </Layout>
    </Layout>
  );
}

function UserGate({ children, onReady }: { children: React.ReactNode; onReady: (u: any) => void }) {
  const [state, setState] = useState<'loading' | 'ok' | 'deny'>('loading');
  (function load() {
    me()
      .then((u) => {
        onReady(u);
        setState('ok');
      })
      .catch(() => setState('deny'));
  })();
  if (state === 'loading') return null;
  if (state === 'deny') return <Navigate to="/login" replace />;
  return <>{children}</>;
}
