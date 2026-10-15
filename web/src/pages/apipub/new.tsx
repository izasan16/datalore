import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Card, Message } from '@arco-design/web-react';
import { createApi } from '../../api/apipub';
import ApiPubForm from './form';

export default function ApiPubNewPage() {
  const nav = useNavigate();
  const [saving, setSaving] = useState(false);

  return (
    <Card
      title="新建 API 发布"
      style={{ maxWidth: 760, margin: '0 auto' }}
      extra={<Button type="text" onClick={() => nav('/apipub')}>返回列表</Button>}
    >
      <ApiPubForm
        submitText="发布"
        saving={saving}
        onSubmit={async (payload) => {
          setSaving(true);
          try {
            const api = await createApi(payload);
            Message.success('发布成功');
            nav(`/apipub/${api.id}`);
          } catch (e: any) {
            Message.error(e.response?.data?.message || '发布失败');
          } finally {
            setSaving(false);
          }
        }}
      />
    </Card>
  );
}
