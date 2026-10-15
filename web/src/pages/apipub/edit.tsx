import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Button, Card, Message, Spin } from '@arco-design/web-react';
import { ApiPubInfo, getApi, updateApi } from '../../api/apipub';
import ApiPubForm from './form';

export default function ApiPubEditPage() {
  const nav = useNavigate();
  const { id } = useParams();
  const apiId = Number(id);
  const [api, setApi] = useState<ApiPubInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [saving, setSaving] = useState(false);

  const reload = useCallback(async () => {
    setLoading(true);
    setLoadError(false);
    try {
      setApi(await getApi(apiId));
    } catch {
      setLoadError(true);
    } finally {
      setLoading(false);
    }
  }, [apiId]);

  useEffect(() => {
    reload();
  }, [reload]);

  return (
    <Card
      title="编辑 API 发布"
      style={{ maxWidth: 760, margin: '0 auto' }}
      extra={<Button type="text" onClick={() => nav(`/apipub/${apiId}`)}>返回详情</Button>}
    >
      {loading ? (
        <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
      ) : loadError || !api ? (
        <div className="page-error" role="alert">
          <Button size="mini" onClick={reload}>重试</Button>
        </div>
      ) : (
        <ApiPubForm
          initial={api}
          submitText="保存"
          saving={saving}
          onSubmit={async (payload) => {
            setSaving(true);
            try {
              await updateApi(apiId, payload);
              Message.success('已保存');
              nav(`/apipub/${apiId}`);
            } catch (e: any) {
              Message.error(e.response?.data?.message || '保存失败');
            } finally {
              setSaving(false);
            }
          }}
        />
      )}
    </Card>
  );
}
