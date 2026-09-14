import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button, Card, Form, Input, Message, Select, Space, Steps, Table, Tag, Typography, Upload,
} from '@arco-design/web-react';
import { IconArrowRise, IconUpload } from '@arco-design/web-react/icon';
import { ColumnDef, confirmDataset, uploadDataset } from '../../api/dataset';
import { listDatasources, listTables } from '../../api/datasource';

const TYPE_COLOR: Record<string, string> = {
  bigint: 'arcoblue', numeric: 'cyan', text: 'gray',
  timestamp: 'purple', date: 'purple', boolean: 'green',
};

interface ParseResult {
  uploadId: string;
  fileName: string;
  columns: ColumnDef[];
  previewRows: string[][];
  rowCount: number;
}

export default function DatasetNewPage() {
  const nav = useNavigate();
  const [step, setStep] = useState(0);
  const [uploading, setUploading] = useState(false);
  const [importing, setImporting] = useState(false);
  const [result, setResult] = useState<ParseResult | null>(null);
  const [fileRef, setFileRef] = useState<File | null>(null);

  async function handleUpload(f: File) {
    if (!f) {
      Message.warning('未读到文件，请重新选择');
      return;
    }
    setFileRef(f);
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
      setStep(1);
    } catch (e: any) {
      Message.error(e.response?.data?.message || '上传失败');
    } finally {
      setUploading(false);
    }
  }

  async function onConfirm(v: any) {
    if (!result) return;
    setImporting(true);
    try {
      await confirmDataset({
        uploadId: result.uploadId,
        name: String(v.name || '').trim(),
        fileName: result.fileName,
        columns: result.columns.map((c) => c.name),
        types: result.columns.map((c) => c.type),
        datasourceId: v.datasourceId,
        existingTable: v.mode === 'append' ? v.existingTable : undefined,
      });
      Message.success('导入成功');
      nav('/datamanage');
    } catch (e: any) {
      Message.error(e.response?.data?.message || '导入失败');
    } finally {
      setImporting(false);
    }
  }

  return (
    <Card
      title="上传数据"
      style={{ maxWidth: 880 }}
      extra={<Button type="text" onClick={() => nav('/datamanage')}>返回</Button>}
    >
      <Steps current={step} style={{ marginBottom: 28 }}>
        <Steps.Step title="选择文件" />
        <Steps.Step title="选择入库位置" />
      </Steps>

      {step === 0 && (
        <Upload
          drag
          multiple={false}
          limit={1}
          customRequest={(option) => {
            handleUpload(option.file as File);
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
        <ImportForm
          result={result}
          importing={importing}
          defaultName={fileRef?.name?.replace(/\.(csv|xlsx|xls|txt)$/i, '') || ''}
          onConfirm={onConfirm}
          onReset={() => {
            setResult(null);
            setStep(0);
            setFileRef(null);
          }}
        />
      )}
    </Card>
  );
}

function ImportForm({
  result, importing, defaultName, onConfirm, onReset,
}: {
  result: ParseResult;
  importing: boolean;
  defaultName: string;
  onConfirm: (v: { name: string; datasourceId: number; existingTable?: string }) => void;
  onReset: () => void;
}) {
  const [form] = Form.useForm();
  const [dsOptions, setDsOptions] = useState<{ value: number; label: string }[]>([]);
  const [tableOptions, setTableOptions] = useState<{ value: string; label: string }[]>([]);
  const [mode, setMode] = useState<'new' | 'append'>('new');
  const [watchDs, setWatchDs] = useState<number>();

  useEffect(() => {
    listDatasources().then((list) => {
      setDsOptions(list.map((d) => ({
        value: d.id,
        label: d.builtin ? `${d.name}（内置，可写入）` : `${d.name}（${d.type}，MVP 只读）`,
      })));
      const builtin = list.find((d) => d.builtin);
      if (builtin) {
        form.setFieldValue('datasourceId', builtin.id);
        setWatchDs(builtin.id);
      }
    });
  }, [form]);

  useEffect(() => {
    if (watchDs) {
      listTables(watchDs)
        .then((tables) => setTableOptions(tables.map((t) => ({ value: t.name, label: t.name }))))
        .catch(() => setTableOptions([]));
    }
  }, [watchDs]);

  return (
    <div>
      <Space style={{ marginBottom: 16 }} wrap>
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

      <Form
        form={form}
        onSubmit={onConfirm}
        layout="vertical"
        style={{ marginTop: 24, maxWidth: 420 }}
        initialValues={{ mode: 'new', name: defaultName } as any}
      >
        <Form.Item label="数据名称" field="name" rules={[{ required: true, message: '请输入名称' }]}>
          <Input placeholder="如：销售明细" />
        </Form.Item>
        <Form.Item label="导入到数据源" field="datasourceId" rules={[{ required: true, message: '请选择数据源' }]}>
          <Select
            options={dsOptions}
            onChange={(v) => {
              setWatchDs(v as number);
              form.setFieldValue('existingTable', undefined);
            }}
          />
        </Form.Item>
        <Form.Item label="导入方式" field="mode">
          <Select
            onChange={(v) => setMode(v as 'new' | 'append')}
            options={[
              { value: 'new', label: '新建表' },
              { value: 'append', label: '追加到已有表' },
            ]}
          />
        </Form.Item>
        {mode === 'append' && (
          <Form.Item
            label="目标表"
            field="existingTable"
            rules={[{ required: true, message: '请选择目标表' }]}
            extra="目标表列名需与上传文件表头完全一致"
          >
            <Select options={tableOptions} placeholder="选择已有表" showSearch />
          </Form.Item>
        )}
        <Space style={{ marginTop: 8 }}>
          <Button onClick={onReset}>重新选择文件</Button>
          <Button type="primary" htmlType="submit" loading={importing} icon={<IconArrowRise />}>
            确认导入
          </Button>
        </Space>
      </Form>
    </div>
  );
}
