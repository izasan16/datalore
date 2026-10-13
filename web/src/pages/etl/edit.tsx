import { useEffect, useState } from 'react';
import { Button, DatePicker, Form, Input, Message, Select, Space, Steps, Tag, Typography } from '@arco-design/web-react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  createEtlTask, EtlTask, getEtlTask, parseEtlDdl, ParsedDdl, updateEtlTask,
} from '../../api/etl';
import {
  ColumnInfo, DataSourceInfo, listColumns, listDatasources, listSchemas, listTables,
} from '../../api/datasource';
import { runSql } from '../../api/sqllab';
import FieldMappingPanel, { FieldCol, FieldPair } from './FieldMapping';

const StepItem = Steps.Step;


function platformType(raw: string): string {
  const t = (raw || '').toLowerCase();
  if (t.includes('bool')) return 'boolean';
  if (t === 'date') return 'date';
  if (t.includes('timestamp') || t.includes('datetime')) return 'timestamp';
  if (t.includes('int') || t.includes('serial')) return 'bigint';
  if (t.includes('numeric') || t.includes('decimal') || t.includes('number') || t.includes('double') || t.includes('float') || t.includes('real') || t.includes('money')) return 'numeric';
  return 'text';
}

function parseFieldMappings(json: string): { source: string; target: string }[] {
  try {
    const arr = JSON.parse(json || '[]');
    return Array.isArray(arr) ? arr.filter((f: any) => f && f.source && f.target) : [];
  } catch {
    return [];
  }
}

export default function EtlEditPage() {
  const nav = useNavigate();
  const { id } = useParams();
  const editing = !!id;
  const [step, setStep] = useState(0);
  const [sources, setSources] = useState<DataSourceInfo[]>([]);
  const [task, setTask] = useState<EtlTask | null>(null);
  const [saving, setSaving] = useState(false);
  const [parsedDdl, setParsedDdl] = useState<ParsedDdl | null>(null);
  const [srcSchemas, setSrcSchemas] = useState<{ name: string }[]>([]);
  const [tgtSchemas, setTgtSchemas] = useState<{ name: string }[]>([]);
  const [srcTables, setSrcTables] = useState<{ name: string }[]>([]);
  const [tgtTables, setTgtTables] = useState<{ name: string }[]>([]);
  const [srcCols, setSrcCols] = useState<FieldCol[]>([]);
  const [tgtCols, setTgtCols] = useState<FieldCol[]>([]);
  const [pairs, setPairs] = useState<FieldPair[]>([]);
  const [mapInputType, setMapInputType] = useState('TABLE');
  const [mapWriteMode, setMapWriteMode] = useState('APPEND');
  const [mapTargetMode, setMapTargetMode] = useState('CREATE');
  const [schedType, setSchedType] = useState('MANUAL');
  const [form] = Form.useForm();
  const [mappingForm] = Form.useForm();
  const taskId = editing ? Number(id) : task?.id;

  useEffect(() => {
    listDatasources().then(setSources).catch(() => Message.error('数据源加载失败'));
    if (id) {
      getEtlTask(Number(id)).then(async (loaded) => {
        setTask(loaded);
        form.setFieldsValue({
          name: loaded.name,
          description: loaded.description,
          sourceDatasourceId: loaded.sourceDatasourceId,
          targetDatasourceId: loaded.targetDatasourceId,
          scheduleType: loaded.scheduleType,
          cronExpression: loaded.cronExpression || undefined,
          maxRetries: 0,
          retryIntervalSeconds: 60,
        });
        setSchedType(loaded.scheduleType);
        if (loaded.hasMapping) await restoreMapping(loaded);
      }).catch(() => Message.error('任务加载失败'));
    }
  }, [id]);

  function loadSchemas(dsId: number | undefined, side: 'src' | 'tgt') {
    if (!dsId) return;
    listSchemas(dsId).then((rows) => (side === 'src' ? setSrcSchemas(rows) : setTgtSchemas(rows)))
      .catch(() => Message.error('Schema 加载失败'));
  }

  function loadTables(dsId: number | undefined, schema: string | undefined, side: 'src' | 'tgt') {
    if (!dsId) return;
    listTables(dsId, schema).then((rows) => (side === 'src' ? setSrcTables(rows) : setTgtTables(rows)))
      .catch(() => Message.error('表加载失败'));
  }

  function loadColumns(dsId: number | undefined, schema: string | undefined, table: string, side: 'src' | 'tgt') {
    if (!dsId) return;
    listColumns(dsId, table, schema).then((rows: ColumnInfo[]) => {
      const cols = rows.map((r) => ({ name: r.name, type: platformType(r.type) }));
      if (side === 'src') setSrcCols(cols); else setTgtCols(cols);
    }).catch(() => Message.error('字段加载失败'));
  }

  async function restoreMapping(m: EtlTask) {
    const srcDs = form.getFieldValue('sourceDatasourceId');
    const tgtDs = form.getFieldValue('targetDatasourceId');
    setMapInputType(m.inputType || 'TABLE');
    setMapWriteMode(m.writeMode || 'APPEND');
    setMapTargetMode(m.targetTableMode || 'CREATE');
    setParsedDdl(null);
    let upsertKeys: string[] = [];
    try { upsertKeys = JSON.parse(m.upsertKeysJson || '[]'); } catch { upsertKeys = []; }
    mappingForm.setFieldsValue({
      inputType: m.inputType || 'TABLE',
      writeMode: m.writeMode || 'APPEND',
      targetTableMode: m.targetTableMode || 'CREATE',
      sourceSchema: m.sourceSchema,
      sourceTable: m.sourceTable,
      targetSchema: m.targetSchema,
      targetTable: m.targetTable,
      selectSql: m.selectSql,
      createTableDdl: m.createTableDdl,
      upsertKeys,
    });
    setPairs(parseFieldMappings(m.fieldMappingsJson || '[]'));
    setSrcCols([]);
    setTgtCols([]);
    if (m.targetTableMode === 'CREATE' && m.createTableDdl) {
      await applyParsedDdl(m.createTableDdl, false);
    }
    if (m.inputType === 'TABLE') {
      try {
        const [ss, ts, st] = await Promise.all([
          listSchemas(srcDs), listSchemas(tgtDs),
          listTables(srcDs, m.sourceSchema),
        ]);
        setSrcSchemas(ss); setTgtSchemas(ts); setSrcTables(st);
        const srcRows = await listColumns(srcDs, m.sourceTable!, m.sourceSchema);
        setSrcCols(srcRows.map((r) => ({ name: r.name, type: platformType(r.type) })));
        if (m.targetTableMode === 'EXISTING') {
          const tgtRows = await listColumns(tgtDs, m.targetTable!, m.targetSchema);
          setTgtCols(tgtRows.map((r) => ({ name: r.name, type: platformType(r.type) })));
        }
      } catch {
        Message.error('映射配置回填失败');
      }
    }
  }

  function onSourceSchemaChange(schema: string) {
    mappingForm.setFieldValue('sourceTable', undefined);
    setSrcCols([]);
    loadTables(form.getFieldValue('sourceDatasourceId'), schema, 'src');
  }

  function onSourceTableChange(table: string) {
    setPairs([]);
    loadColumns(form.getFieldValue('sourceDatasourceId'), mappingForm.getFieldValue('sourceSchema'), table, 'src');
  }

  function onTargetSchemaChange(schema: string) {
    mappingForm.setFieldValue('targetTable', undefined);
    setTgtCols([]);
    setPairs([]);
    loadTables(form.getFieldValue('targetDatasourceId'), schema, 'tgt');
  }

  function onTargetTableChange(table: string) {
    setPairs([]);
    if (mapTargetMode === 'EXISTING') {
      loadColumns(form.getFieldValue('targetDatasourceId'), mappingForm.getFieldValue('targetSchema'), table, 'tgt');
    } else {
      setTgtCols(srcCols.map((c) => ({ ...c })));
    }
  }

  function onTargetModeChange(mode: string) {
    setMapTargetMode(mode);
    setPairs([]);
    setParsedDdl(null);
    const targetTable = mappingForm.getFieldValue('targetTable');
    const targetSchema = mappingForm.getFieldValue('targetSchema');
    if (mode === 'CREATE') {
      // 预填一份基于源字段的建表语句，用户可以直接修改后解析。
      if (!mappingForm.getFieldValue('createTableDdl') && srcCols.length > 0) {
        const srcTable = mappingForm.getFieldValue('sourceTable') || 'target_table';
        const cols = srcCols.map((c) => `  ${c.name} ${c.type.toUpperCase()}`).join(',\n');
        mappingForm.setFieldValue('createTableDdl', `CREATE TABLE ${srcTable}_backup (\n${cols}\n)`);
      }
      setTgtCols([]);
    } else if (targetTable) {
      loadColumns(form.getFieldValue('targetDatasourceId'), targetSchema, targetTable, 'tgt');
    } else {
      setTgtCols([]);
    }
  }

  async function applyParsedDdl(ddl: string, notify: boolean) {
    try {
      const parsed = await parseEtlDdl(ddl);
      setParsedDdl(parsed);
      setTgtCols(parsed.columns.map((c) => ({ name: c.name, type: c.platformType })));
      if (notify) Message.success(`已解析目标表 ${parsed.schema ? `${parsed.schema}.` : ''}${parsed.table}，共 ${parsed.columns.length} 个字段`);
      return true;
    } catch (e: any) {
      setParsedDdl(null);
      setTgtCols([]);
      Message.error(e.response?.data?.message || '建表语句解析失败');
      return false;
    }
  }

  async function resolveSelectColumns() {
    const sql = mappingForm.getFieldValue('selectSql');
    if (!sql) { Message.warning('请先输入 SELECT'); return; }
    try {
      // 命名参数仅为占位，解析字段时替换为 NULL 以通过 SQL 网关
      const probeSql = sql.replace(/(?<!:):([A-Za-z_][A-Za-z0-9_]*)/g, 'NULL');
      const result = await runSql(form.getFieldValue('sourceDatasourceId'), probeSql);
      const cols = (result.columns || []).map((name) => ({ name, type: 'text' }));
      setSrcCols(cols);
      Message.success(`已解析 ${cols.length} 个字段`);
    } catch (e: any) {
      Message.error(e.response?.data?.message || '字段解析失败');
    }
  }

  async function saveTask(extra?: Record<string, unknown>): Promise<boolean> {
    try {
      const values = await form.validate();
      setSaving(true);
      const payload = {
        ...values,
        onceAt: values.onceAt ? String(values.onceAt).replace(' ', 'T') : undefined,
        maxRetries: Number(values.maxRetries || 0),
        retryIntervalSeconds: Number(values.retryIntervalSeconds || 60),
        ...extra,
      };
      if (taskId) {
        const updated = await updateEtlTask(taskId, payload);
        setTask({ ...(task as EtlTask), ...payload, configVersion: updated.configVersion });
      } else {
        const created = await createEtlTask(payload);
        setTask({ ...created, ...payload, status: 'DRAFT' } as unknown as EtlTask);
      }
      return true;
    } catch (e: any) {
      if (!e?.errors) Message.error(e.response?.data?.message || '保存失败');
      return false;
    } finally {
      setSaving(false);
    }
  }

  async function nextStep() {
    if (step === 0) {
      setStep(1);
    } else if (step === 1) {
      if (!(await saveTask())) return;
      const values = form.getFieldsValue();
      loadSchemas(values.sourceDatasourceId, 'src');
      loadSchemas(values.targetDatasourceId, 'tgt');
      if (task?.hasMapping) {
        await restoreMapping(task);
      } else {
        mappingForm.resetFields();
        setPairs([]); setSrcCols([]); setTgtCols([]);
        setMapInputType('TABLE'); setMapWriteMode('APPEND'); setMapTargetMode('CREATE');
      }
      setStep(2);
    } else if (step === 2) {
      setStep(3);
    }
  }

  async function collectMapping(): Promise<Record<string, unknown> | null> {
    if (!taskId) return null;
    try {
      await mappingForm.validate();
    } catch {
      return null;
    }
    const values = mappingForm.getFieldsValue();
    if (pairs.length === 0) { Message.error('请至少连线一个字段映射'); return null; }
    if (values.inputType === 'TABLE' && !values.sourceTable) { Message.error('请选择源表'); return null; }
    if (values.targetTableMode === 'CREATE') {
      if (!values.createTableDdl?.trim()) { Message.error('请输入建表语句'); return null; }
      if (!parsedDdl) { Message.error('请先点击“解析建表语句”生成目标字段'); return null; }
    }
    if (values.targetTableMode === 'EXISTING' && !values.targetTable) { Message.error('请选择目标表'); return null; }
    if (values.writeMode === 'UPSERT' && !(values.upsertKeys || []).length) { Message.error('请选择更新插入键字段'); return null; }
    return {
      inputType: values.inputType,
      sourceSchema: values.sourceSchema,
      sourceTable: values.sourceTable,
      selectSql: values.selectSql,
      parametersJson: '{}',
      targetSchema: values.targetSchema,
      targetTable: values.targetTable,
      targetTableMode: values.targetTableMode,
      writeMode: values.writeMode,
      fieldMappingsJson: JSON.stringify(srcCols
        .filter((c) => pairs.some((p) => p.source === c.name))
        .map((c) => {
          const pair = pairs.find((p) => p.source === c.name)!;
          return { source: c.name, target: pair.target, type: c.type, enabled: true };
        })),
      upsertKeysJson: values.writeMode === 'UPSERT' ? JSON.stringify(values.upsertKeys || []) : '[]',
      createTableDdl: values.targetTableMode === 'CREATE' ? values.createTableDdl : undefined,
    };
  }

  async function finish() {
    const mapping = await collectMapping();
    if (!mapping) return;
    if (!(await saveTask(mapping))) return;
    setTask((prev) => (prev ? ({ ...prev, ...mapping, hasMapping: true } as EtlTask) : prev));
    nav(`/etl/${taskId}`);
  }

  const dsFilter = (input: string, option: any) =>
    String(option?.props?.children ?? '').toLowerCase().includes(input.toLowerCase());

  return (
    <div className="page-shell">
      <div className="page-header">
        <div className="page-header-main">
          <Typography.Title heading={4} style={{ margin: 0 }}>{editing ? '编辑数据集成任务' : '新建数据集成任务'}</Typography.Title>
          <Typography.Text type="secondary">按步骤完成任务配置，保存后再启用</Typography.Text>
        </div>
        <Button onClick={() => nav('/etl')}>返回列表</Button>
      </div>
      <section className="page-section">
        <Steps current={step + 1} size="small" style={{ marginBottom: 28 }}>
          <StepItem title="基本信息" />
          <StepItem title="源与目标" />
          <StepItem title="映射配置" />
          <StepItem title="调度与确认" />
        </Steps>
        <Form form={form} layout="vertical" initialValues={{ scheduleType: 'MANUAL', maxRetries: 0, retryIntervalSeconds: 60 }}>
          <div style={{ display: step === 0 ? 'block' : 'none' }}>
            <Form.Item field="name" label="任务名称" rules={[{ required: true, message: '请输入任务名称' }]}>
              <Input placeholder="例如：每日订单同步" />
            </Form.Item>
            <Form.Item field="description" label="描述">
              <Input.TextArea placeholder="说明同步用途和数据范围" />
            </Form.Item>
          </div>
          <div style={{ display: step === 1 ? 'block' : 'none' }}>
            <Form.Item field="sourceDatasourceId" label="源数据源" rules={[{ required: true, message: '请选择源数据源' }]}>
              <Select showSearch filterOption={dsFilter}
                onChange={(v) => { mappingForm.setFieldValue('sourceSchema', undefined); loadSchemas(v, 'src'); }}>
                {sources.map((s) => <Select.Option key={s.id} value={s.id}>{s.name} · {s.type}</Select.Option>)}
              </Select>
            </Form.Item>
            <Form.Item field="targetDatasourceId" label="目标数据源" rules={[{ required: true, message: '请选择目标数据源' }]}>
              <Select showSearch filterOption={dsFilter}
                onChange={(v) => { mappingForm.setFieldValue('targetSchema', undefined); loadSchemas(v, 'tgt'); }}>
                {sources.map((s) => <Select.Option key={s.id} value={s.id}>{s.name} · {s.type}</Select.Option>)}
              </Select>
            </Form.Item>
          </div>
          <div style={{ display: step === 3 ? 'block' : 'none' }}>
            <Form.Item field="scheduleType" label="调度方式">
              <Select onChange={(v) => setSchedType(v)}>
                <Select.Option value="MANUAL">手动</Select.Option>
                <Select.Option value="ONCE">单次</Select.Option>
                <Select.Option value="CRON">Cron</Select.Option>
              </Select>
            </Form.Item>
            {schedType === 'ONCE' && (
              <Form.Item field="onceAt" label="执行时间" rules={[{ required: true, message: '请选择执行时间' }]}>
                <DatePicker showTime style={{ width: 240 }} />
              </Form.Item>
            )}
            {schedType === 'CRON' && (
              <Form.Item field="cronExpression" label="Cron 表达式" rules={[{ required: true, message: '请输入 Cron 表达式' }]}>
                <Input placeholder="0 0 2 * * ?" />
              </Form.Item>
            )}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
              <Form.Item field="maxRetries" label="最大重试次数"><Input type="number" /></Form.Item>
              <Form.Item field="retryIntervalSeconds" label="重试间隔（秒）"><Input type="number" /></Form.Item>
            </div>
          </div>
          <Space>
            {step > 0 && <Button onClick={() => setStep(Math.max(0, step - 1))}>上一步</Button>}
            {step === 3
              ? <Button type="primary" loading={saving} onClick={finish}>保存并完成</Button>
              : <Button type="primary" loading={saving} onClick={nextStep}>下一步</Button>}
          </Space>
        </Form>
        <div style={{ marginTop: 24, display: step === 2 ? 'block' : 'none' }}>
            <Form form={mappingForm} layout="vertical" initialValues={{ inputType: 'TABLE', targetTableMode: 'CREATE', writeMode: 'APPEND' }}>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16 }}>
                <Form.Item field="inputType" label="输入类型">
                  <Select onChange={(v) => { setMapInputType(v); setPairs([]); setSrcCols([]); setTgtCols([]); }}>
                    <Select.Option value="TABLE">数据表</Select.Option>
                    <Select.Option value="SELECT">参数化 SELECT</Select.Option>
                  </Select>
                </Form.Item>
                <Form.Item field="writeMode" label="写入策略">
                  <Select onChange={(v) => setMapWriteMode(v)}>
                    <Select.Option value="OVERWRITE">覆盖</Select.Option>
                    <Select.Option value="APPEND">追加</Select.Option>
                    <Select.Option value="UPSERT">更新插入</Select.Option>
                  </Select>
                </Form.Item>
                <Form.Item field="targetTableMode" label="目标表策略">
                  <Select onChange={onTargetModeChange}>
                    <Select.Option value="CREATE">自动建表</Select.Option>
                    <Select.Option value="EXISTING">写入已有表</Select.Option>
                  </Select>
                </Form.Item>
                {mapWriteMode === 'UPSERT' && (
                  <Form.Item field="upsertKeys" label="更新插入键字段" rules={[{ required: true, message: '请选择键字段' }]}>
                    <Select mode="multiple" placeholder="选择目标字段作为键">
                      {tgtCols.map((c) => <Select.Option key={c.name} value={c.name}>{c.name}</Select.Option>)}
                    </Select>
                  </Form.Item>
                )}
              </div>
              {mapInputType === 'TABLE' ? (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16 }}>
                  <Form.Item field="sourceSchema" label="源 Schema">
                    <Select showSearch allowClear placeholder="选择 schema" onChange={onSourceSchemaChange}>
                      {srcSchemas.map((s) => <Select.Option key={s.name} value={s.name}>{s.name}</Select.Option>)}
                    </Select>
                  </Form.Item>
                    <Form.Item field="sourceTable" label="源表" rules={[{ required: true, message: '请选择源表' }]}>
                      <Select showSearch placeholder="选择源表" onChange={onSourceTableChange}>
                        {srcTables.map((s) => <Select.Option key={s.name} value={s.name}>{s.name}</Select.Option>)}
                      </Select>
                    </Form.Item>
                    {mapTargetMode === 'EXISTING' && (
                      <>
                        <Form.Item field="targetSchema" label="目标 Schema">
                          <Select showSearch allowClear placeholder="选择 schema" onChange={onTargetSchemaChange}>
                            {tgtSchemas.map((s) => <Select.Option key={s.name} value={s.name}>{s.name}</Select.Option>)}
                          </Select>
                        </Form.Item>
                        <Form.Item field="targetTable" label="目标表" rules={[{ required: true, message: '请选择或输入目标表' }]}>
                          <Select showSearch allowCreate placeholder="选择或输入目标表名" onChange={onTargetTableChange}>
                            {tgtTables.map((s) => <Select.Option key={s.name} value={s.name}>{s.name}</Select.Option>)}
                          </Select>
                        </Form.Item>
                      </>
                    )}
                  </div>
                ) : null}
              {mapInputType === 'TABLE' && mapTargetMode === 'CREATE' && (
                <>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: 16, alignItems: 'start' }}>
                    <Form.Item field="createTableDdl" label="建表语句" rules={[{ required: true, message: '请输入建表语句' }]}>
                      <Input.TextArea
                        autoSize={{ minRows: 4, maxRows: 10 }}
                        style={{ fontFamily: 'monospace', fontSize: 12 }}
                        placeholder={'CREATE TABLE orders_backup (\n  id BIGINT,\n  city TEXT,\n  amount NUMERIC\n)'}
                      />
                    </Form.Item>
                    <Button style={{ marginTop: 4 }} onClick={() => {
                      const ddl = mappingForm.getFieldValue('createTableDdl');
                      if (!ddl?.trim()) { Message.warning('请先输入建表语句'); return; }
                      applyParsedDdl(ddl, true);
                    }}>解析建表语句</Button>
                  </div>
                  {parsedDdl && (
                    <Space size={8} style={{ marginBottom: 12 }}>
                      <Typography.Text type="secondary">目标表</Typography.Text>
                      <Tag color="blue">{parsedDdl.schema ? `${parsedDdl.schema}.${parsedDdl.table}` : parsedDdl.table}</Tag>
                      <Typography.Text type="secondary">{parsedDdl.columns.length} 个字段</Typography.Text>
                    </Space>
                  )}
                </>
              )}
              {mapInputType !== 'TABLE' && (
                <div style={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: 16, alignItems: 'start' }}>
                  <Form.Item field="selectSql" label="只读 SELECT">
                    <Input.TextArea autoSize={{ minRows: 2, maxRows: 5 }} placeholder="SELECT id, name FROM orders WHERE city = :city" />
                  </Form.Item>
                  <Button style={{ marginTop: 4 }} onClick={resolveSelectColumns}>解析字段</Button>
                </div>
              )}
              {srcCols.length > 0 && (
                <div style={{ marginTop: 8 }}>
                  <FieldMappingPanel sourceCols={srcCols} targetCols={tgtCols} value={pairs} onChange={setPairs} />
                </div>
              )}
            </Form>
          </div>
      </section>
    </div>
  );
}
