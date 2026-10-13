import type { ReactNode } from 'react';
import { LogoMark, CheckIcon } from '../../components/Brand';

const FEATURES = [
  '多租户数据平台，数据落在您自己的数据库',
  'SQL 分析与脚本参数化复用',
  '数据一键发布为 REST API',
  '拖拽式可视化分析与仪表盘',
];

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="auth-page">
      <div className="auth-brand">
        <div className="auth-brand-logo">
          <span className="auth-brand-logo-mark">
            <LogoMark size={36} />
          </span>
          <span style={{ fontSize: 20, fontWeight: 600, letterSpacing: 0.5 }}>daaspro</span>
        </div>

        <div className="auth-brand-body">
          <h1>
            让数据从接入到洞察
            <br />
            只需一条流畅的链路
          </h1>
          <p>
            daaspro 面向数据分析师与数据开发工程师：接入数据、建模分析、发布服务、可视呈现，在一个平台完成。
          </p>
          <div className="auth-brand-features">
            {FEATURES.map((f) => (
              <div className="auth-brand-feature" key={f}>
                <CheckIcon />
                <span>{f}</span>
              </div>
            ))}
          </div>
        </div>

        <div className="auth-brand-footer">© 2026 daaspro · 数据分析平台</div>
      </div>

      <div className="auth-panel">
        <div className="auth-panel-inner">{children}</div>
      </div>
    </div>
  );
}
