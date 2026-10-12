import { useCallback, useEffect, useState } from 'react';
import { Button, Message, Modal, Space, Table, Tag, Typography } from '@arco-design/web-react';
import { IconPlus, IconRefresh } from '@arco-design/web-react/icon';
import { useNavigate } from 'react-router-dom';
import { deleteEtlTask, EtlTask, listEtlTasks, setEtlStatus } from '../../api/etl';
import { DataSourceInfo, listDatasources } from '../../api/datasource';

const scheduleLabel: Record<string, string> = { MANUAL: '手动', ONCE: '单次', CRON: 'Cron' };
const statusLabel: Record<string, [string, string]> = { DRAFT: ['草稿', 'gray'], ACTIVE: ['运行中', 'green'], PAUSED: ['已暂停', 'orange'] };

export default function EtlPage() {
  const nav = useNavigate();
  const [tasks, setTasks] = useState<EtlTask[]>([]);
  const [sources, setSources] = useState<DataSourceInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const reload = useCallback(async () => {
    setLoading(true); setError(false);
    try { setTasks(await listEtlTasks()); setSources(await listDatasources()); }
    catch (e: any) { setError(true); Message.error(e.response?.data?.message || '数据集成任务加载失败'); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { reload(); }, [reload]);
  const sourceName = (id: number) => sources.find((s) => s.id === id)?.name || `数据源 #${id}`;
  function remove(task: EtlTask) {
    Modal.confirm({ title: '删除数据集成任务', content: `确定删除「${task.name}」吗？`, okButtonProps: { status: 'danger' },
      onOk: async () => { try { await deleteEtlTask(task.id); Message.success('已删除'); reload(); } catch (e: any) { Message.error(e.response?.data?.message || '删除失败'); } } });
  }
  async function toggle(task: EtlTask) {
    try { await setEtlStatus(task.id, task.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE'); Message.success(task.status === 'ACTIVE' ? '任务已暂停' : '任务已启用'); reload(); }
    catch (e: any) { Message.error(e.response?.data?.message || '状态更新失败'); }
  }
  return <div className="page-shell">
    <div className="page-header"><div className="page-header-main"><Typography.Title heading={4} style={{ margin: 0 }}>数据集成</Typography.Title><Typography.Text type="secondary">把外部数据按字段映射同步到目标数据源，统一管理任务和运行记录</Typography.Text></div>
      <div className="page-header-actions"><Button size="mini" icon={<IconRefresh />} onClick={reload}>刷新</Button><Button type="primary" size="mini" icon={<IconPlus />} onClick={() => nav('/etl/new')}>新建任务</Button></div></div>
    <section className="page-section natural-height"><Typography.Title heading={6} style={{ margin: '0 0 14px' }}>同步任务</Typography.Title>
      {error && !loading ? <div className="page-error">任务列表加载失败，请重试。<div style={{ marginTop: 10 }}><Button size="mini" onClick={reload}>重试</Button></div></div> : tasks.length === 0 && !loading ? <div className="page-empty">还没有数据集成任务。<div style={{ marginTop: 12 }}><Button type="primary" size="small" onClick={() => nav('/etl/new')}>创建第一个任务</Button></div></div> : <Table size="small" rowKey="id" loading={loading} data={tasks} pagination={false} columns={[
        { title: '任务名称', dataIndex: 'name', render: (v: string, r: EtlTask) => <Button type="text" size="mini" onClick={() => nav(`/etl/${r.id}`)}>{v}</Button> },
        { title: '源 → 目标', render: (_: unknown, r: EtlTask) => `${sourceName(r.sourceDatasourceId)} → ${sourceName(r.targetDatasourceId)}` },
        { title: '映射', dataIndex: 'mappingCount', width: 80 }, { title: '调度', dataIndex: 'scheduleType', width: 90, render: (v: string) => scheduleLabel[v] || v },
        { title: '状态', dataIndex: 'status', width: 90, render: (v: string) => <Tag color={statusLabel[v]?.[1] as any}>{statusLabel[v]?.[0] || v}</Tag> },
        { title: '更新时间', dataIndex: 'updatedAt', width: 150, render: (v: string) => v?.replace('T', ' ').slice(0, 16) },
        { title: '操作', width: 180, render: (_: unknown, r: EtlTask) => <Space><Button type="text" size="mini" onClick={() => toggle(r)}>{r.status === 'ACTIVE' ? '暂停' : '启用'}</Button><Button type="text" size="mini" onClick={() => nav(`/etl/${r.id}`)}>编辑</Button><Button type="text" status="danger" size="mini" onClick={() => remove(r)}>删除</Button></Space> },
      ]} />}
    </section>
  </div>;
}
