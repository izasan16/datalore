import { Typography } from '@arco-design/web-react';
import { useNavigate, useOutletContext } from 'react-router-dom';
import { IconStorage, IconCloud, IconCode, IconRight } from '@arco-design/web-react/icon';

const ENTRIES = [
  { title: '数据源', desc: '接入外部数据库，浏览库表结构', path: '/datasource', icon: <IconStorage /> },
  { title: '数据管理', desc: '上传 CSV / XLSX，管理表与数据集', path: '/datamanage', icon: <IconCloud /> },
  { title: 'SQL 分析', desc: '编写并运行 SQL，保存常用脚本', path: '/sqllab', icon: <IconCode /> },
];

export default function WorkspacePage() {
  const user = useOutletContext<{ username: string; role: string }>();
  const nav = useNavigate();

  return (
    <div className="page-shell">
      <div className="page-header">
        <div className="page-header-main">
          <Typography.Title heading={4} style={{ margin: 0 }}>
            你好，{user.username}
          </Typography.Title>
          <Typography.Text type="secondary">你的数据工作区已就绪，从下面任一模块开始。</Typography.Text>
        </div>
      </div>

      <div className="compact-card-grid">
        {ENTRIES.map((e) => (
          <div
            key={e.path}
            className="compact-entry-card"
            role="button"
            tabIndex={0}
            onClick={() => nav(e.path)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                nav(e.path);
              }
            }}
          >
            <div style={{ fontSize: 24, color: 'var(--daaspro-primary)', marginBottom: 12 }}>{e.icon}</div>
            <Typography.Title heading={6} style={{ margin: '0 0 6px' }}>
              {e.title}
            </Typography.Title>
            <Typography.Text type="secondary" style={{ display: 'block', fontSize: 13, lineHeight: 1.6 }}>
              {e.desc}
            </Typography.Text>
            <Typography.Text style={{ fontSize: 13, color: 'var(--daaspro-primary)' }}>
              进入 <IconRight />
            </Typography.Text>
          </div>
        ))}
      </div>

      <section className="page-section" aria-labelledby="workspace-info-title">
        <Typography.Title id="workspace-info-title" heading={6} style={{ margin: '0 0 14px' }}>
          工作区信息
        </Typography.Title>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12 }}>
          <div>
            <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12 }}>
              当前用户
            </Typography.Text>
            <Typography.Text>{user.username}</Typography.Text>
          </div>
          <div>
            <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12 }}>
              用户角色
            </Typography.Text>
            <Typography.Text>{user.role === 'admin' ? '管理员' : '成员'}</Typography.Text>
          </div>
        </div>
      </section>
    </div>
  );
}
