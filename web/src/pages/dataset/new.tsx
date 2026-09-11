import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button, Card, Input, Message, Space, Steps, Table, Tag, Typography, Upload,
} from '@arco-design/web-react';
import { IconArrowRise, IconUpload } from '@arco-design/web-react/icon';
import { ColumnDef, confirmDataset, uploadDataset } from '../../api/dataset';

export default function DatasetNewPage() {
  const nav = useNavigate();
  const [step, setStep] = useState(0);
  const [uploading, setUploading] = useState(false);
  const [importing, setImporting] = useState(false);
  const [result, setResult] = useState<{ uploadId: string; fileName: string; columns: ColumnDef[]; previewRows: string[][]; rowCount: number } | null>(null);
  const [name, setName] = useState('');
  const fileRef = useRef<File | null>(null);

  async function handleUpload(_file: File, fileList: { file: File }[]) {
    const f = fileList[0]?.file;
    if (!f) return;
    fileRef.current = f;
    setUploading(true);
    try {
      const r = await uploadDataset(f);
      setResult({
        uploadId: r.uploadId,
        fileName: r.fileName,
        columns: r.columns.map((c, i) => ({ name: c, type: r.types[i] })),
        previewRows: r.previewRows,
        rowCount: r.rowCount,
      });
      setName(f.name.replace(/\.(csv|xlsx|xls|txt)$/i, ''));
      setStep(1);
    } catch (e: any) {
      Message.error(e.response?.data?.message || '上传失败');
    } finally {
      setUploading(false);
    }
  }

  async function onConfirm() {
    if (!result) return;
    if (!name.trim()) {
      Message.warning('请输入数据集名称');
      return;
    }
    setImporting(true);
    try {
      await confirmDataset({
        uploadId: result.uploadId,
        name: name.trim(),
        fileName: result.fileName,
        columns: result.columns.map((c) => c.name),
        types: result.columns.map((c) => c.type),
      });
      Message.success('导入成功');
      nav('/dataset');
    } catch (e: any) {
      Message.error(e.response?.data?.message || '导入失败');
    } finally {
      setImporting(false);
    }
  }

  function reset() {
    setResult(null);
    setStep(0);
    setName('');
    fileRef.current = null;
  }

  const TYPE_COLOR: Record<string, string> = {
    bigint: 'arcoblue', numeric: 'cyan', text: 'gray',
    timestamp: 'purple', date: 'purple', boolean: 'green',
  };

  return (
    <Card
      title="上传数据集"
      style={{ maxWidth: 860 }}
      extra={
        <Button type="text" onClick={() => nav('/dataset')}>返回列表</Button>
      }
    >
      <Steps current={step} style={{ marginBottom: 28 }}>
        <Steps.Step title="选择文件" />
        <Steps.Step title="确认并导入" />
      </Steps>

      {step === 0 && (
        <Upload
          drag
          multiple={false}
          limit={1}
          customRequest={(option) => {
            handleUpload(option.file as File, []);
            return { abort: () => {} };
          }}
          accept=".csv,.xlsx,.xls,.txt"
          showUploadList={false}
          disabled={uploading}
        >
          <div style={{ textAlign: 'center', padding: '56px 0' }}>
            <div style={{ marginBottom: 16 }}>
              <IconUpload style={{ fontSize: 40, color: 'var(--daaspro-primary)' }} />
            </div>
            <Typography.Title heading={6} style={{ marginTop: 0 }}>
              点击或拖拽文件到此处上传
            </Typography.Title>
            <Typography.Text type="secondary">
              支持 CSV / XLSX，单文件不超过 20MB；首行为表头
            </Typography.Text>
          </div>
        </Upload>
      )}

      {step === 1 && result && (
        <div>
          <Space style={{ marginBottom: 16 }}>
            <Typography.Text>识别到 {result.columns.length} 列，{result.rowCount} 行数据：</Typography.Text>
            {result.columns.map((c) => (
              <Tag key={c.name} color={TYPE_COLOR[c.type] || 'gray'}>
                {c.name} · {c.type}
              </Tag>
            ))}
          </Space>

          <Table
            data={result.previewRows.map((row, i) => ({ __i: i, row }))}
            rowKey="__i"
            size="small"
            pagination={{ pageSize: 10 }}
            columns={result.columns.map((c, idx) => ({
              title: `${c.name}`,
              render: (_: unknown, record: { row: string[] }) => record.row[idx] ?? '',
              ellipsis: true,
              width: 140,
            }))}
          />
          <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
            仅展示前 {result.previewRows.length} 行预览
          </Typography.Text>

          <div style={{ marginTop: 24, maxWidth: 360 }}>
            <Input
              value={name}
              onChange={setName}
              placeholder="数据集名称"
              addBefore="名称"
            />
          </div>

          <Space style={{ marginTop: 20 }}>
            <Button onClick={reset}>重新选择文件</Button>
            <Button
              type="primary"
              loading={importing}
              onClick={onConfirm}
              icon={<IconArrowRise />}
            >
              确认导入
            </Button>
          </Space>
        </div>
      )}
    </Card>
  );
}
