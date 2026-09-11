import { Typography, Card } from '@arco-design/web-react';

export default function WorkspacePage() {
  return (
    <Card style={{ maxWidth: 560 }}>
      <Typography.Title heading={4}>工作台（MVP 占位）</Typography.Title>
      <Typography.Text type="secondary">
        数据源管理、SQL 分析、API 发布等模块将在此后的迭代加入侧边栏。
      </Typography.Text>
    </Card>
  );
}
