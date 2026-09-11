import { Routes, Route, Navigate } from 'react-router-dom';
import LoginPage from './pages/login';
import RegisterPage from './pages/register';
import WorkspaceLayout from './pages/workspace/Layout';
import WorkspacePage from './pages/workspace';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route element={<WorkspaceLayout />}>
        <Route path="/workspace" element={<WorkspacePage />} />
      </Route>
      <Route path="*" element={<Navigate to="/workspace" replace />} />
    </Routes>
  );
}
