import { Routes, Route, Navigate, useNavigate } from 'react-router-dom';
import { Button, Result } from '@arco-design/web-react';
import LoginPage from './pages/login';
import RegisterPage from './pages/register';
import WorkspaceLayout from './pages/workspace/Layout';
import WorkspacePage from './pages/workspace';
import DatasourcePage from './pages/datasource';
import DatasourceCreatePage from './pages/datasource/create';
import DatasetPage from './pages/dataset';
import DatasetNewPage from './pages/dataset/new';
import SqlLabPage from './pages/sqllab';

function NotFound() {
  const nav = useNavigate();
  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <Result
        status="404"
        title="页面不存在"
        extra={
          <Button type="primary" onClick={() => nav('/workspace')}>
            返回工作台
          </Button>
        }
      />
    </div>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route element={<WorkspaceLayout />}>
        <Route path="/workspace" element={<WorkspacePage />} />
        <Route path="/datasource" element={<DatasourcePage />} />
        <Route path="/datasource/new" element={<DatasourceCreatePage />} />
        <Route path="/datamanage" element={<DatasetPage />} />
        <Route path="/datamanage/new" element={<DatasetNewPage />} />
        <Route path="/sqllab" element={<SqlLabPage />} />
      </Route>
      {/* 旧路径兼容 */}
      <Route path="/dataset" element={<Navigate to="/datamanage" replace />} />
      <Route path="/dataset/new" element={<Navigate to="/datamanage/new" replace />} />
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
