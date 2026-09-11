import { useEffect, useState } from 'react';
import { Typography, Card, Tag, Spin } from '@arco-design/web-react';
import { getHealth } from './api/http';

export default function App() {
  const [version, setVersion] = useState<string | null>(null);
  useEffect(() => {
    getHealth()
      .then((d) => setVersion(d.version))
      .catch(() => setVersion('unreachable'));
  }, []);
  return (
    <Card style={{ maxWidth: 480, margin: '80px auto' }}>
      <Typography.Title heading={3}>daaspro</Typography.Title>
      <Typography.Text>后端版本：</Typography.Text>
      {version === null ? <Spin size={16} style={{ marginLeft: 8 }} /> : <Tag color="arcoblue">{version}</Tag>}
    </Card>
  );
}
