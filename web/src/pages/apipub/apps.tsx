import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button, Input, Message, Modal, Space, Table, Typography,
} from '@arco-design/web-react';
import { IconCopy, IconPlus, IconRefresh } from '@arco-design/web-react/icon';
import {
  ApiAppInfo, createApp, deleteApp, listApps, revealAppSecret, updateApp,
} from '../../api/apipub';

export default function ApiAppsPage() {
  const nav = useNavigate();
  const [apps, setApps] = useState<ApiAppInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [listError, setListError] = useState(false);
  const [newName, setNewName] = useState('');
  const [creating, setCreating] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editingName, setEditingName] = useState('');
  const [renaming, setRenaming] = useState(false);

  const reload = useCallback(async () => {
    setLoading(true);
    setListError(false);
    try {
      setApps(await listApps());
    } catch {
      setListError(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  async function onCreate() {
    if (!newName.trim()) return;
    setCreating(true);
    try {
      const r = await createApp(newName.trim());
      setNewName('');
      await reload();
      Modal.success({
        title: '应用已创建',
        content: (
          <div style={{ wordBreak: 'break-all' }}>
            <p style={{ marginBottom: 8 }}>请立刻保存 AppSecret，列表页只显示脱敏值：</p>
            <p style={{ fontFamily: 'monospace' }}>AppKey: {r.appKey}</p>
            <p style={{ fontFamily: 'monospace' }}>AppSecret: {r.appSecret}</p>
          </div>
        ),
      });
    } catch (e: any) {
      // 错误提示交给列表页统一处理
      if (e.response?.data?.message) Modal.error({ title: '创建失败', content: e.response.data.message });
    } finally {
      setCreating(false);
    }
  }

  async function copy(text: string, label: string) {
    try {
      await navigator.clipboard.writeText(text);
      Modal.success({ title: label + ' 已复制', content: text });
    } catch {
      Message.error(`${label} 复制失败，请手动复制`);
    }
  }

  function startRename(a: ApiAppInfo) {
    setEditingId(a.id);
    setEditingName(a.name);
  }

  function cancelRename() {
    setEditingId(null);
    setEditingName('');
  }

  async function onRename() {
    if (editingId === null || !editingName.trim()) return;
    setRenaming(true);
    try {
      await updateApp(editingId, editingName.trim());
      cancelRename();
      await reload();
      Message.success('应用名称已更新');
    } catch (e: any) {
      Message.error(e.response?.data?.message || '重命名失败');
    } finally {
      setRenaming(false);
    }
  }

  async function showSecret(a: ApiAppInfo) {
    try {
      const secret = await revealAppSecret(a.id);
      Modal.info({
        title: `「${a.name}」凭证`,
        content: (
          <div style={{ wordBreak: 'break-all' }}>
            <p style={{ fontFamily: 'monospace' }}>AppKey: {a.appKey}</p>
            <p style={{ fontFamily: 'monospace' }}>
              AppSecret: {secret}{' '}
              <Button size="mini" type="text" onClick={() => copy(secret, 'AppSecret')}>
                复制
              </Button>
            </p>
          </div>
        ),
      });
    } catch (e: any) {
      Message.error(e.response?.data?.message || '获取失败');
    }
  }

  function onDelete(a: ApiAppInfo) {
    Modal.confirm({
      title: '删除应用',
      content: `确定删除「${a.name}」吗？其全部授权同时移除。`,
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        await deleteApp(a.id);
        reload();
      },
    });
  }

  return (
    <div className="page-shell">
      <div className="page-header">
        <div className="page-header-main">
          <Typography.Title heading={4} style={{ margin: 0 }}>
            应用管理（调用方凭证）
          </Typography.Title>
          <Typography.Text type="secondary">
            创建外部调用方凭证，并在 API 详情页为应用授予访问权限。
          </Typography.Text>
        </div>
        <div className="page-header-actions">
          <Button size="mini" onClick={() => nav('/apipub')}>返回 API 列表</Button>
        </div>
      </div>

      <section className="page-section" aria-labelledby="api-app-create-title">
        <Typography.Title id="api-app-create-title" heading={6} style={{ margin: '0 0 8px' }}>
          创建应用
        </Typography.Title>
        <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 12, fontSize: 12 }}>
          创建后获得 AppKey/Secret。AppSecret 只会在创建成功时显示一次，请及时保存。
        </Typography.Text>
        <Space wrap>
          <Input
            id="api-app-name"
            style={{ width: 260 }}
            placeholder="新应用名称，如：门户系统"
            value={newName}
            onChange={setNewName}
            onPressEnter={onCreate}
          />
          <Button type="primary" icon={<IconPlus />} loading={creating} onClick={onCreate}>
            创建应用
          </Button>
          <Button icon={<IconRefresh />} onClick={reload}>刷新</Button>
        </Space>
      </section>

      <section className="page-section natural-height" aria-labelledby="api-app-list-title">
        <Typography.Title id="api-app-list-title" heading={6} style={{ margin: '0 0 14px' }}>
          已授权应用
        </Typography.Title>
        {listError && !loading ? (
          <div className="page-error" role="alert">
            <Typography.Text>应用列表加载失败，请重试。</Typography.Text>
            <div style={{ marginTop: 10 }}>
              <Button size="small" onClick={reload}>重试</Button>
            </div>
          </div>
        ) : apps.length === 0 && !loading ? (
          <div className="page-empty">
            <Typography.Text>还没有应用。</Typography.Text>
            <div style={{ marginTop: 10 }}>
              <Button type="primary" size="small" icon={<IconPlus />} onClick={() => document.getElementById('api-app-name')?.focus()}>
                创建应用
              </Button>
            </div>
          </div>
        ) : (
          <Table
            size="small"
            rowKey="id"
            loading={loading}
            data={apps}
            pagination={false}
            columns={[
              { title: '名称', dataIndex: 'name' },
              {
                title: 'AppKey',
                dataIndex: 'appKey',
                render: (k: string) => (
                  <Space size={4}>
                    <code>{k}</code>
                    <Button
                      size="mini"
                      type="text"
                      title="复制 AppKey"
                      aria-label="复制 AppKey"
                      icon={<IconCopy />}
                      onClick={() => copy(k, 'AppKey')}
                    />
                  </Space>
                ),
              },
              { title: 'Secret（脱敏）', dataIndex: 'secretMask', render: (m: string) => <code>{m}</code> },
              {
                title: '创建时间',
                dataIndex: 'createdAt',
                render: (t: string) => (t ? t.replace('T', ' ').slice(0, 16) : ''),
              },
              {
                title: '操作',
                width: 220,
                render: (_, r) => editingId === r.id ? (
                  <Space size={4} wrap>
                    <Input
                      size="mini"
                      value={editingName}
                      style={{ width: 120 }}
                      onChange={setEditingName}
                      onPressEnter={onRename}
                    />
                    <Button type="text" size="mini" loading={renaming} onClick={onRename}>保存</Button>
                    <Button type="text" size="mini" disabled={renaming} onClick={cancelRename}>取消</Button>
                  </Space>
                ) : (
                  <Space size={4} wrap>
                    <Button type="text" size="mini" onClick={() => startRename(r)}>改名</Button>
                    <Button type="text" size="mini" onClick={() => showSecret(r)}>查看密钥</Button>
                    <Button type="text" size="mini" status="danger" onClick={() => onDelete(r)}>
                      删除
                    </Button>
                  </Space>
                ),
              },
            ]}
          />
        )}
      </section>
    </div>
  );
}
