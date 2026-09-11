import { Typography, Card } from '@arco-design/web-react';
import { useOutletContext } from 'react-router-dom';

export default function WorkspacePage() {
  const user = useOutletContext<{ userId: number; username: string; role: string; tenantId: number }>();

  return (
    <Card style={{ maxWidth: 640 }}>
      <Typography.Title heading={4}>你好，{user.username}</Typography.Title>
      <Typography.Text type="secondary">
        你的数据工作区已就绪（租户 {user.tenantId}）。数据源管理、SQL 分析等模块将在此后的迭代加入侧边栏。
      </Typography.Text>
    </Card>
  );
}
