import { useEffect, useState } from 'react';
import { Button, Message, Space, Table, Tag, Typography } from '@arco-design/web-react';
import { useNavigate, useParams } from 'react-router-dom';
import { EtlMapping, EtlRun, EtlTask, getEtlTask, listEtlMappings, listEtlRuns, runEtlTask, setEtlStatus } from '../../api/etl';

export default function EtlDetailPage() { const nav = useNavigate(); const { id } = useParams(); const [task, setTask] = useState<EtlTask | null>(null); const [mappings, setMappings] = useState<EtlMapping[]>([]); const [runs, setRuns] = useState<EtlRun[]>([]);
  async function reload() { if (!id) return; try { setTask(await getEtlTask(Number(id))); setMappings(await listEtlMappings(Number(id))); setRuns(await listEtlRuns(Number(id))); } catch (e: any) { Message.error(e.response?.data?.message || '任务加载失败'); } }
  useEffect(() => { reload(); }, [id]);
  useEffect(() => {
    if (!runs.some((run) => run.status === 'QUEUED' || run.status === 'RUNNING')) return;
    const timer = setInterval(() => { reload(); }, 2000);
    return () => clearInterval(timer);
  }, [id, runs]);
  if (!task) return <div className="page-empty">正在加载任务…</div>;
  return <div className="page-shell"><div className="page-header"><div className="page-header-main"><Typography.Title heading={4} style={{ margin: 0 }}>{task.name}</Typography.Title><Typography.Text type="secondary">{task.description || '未填写描述'}</Typography.Text></div><Space><Button onClick={() => nav('/etl')}>返回</Button><Button onClick={() => nav(`/etl/${id}/edit`)}>编辑配置</Button><Button type="primary" onClick={async () => { try { await setEtlStatus(task.id, task.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE'); Message.success('状态已更新'); reload(); } catch (e: any) { Message.error(e.response?.data?.message || '状态更新失败'); } }}>{task.status === 'ACTIVE' ? '暂停任务' : '启用任务'}</Button></Space></div>
    <section className="page-section natural-height"><Space size={18}><Tag color={task.status === 'ACTIVE' ? 'green' : 'gray'}>{task.status}</Tag><Typography.Text>源数据源 #{task.sourceDatasourceId} → 目标数据源 #{task.targetDatasourceId}</Typography.Text><Typography.Text type="secondary">{task.mappingCount} 组映射 · 配置 v{task.configVersion}</Typography.Text><Button type="primary" size="small" onClick={async () => { try { await runEtlTask(task.id); Message.success('已提交运行'); reload(); } catch (e: any) { Message.error(e.response?.data?.message || '运行提交失败'); } }}>立即运行</Button></Space></section>
    <section className="page-section natural-height"><Typography.Title heading={6} style={{ margin: '0 0 14px' }}>表映射</Typography.Title><Table size="small" pagination={false} data={mappings} rowKey="id" columns={[{ title: '输入', render: (_: unknown, r: EtlMapping) => r.inputType === 'TABLE' ? `${r.sourceSchema || 'public'}.${r.sourceTable}` : '参数化 SELECT' }, { title: '目标表', dataIndex: 'targetTable' }, { title: '写入策略', dataIndex: 'writeMode' }, { title: '字段映射', render: (_: unknown, r: EtlMapping) => { try { return `${JSON.parse(r.fieldMappingsJson).length} 个字段`; } catch { return '—'; } } }]} /></section>
    <section className="page-section natural-height"><Typography.Title heading={6} style={{ margin: '0 0 14px' }}>运行记录</Typography.Title><Table size="small" pagination={false} data={runs} rowKey="id" columns={[{ title: '运行编号', dataIndex: 'id' }, { title: '状态', dataIndex: 'status' }, { title: '读行数', dataIndex: 'readRows' }, { title: '写行数', dataIndex: 'writtenRows' }, { title: '创建时间', dataIndex: 'createdAt', render: (v: string) => v?.replace('T', ' ').slice(0, 16) }, { title: '操作', render: (_: unknown, r: EtlRun) => <Button type="text" size="mini" onClick={() => nav(`/etl/${id}/runs/${r.id}`)}>查看详情</Button> }]} /></section>
  </div>;
}
