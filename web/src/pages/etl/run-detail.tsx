import { useEffect, useState } from 'react';
import { Button, Message, Space, Tag, Typography } from '@arco-design/web-react';
import { useNavigate, useParams } from 'react-router-dom';
import { cancelEtlRun, EtlRun, getEtlRun, retryEtlRun } from '../../api/etl';

export default function EtlRunDetailPage() {
  const nav = useNavigate();
  const { runId } = useParams();
  const [run, setRun] = useState<EtlRun | null>(null);
  async function reload() {
    if (!runId) return;
    try { setRun(await getEtlRun(Number(runId))); }
    catch (e: any) { Message.error(e.response?.data?.message || '运行详情加载失败'); }
  }
  useEffect(() => { reload(); const timer = setInterval(reload, 2000); return () => clearInterval(timer); }, [runId]);
  if (!run) return <div className="page-empty">正在加载运行详情…</div>;
  const active = ['QUEUED', 'RUNNING'].includes(run.status);
  return <div className="page-shell"><div className="page-header"><div className="page-header-main"><Typography.Title heading={4} style={{ margin: 0 }}>运行详情 #{run.id}</Typography.Title><Typography.Text type="secondary">任务 #{run.taskId} · 第 {run.currentAttempt} 次尝试</Typography.Text></div><Space><Button onClick={() => nav(`/etl/${run.taskId}`)}>返回任务</Button>{active && <Button status="danger" onClick={async () => { try { await cancelEtlRun(run.id); Message.success('取消请求已发送'); reload(); } catch (e: any) { Message.error(e.response?.data?.message || '取消失败'); } }}>取消运行</Button>}{run.status === 'FAILED' && <Button type="primary" onClick={async () => { try { await retryEtlRun(run.id); Message.success('重试已提交'); nav(`/etl/${run.taskId}`); } catch (e: any) { Message.error(e.response?.data?.message || '重试失败'); } }}>重试</Button>}</Space></div>
    <section className="page-section natural-height"><Space size={18}><Tag color={run.status === 'SUCCESS' ? 'green' : run.status === 'FAILED' ? 'red' : 'arcoblue'}>{run.status}</Tag><Typography.Text>读取 {run.readRows} 行</Typography.Text><Typography.Text>写入 {run.writtenRows} 行</Typography.Text></Space></section>
    {run.errorSummary && <section className="page-error"><Typography.Text>{run.errorSummary}</Typography.Text></section>}
  </div>;
}
