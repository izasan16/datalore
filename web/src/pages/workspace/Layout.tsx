import { useEffect, useState } from 'react';
import { useLocation, useNavigate, Outlet } from 'react-router-dom';
import { Layout, Menu, Typography, Button, Avatar } from '@arco-design/web-react';
import {
  IconStorage,
  IconCode,
  IconBranch,
  IconCloud,
  IconHome,
  IconSettings,
} from '@arco-design/web-react/icon';
import { me, logout } from '../../api/auth';
import { LogoMark } from '../../components/Brand';

const { Sider, Header, Content } = Layout;

interface MeInfo {
  userId: number;
  username: string;
  role: string;
  tenantId: number;
}

const MENU = [
  { key: 'workspace', title: '工作台', icon: <IconHome /> },
  { key: 'datasource', title: '数据源', icon: <IconStorage /> },
  { key: 'datamanage', title: '数据管理', icon: <IconCloud /> },
  { key: 'sqllab', title: 'SQL 分析', icon: <IconCode />, disabled: true },
  { key: 'apipub', title: 'API 发布', icon: <IconCloud />, disabled: true },
  { key: 'workflow', title: '工作流', icon: <IconBranch />, disabled: true },
  { key: 'settings', title: '设置', icon: <IconSettings />, disabled: true },
];

export default function WorkspaceLayout() {
  const nav = useNavigate();
  const location = useLocation();
  const [user, setUser] = useState<MeInfo | null>(null);
  // 菜单受控高亮：取路径第一段（/datamanage/new -> datamanage）
  const activeKey = '/' + location.pathname.split('/')[1];
  const selectedKeys = MENU.some((m) => m.key === activeKey.slice(1)) ? [activeKey.slice(1)] : [];

  useEffect(() => {
    me()
      .then(setUser)
      .catch(() => setUser(null));
  }, []);

  function onLogout() {
    logout().finally(() => {
      localStorage.removeItem('daaspro.token');
      localStorage.removeItem('daaspro.user');
      nav('/login');
    });
  }

  if (user === null) {
    // me() 失败或未加载完成时交给拦截器/守卫处理
    return null;
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={216} style={{ background: '#fff', borderRight: '1px solid var(--color-border-2)' }}>
        <div className="ws-sider-logo">
          <LogoMark size={28} />
          daaspro
        </div>
        <Menu
          selectedKeys={selectedKeys}
          style={{ width: '100%' }}
          onClickMenuItem={(key) => nav(`/${key}`)}
        >
          {MENU.map((m) => (
            <Menu.Item key={m.key} disabled={m.disabled}>
              {m.icon}
              {m.title}
            </Menu.Item>
          ))}
        </Menu>
        <div style={{ padding: '12px 20px' }}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            灰色菜单为后续迭代模块
          </Typography.Text>
        </div>
      </Sider>
      <Layout>
        <Header
          style={{
            display: 'flex',
            justifyContent: 'flex-end',
            alignItems: 'center',
            gap: 12,
            background: '#fff',
            borderBottom: '1px solid var(--color-border-2)',
            height: 56,
          }}
        >
          <Avatar size={28} style={{ backgroundColor: 'var(--daaspro-primary)' }}>
            {user.username.slice(0, 1).toUpperCase()}
          </Avatar>
          <Typography.Text>
            {user.username}
            <Typography.Text type="secondary" style={{ marginLeft: 6, fontSize: 12 }}>
              {user.role === 'admin' ? '管理员' : '成员'}
            </Typography.Text>
          </Typography.Text>
          <Button size="mini" onClick={onLogout} style={{ marginLeft: 8 }}>
            退出
          </Button>
        </Header>
        <Content style={{ padding: 20, background: 'var(--color-fill-1)' }}>
          <Outlet context={user} />
        </Content>
      </Layout>
    </Layout>
  );
}
