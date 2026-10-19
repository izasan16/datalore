import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button, Form, Grid, Input, Message, Select, Space, Steps, Table, Typography, Upload,
} from '@arco-design/web-react';
import { IconArrowRise, IconUpload } from '@arco-design/web-react/icon';
import { confirmImported, uploadImported } from '../../api/imported';
import { ColumnInfo, listDatasources, listSchemas, listTables } from '../../api/datasource';

const { Row, Col } = Grid;

const TYPE_OPTIONS = [
  { label: 'bigint（整数）', value: 'bigint' },
  { label: 'numeric（数值）', value: 'numeric' },
  { label: 'text（文本）', value: 'text' },
  { label: 'timestamp（时间戳）', value: 'timestamp' },
  { label: 'date（日期）', value: 'date' },
  { label: 'boolean（布尔）', value: 'boolean' },
];

interface FieldRow {
  sourceName: string;
  name: string;
  type: string;
}

interface ParseResult {
  uploadId: string;
  fileName: string;
  columns: ColumnInfo[];
  previewRows: string[][];
  rowCount: number;
}

export default function DataManageNewPage() {
  const nav = useNavigate();
  const [step, setStep] = useState(0);
  const [uploading, setUploading] = useState(false);
  const [importing, setImporting] = useState(false);
  const [result, setResult] = useState<ParseResult | null>(null);
  const [fileRef, setFileRef] = useState<File | null>(null);
  const [fields, setFields] = useState<FieldRow[]>([]);
  const [fieldErrors, setFieldErrors] = useState<string[]>([]);

  async function handleUpload(f: File) {
    if (!f) {
      Message.warning('未读到文件，请重新选择');
      return;
    }
    setFileRef(f);
    setUploading(true);
    try {
      const r = await uploadImported(f);
      const parsed: ParseResult = {
        uploadId: r.uploadId,
        fileName: r.fileName,
        columns: r.columns.map((c, i) => ({ name: c, type: r.types[i] })),
        previewRows: r.previewRows,
        rowCount: r.rowCount,
      };
      setResult(parsed);
      setFields(parsed.columns.map((c) => ({ sourceName: c.name, name: c.name, type: c.type })));
      setFieldErrors([]);
    } catch (e: any) {
      Message.error(e.response?.data?.message || '上传失败');
    } finally {
      setUploading(false);
    }
  }

  function validateFields(): boolean {
    const errs: string[] = [];
    const names = fields.map((f) => f.name.trim());
    if (names.some((n) => !n)) errs.push('字段名不能为空');
    if (new Set(names).size !== names.length) errs.push('字段名不能重复');
    if (fields.some((f) => !TYPE_OPTIONS.some((t) => t.value === f.type))) errs.push('请为每个字段选择类型');
    setFieldErrors(errs);
    return errs.length === 0;
  }

  function nextFromFields() {
    if (validateFields()) setStep(2);
  }

  async function onConfirm(v: any) {
    if (!result) return;
    setImporting(true);
    try {
      await confirmImported({
        uploadId: result.uploadId,
        name: String(v.name || '').trim(),
        fileName: result.fileName,
        columns: fields.map((f) => f.name.trim()),
        types: fields.map((f) => f.type),
        datasourceId: v.datasourceId,
        schemaName: v.schemaName || undefined,
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

  const defaultName = fileRef?.name?.replace(/\.(csv|xlsx|xls|txt)$/i, '') || '';

  return (
    <div className="page-shell">
      <div className="page-header">
        <div className="page-header-main">
          <Typography.Title heading={4} style={{ margin: 0 }}>
            导入数据
          </Typography.Title>
          <Typography.Text type="secondary">
            上传文件、确认字段设置，然后选择入库位置
          </Typography.Text>
        </div>
        <div className="page-header-actions">
          <Button type="text" onClick={() => nav('/datamanage')}>返回列表</Button>
        </div>
      </div>

      <section className="page-section natural-height" style={{ maxWidth: 960, width: '100%', margin: '0 auto' }}>
        <Steps current={step + 1} style={{ marginBottom: 28 }}>
          <Steps.Step title="选择文件" />
          <Steps.Step title="字段设置" />
          <Steps.Step title="入库位置" />
        </Steps>

        {step === 0 && (
          <>
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
              <div style={{ textAlign: 'center', padding: result ? '28px 0' : '72px 0' }}>
                <div style={{ marginBottom: 16 }}>
                  <IconUpload style={{ fontSize: 40, color: 'var(--Datalore-primary)' }} />
                </div>
                <Typography.Title heading={6} style={{ marginTop: 0 }}>
                  点击或拖拽文件到此处上传
                </Typography.Title>
                <Typography.Text type="secondary">
                  支持 CSV / XLSX，单文件不超过 20MB；首行为表头
                </Typography.Text>
              </div>
            </Upload>
            {result && (
              <div style={{ marginTop: 24 }}>
                <PreviewBlock result={result} />
                <Space style={{ marginTop: 20 }}>
                  <Button type="primary" onClick={() => setStep(1)}>下一步</Button>
                </Space>
              </div>
            )}
          </>
        )}

        {step === 1 && result && (
          <FieldSettingStep
            fields={fields}
            errors={fieldErrors}
            onChange={setFields}
            onPrev={() => setStep(0)}
            onNext={nextFromFields}
          />
        )}

        {step === 2 && result && (
          <LocationStep
            result={result}
            importing={importing}
            defaultName={defaultName}
            fieldCount={fields.length}
            onConfirm={onConfirm}
            onPrev={() => setStep(1)}
          />
        )}
      </section>
    </div>
  );
}

function previewColumns(result: ParseResult) {
  return result.columns.map((c, idx) => ({
    title: c.name,
    render: (_: unknown, record: { row: string[] }) => record.row[idx] ?? '',
    ellipsis: true,
  }));
}

/** 第 1 步：上传后的数据预览。 */
function PreviewBlock({ result }: { result: ParseResult }) {
  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Typography.Text>识别到 {result.columns.length} 列，{result.rowCount} 行数据：</Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>文件 {result.fileName}</Typography.Text>
      </Space>
      <div className="bounded-checks">
        <Table
          data={result.previewRows.map((row, i) => ({ __i: i, row }))}
          rowKey="__i"
          size="default"
          pagination={false}
          columns={previewColumns(result) as any}
        />
      </div>
      <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
        预览前 {result.previewRows.length} 行
      </Typography.Text>
    </div>
  );
}

/** 第 2 步：字段设置（对齐 DaaS FieldSetting：原字段名 / 字段名 / 字段类型）。 */
function FieldSettingStep({
  fields, errors, onChange, onPrev, onNext,
}: {
  fields: FieldRow[];
  errors: string[];
  onChange: (fields: FieldRow[]) => void;
  onPrev: () => void;
  onNext: () => void;
}) {
  function update(idx: number, patch: Partial<FieldRow>) {
    onChange(fields.map((f, i) => (i === idx ? { ...f, ...patch } : f)));
  }

  return (
    <div>
      <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
        共识别到 {fields.length} 个字段，可修改字段名和类型；确认后按此定义建表
      </Typography.Text>
      {errors.length > 0 && (
        <div className="page-error" role="alert" style={{ marginBottom: 12 }}>
          {errors.map((e) => (
            <Typography.Text key={e} style={{ display: 'block' }}>{e}</Typography.Text>
          ))}
        </div>
      )}
      <Table
        data={fields.map((f, i) => ({ ...f, __i: i }))}
        rowKey="__i"
        size="default"
        pagination={false}
        columns={[
          { title: '原字段名', dataIndex: 'sourceName', width: 200 },
          {
            title: '字段名', dataIndex: 'name', width: 240,
            render: (_: unknown, record: FieldRow & { __i: number }) => (
              <Input
                value={record.name}
                placeholder="字段名"
                onChange={(v: string) => update(record.__i, { name: v })}
              />
            ),
          },
          {
            title: '字段类型', dataIndex: 'type', width: 220,
            render: (_: unknown, record: FieldRow & { __i: number }) => (
              <Select
                value={record.type}
                options={TYPE_OPTIONS}
                style={{ width: '100%' }}
                onChange={(v: string) => update(record.__i, { type: v })}
              />
            ),
          },
        ] as any}
      />
      <Space style={{ marginTop: 20 }}>
        <Button onClick={onPrev}>上一步</Button>
        <Button type="primary" onClick={onNext}>下一步</Button>
      </Space>
    </div>
  );
}

/** 第 3 步：入库位置。 */
function LocationStep({
  result, importing, defaultName, fieldCount, onConfirm, onPrev,
}: {
  result: ParseResult;
  importing: boolean;
  defaultName: string;
  fieldCount: number;
  onConfirm: (v: any) => void;
  onPrev: () => void;
}) {
  const [form] = Form.useForm();
  const [mode, setMode] = useState<'new' | 'append'>('new');
  const [dsList, setDsList] = useState<{ label: string; value: number }[]>([]);
  const [dsLoading, setDsLoading] = useState(false);
  const [dsError, setDsError] = useState(false);
  const [watchDs, setWatchDs] = useState<number | null>(null);
  const [schemas, setSchemas] = useState<{ label: string; value: string }[]>([]);
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [, setSchemaError] = useState(false);
  const [tables, setTables] = useState<{ label: string; value: string }[]>([]);
  const [tableLoading, setTableLoading] = useState(false);
  const [tableError, setTableError] = useState(false);

  useEffect(() => {
    setDsLoading(true);
    listDatasources()
      .then((list) => {
        setDsList(list.map((d) => ({ label: `${d.name}（${d.type}）`, value: d.id })));
        setDsError(false);
      })
      .catch(() => setDsError(true))
      .finally(() => setDsLoading(false));
  }, []);

  useEffect(() => {
    if (!watchDs) return;
    setSchemaLoading(true);
    listSchemas(watchDs)
      .then((list) => {
        setSchemas(list.map((s) => ({ label: s.name, value: s.name })));
        setSchemaError(false);
      })
      .catch(() => setSchemaError(true))
      .finally(() => setSchemaLoading(false));
  }, [watchDs]);

  useEffect(() => {
    if (!watchDs) return;
    setTableLoading(true);
    const schema = form.getFieldValue('schemaName');
    listTables(watchDs, schema)
      .then((list) => {
        setTables(list.map((t) => ({ label: t.name, value: t.name })));
        setTableError(false);
      })
      .catch(() => setTableError(true))
      .finally(() => setTableLoading(false));
  }, [watchDs, form]);

  return (
    <div>
      <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 16 }}>
        将按已设置的 {fieldCount} 个字段建表并导入 {result.rowCount} 行数据
      </Typography.Text>
      <Form
        form={form}
        onSubmit={onConfirm}
        layout="vertical"
        initialValues={{ mode: 'new', name: defaultName } as any}
      >
        <Row gutter={16}>
          <Col xs={24} sm={12}>
            <Form.Item label="导入方式" field="mode">
              <Select
                onChange={(v) => setMode(v as 'new' | 'append')}
                options={[
                  { value: 'new', label: '新建表' },
                  { value: 'append', label: '追加到已有表' },
                ]}
              />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12}>
            <Form.Item label="导入到数据源" field="datasourceId" rules={[{ required: true, message: '请选择数据源' }]}>
              <Select
                options={dsList}
                loading={dsLoading}
                onChange={(v) => {
                  setWatchDs(v as number);
                  form.setFieldValue('existingTable', undefined);
                }}
              />
            </Form.Item>
            {dsError && (
              <div className="page-error" role="alert" style={{ marginTop: -12, padding: '8px 12px', textAlign: 'left' }}>
                <Typography.Text>数据源加载失败，请重试。</Typography.Text>
                <Button size="mini" type="text" loading={dsLoading} onClick={() => undefined}>
                  重试
                </Button>
              </div>
            )}
          </Col>
        </Row>
        <Row gutter={16}>
          <Col xs={24} sm={12}>
            <Form.Item label="Schema" field="schemaName" extra="不选则使用数据源默认 schema">
              <Select
                options={schemas}
                loading={schemaLoading}
                placeholder="默认 schema"
                showSearch
                allowClear
                onChange={() => {
                  form.setFieldValue('existingTable', undefined);
                  if (watchDs) {
                    setTableLoading(true);
                    listTables(watchDs, form.getFieldValue('schemaName'))
                      .then((list) => {
                        setTables(list.map((t) => ({ label: t.name, value: t.name })));
                        setTableError(false);
                      })
                      .catch(() => setTableError(true))
                      .finally(() => setTableLoading(false));
                  }
                }}
              />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12}>
            {mode === 'new' ? (
              <Form.Item
                label="表名"
                field="name"
                rules={[
                  { required: true, message: '请输入表名' },
                  { match: /^[\p{L}_][\p{L}\p{N}_]{0,62}$/u, message: '支持中英文、数字、下划线，须以中文、字母或下划线开头，不超过 63 个字符' },
                ]}
                extra="将作为数据库中的物理表名"
              >
                <Input placeholder="如：sales_detail" />
              </Form.Item>
            ) : (
              <>
                <Form.Item
                  label="目标表"
                  field="existingTable"
                  rules={[{ required: true, message: '请选择目标表' }]}
                  extra="目标表列名需与上传文件表头完全一致"
                >
                  <Select options={tables} loading={tableLoading} placeholder="选择已有表" showSearch />
                </Form.Item>
                {tableError && (
                  <div className="page-error" role="alert" style={{ marginTop: -12, padding: '8px 12px', textAlign: 'left' }}>
                    <Typography.Text>目标表加载失败，请重试。</Typography.Text>
                    <Button size="mini" type="text" loading={tableLoading} onClick={() => watchDs && undefined}>
                      重试
                    </Button>
                  </div>
                )}
              </>
            )}
          </Col>
        </Row>
        <Space style={{ marginTop: 8 }}>
          <Button onClick={onPrev}>上一步</Button>
          <Button type="primary" htmlType="submit" loading={importing} icon={<IconArrowRise />}>
            确认导入
          </Button>
        </Space>
      </Form>
    </div>
  );
}
