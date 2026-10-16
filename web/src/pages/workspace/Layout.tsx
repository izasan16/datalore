import {
  useEffect, useState } from 'react';
import { useLocation, useNavigate, Outlet } from 'react-router-dom';
import { Layout, Menu, Typography, Button, Avatar } from '@arco-design/web-react';
import {
  IconStorage,
  IconBook,
  IconCode,
  IconCloud,
  IconCodeBlock,
  IconHome,
  IconSync,
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
  { key: 'sqllab', title: 'SQL 分析', icon: <IconCode /> },
  { key: 'pyscript', title: 'Python 分析', icon: <IconBook /> },
  { key: 'apipub', title: 'API 发布', icon: <IconCodeBlock /> },
  { key: 'etl', title: '数据集成', icon: <IconSync /> },
];

export default function WorkspaceLayout() {
  const nav = useNavigate();
  const location = useLocation();
  const [user, setUser] = useState<MeInfo | null>(null);
  // 菜单受控高亮：取路径第一段（/datamanage/new -> datamanage）
  const currentModuleKey = location.pathname.split('/')[1] || 'workspace';
  const selectedKeys = MENU.some((m) => m.key === currentModuleKey) ? [currentModuleKey] : [];
  const currentModuleTitle = MENU.find((m) => m.key === currentModuleKey)?.title ?? '工作台';

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
    <Layout style={{ height: '100vh' }}>
      <Sider width={216} style={{ background: '#fff', borderRight: '1px solid var(--color-border-2)', overflowY: 'auto' }}>
        <div className="ws-sider-logo">
          <LogoMark size={28} />
          数据开发平台</div>
        <Menu
          selectedKeys={selectedKeys}
          style={{ width: '100%' }}
          onClickMenuItem={(key) => nav(`/${key}`)}
        >
          {MENU.map((m) => (
            <Menu.Item key={m.key}>
              {m.icon}
              {m.title}
            </Menu.Item>
          ))}
        </Menu>
      </Sider>
      <Layout>
        <Header
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 12,
            background: '#fff',
            borderBottom: '1px solid var(--color-border-2)',
            height: 56,
            minHeight: 56,
            padding: '0 20px',
            boxSizing: 'border-box',
            flexWrap: 'wrap',
          }}
        >
          <Typography.Text
            style={{
              minWidth: 0,
              flex: '1 1 160px',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              fontSize: 16,
              fontWeight: 600,
            }}
          >
            {currentModuleTitle}
          </Typography.Text>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'flex-end',
              gap: 12,
              minWidth: 0,
              flex: '0 1 auto',
              flexWrap: 'wrap',
            }}
          >
            <Avatar size={28} style={{ backgroundColor: 'var(--daaspro-primary)' }}>
              {user.username.slice(0, 1).toUpperCase()}
            </Avatar>
            <Typography.Text style={{ whiteSpace: 'nowrap' }}>
              {user.username}
              <Typography.Text type="secondary" style={{ marginLeft: 6, fontSize: 12 }}>
                {user.role === 'admin' ? '管理员' : '成员'}
              </Typography.Text>
            </Typography.Text>
            <Button size="mini" onClick={onLogout}>
              退出
            </Button>
          </div>
        </Header>
        <Content style={{ padding: 20, background: 'var(--color-fill-1)', overflow: 'auto' }}>
          <Outlet context={user} />
        </Content>
      </Layout>
    </Layout>
  );
}
