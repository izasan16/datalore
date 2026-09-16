import { Card, Typography } from '@arco-design/web-react';
import { useNavigate, useOutletContext } from 'react-router-dom';
import { IconStorage, IconCloud, IconCode, IconRight } from '@arco-design/web-react/icon';

const ENTRIES = [
  { title: '数据源', desc: '接入外部数据库，浏览库表结构', path: '/datasource', icon: <IconStorage /> },
  { title: '数据管理', desc: '上传 CSV / XLSX，管理表与数据集', path: '/datamanage', icon: <IconCloud /> },
  { title: 'SQL 分析', desc: '编写并运行 SQL，保存常用脚本', path: '/sqllab', icon: <IconCode /> },
];

export default function WorkspacePage() {
  const user = useOutletContext<{ userId: number; username: string; role: string; tenantId: number }>();
  const nav = useNavigate();

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Card style={{ flex: 'none' }}>
        <Typography.Title heading={4} style={{ marginTop: 0 }}>
          你好，{user.username}
        </Typography.Title>
        <Typography.Text type="secondary">
          你的数据工作区已就绪（租户 {user.tenantId}）。从下面任一模块开始：
        </Typography.Text>
      </Card>

      <div style={{ display: 'flex', gap: 16, flex: 1, minHeight: 0 }}>
        {ENTRIES.map((e) => (
          <Card
            key={e.path}
            hoverable
            onClick={() => nav(e.path)}
            style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}
          >
            <div>
              <div style={{ fontSize: 28, color: 'var(--daaspro-primary)', marginBottom: 12 }}>{e.icon}</div>
              <Typography.Title heading={6} style={{ marginTop: 0 }}>
                {e.title}
              </Typography.Title>
              <Typography.Text type="secondary" style={{ fontSize: 13 }}>
                {e.desc}
              </Typography.Text>
            </div>
            <Typography.Text style={{ fontSize: 13, color: 'var(--daaspro-primary)' }}>
              进入 <IconRight />
            </Typography.Text>
          </Card>
        ))}
      </div>
    </div>
  );
}
