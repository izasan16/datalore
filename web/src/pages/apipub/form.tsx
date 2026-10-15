import { useEffect, useMemo, useState } from 'react';
import {
  Button, Checkbox, Form, Grid, Input, InputNumber, Message, Radio, Select, Switch, Table, Typography,
} from '@arco-design/web-react';
import { ApiParamDef, ApiPubInfo, ApiPubPayload, ApiSourceType } from '../../api/apipub';
import { ColumnInfo, listColumns, listDatasources, listSchemas, listTables } from '../../api/datasource';

const { Row, Col } = Grid;
const CheckboxGroup = Checkbox.Group;

const PARAM_RE = /(?<!:):([A-Za-z_][A-Za-z0-9_]*)/g;

function extractParams(sql: string): string[] {
  const out: string[] = [];
  if (!sql) return out;
  for (const m of sql.matchAll(PARAM_RE)) {
    if (!out.includes(m[1])) out.push(m[1]);
  }
  return out;
}

function BoundedCheckboxGroup({
  value = [],
  onChange,
  options,
  onSelectAll,
  onClear,
}: {
  value?: string[];
  onChange?: (value: string[]) => void;
  options: { label: string; value: string }[];
  onSelectAll: () => void;
  onClear: () => void;
}) {
  return (
    <div>
      <div style={{ marginBottom: 8 }}>
        <Button type="text" size="mini" onClick={onSelectAll}>全选</Button>
        <Button type="text" size="mini" onClick={onClear}>清空</Button>
      </div>
      <div className="bounded-checks">
        <CheckboxGroup value={value} onChange={(nextValue) => onChange?.(nextValue as string[])} options={options} />
      </div>
    </div>
  );
}

export default function ApiPubForm({
  initial,
  submitText,
  saving,
  onSubmit,
}: {
  initial?: ApiPubInfo;
  submitText: string;
  saving: boolean;
  onSubmit: (payload: ApiPubPayload) => void;
}) {
  const [form] = Form.useForm();
  const [sourceType, setSourceType] = useState<ApiSourceType>(initial?.sourceType ?? 'TABLE');
  const [sqlText, setSqlText] = useState(initial?.sqlText ?? '');
  const [params, setParams] = useState<ApiParamDef[]>(initial?.params ?? []);

  const [dsList, setDsList] = useState<{ id: number; name: string; type: string }[]>([]);
  const [schemaOptions, setSchemaOptions] = useState<{ value: string; label: string }[]>([]);
  const [tableOptions, setTableOptions] = useState<{ value: string; label: string }[]>([]);
  const [cols, setCols] = useState<{ name: string; type: string }[]>([]);
  const [dsLoading, setDsLoading] = useState(false);
  const [dsError, setDsError] = useState(false);
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [tableLoading, setTableLoading] = useState(false);
  const [watchDs, setWatchDs] = useState<number | undefined>(initial?.datasourceId);

  const loadDatasources = async () => {
    setDsLoading(true);
    setDsError(false);
    try {
      const list = await listDatasources();
      setDsList(list.map((d) => ({ id: d.id, name: d.name, type: d.type })));
    } catch {
      setDsError(true);
    } finally {
      setDsLoading(false);
    }
  };

  useEffect(() => {
    loadDatasources();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 编辑模式：回填 schema/table 下拉与列结构
  useEffect(() => {
    if (!initial) return;
    if (initial.datasourceId) {
      listSchemas(initial.datasourceId)
        .then((list) => setSchemaOptions(list.map((s) => ({ value: s.name, label: s.name }))))
        .catch(() => setSchemaOptions([]));
      listTables(initial.datasourceId, initial.schemaName || undefined)
        .then((list) => setTableOptions(list.map((t) => ({ value: t.name, label: t.name }))))
        .catch(() => setTableOptions([]));
      if (sourceType === 'TABLE' && initial.tableName) {
        listColumns(initial.datasourceId, initial.tableName, initial.schemaName || undefined)
          .then((list: ColumnInfo[]) => setCols(list.map((x) => ({ name: x.name, type: x.type }))))
          .catch(() => setCols([]));
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // SQL 参数实时提取：保留同名参数的用户设置，新参数给默认配置
  useEffect(() => {
    if (sourceType !== 'SQL') return;
    const names = extractParams(sqlText);
    setParams((prev) => {
      const prevMap = new Map(prev.map((p) => [p.name, p]));
      return names.map((n) => prevMap.get(n) ?? { name: n, type: 'string', required: true, defaultValue: '' });
    });
  }, [sqlText, sourceType]);

  async function onDatasourceChange(id: number) {
    setWatchDs(id);
    setSchemaOptions([]);
    setTableOptions([]);
    form.setFieldValue('tableName', undefined);
    form.setFieldValue('columns', []);
    form.setFieldValue('filterColumns', []);
    setCols([]);
    if (!id) return;
    setSchemaLoading(true);
    try {
      setSchemaOptions((await listSchemas(id)).map((s) => ({ value: s.name, label: s.name })));
    } catch {
      setSchemaOptions([]);
    } finally {
      setSchemaLoading(false);
    }
  }

  async function onSchemaChange(schema?: string) {
    setTableOptions([]);
    form.setFieldValue('tableName', undefined);
    form.setFieldValue('columns', []);
    form.setFieldValue('filterColumns', []);
    setCols([]);
    if (!watchDs) return;
    setTableLoading(true);
    try {
      setTableOptions((await listTables(watchDs, schema)).map((t) => ({ value: t.name, label: t.name })));
    } catch {
      setTableOptions([]);
    } finally {
      setTableLoading(false);
    }
  }

  async function onTableChange(table: string) {
    form.setFieldValue('columns', []);
    form.setFieldValue('filterColumns', []);
    setCols([]);
    if (!watchDs || !table) return;
    try {
      const schema = form.getFieldValue('schemaName');
      const list: ColumnInfo[] = await listColumns(watchDs, table, schema || undefined);
      setCols(list.map((x) => ({ name: x.name, type: x.type })));
      form.setFieldValue('columns', list.map((x) => x.name));
    } catch (e: any) {
      setCols([]);
    }
  }

  const columnsValue: string[] = Form.useWatch('columns', form) || [];
  const filterOptions = useMemo(
    () => cols.filter((c) => columnsValue.includes(c.name)).map((c) => ({ label: c.name, value: c.name })),
    [cols, columnsValue],
  );

  function updateParam(index: number, patch: Partial<ApiParamDef>) {
    setParams((prev) => prev.map((p, i) => (i === index ? { ...p, ...patch } : p)));
  }

  async function handleSubmit(v: any) {
    if (sourceType === 'SQL') {
      if (!v.datasourceId) return;
      if (!sqlText.trim()) {
        Message.error('请输入查询 SQL');
        return;
      }
      onSubmit({
        name: v.name,
        path: v.path,
        sourceType: 'SQL',
        datasourceId: v.datasourceId,
        sqlText,
        params,
        maxRows: v.maxRows || undefined,
      });
    } else {
      if (!v.tableName) {
        Message.error('请选择要发布的表');
        return;
      }
      if (!columnsValue.length) {
        Message.error('至少选择一列对外暴露');
        return;
      }
      onSubmit({
        name: v.name,
        path: v.path,
        sourceType: 'TABLE',
        datasourceId: v.datasourceId,
        schemaName: v.schemaName || undefined,
        tableName: v.tableName,
        columns: columnsValue,
        filterColumns: (v.filterColumns || []) as string[],
        maxRows: v.maxRows || undefined,
      });
    }
  }

  const paramColumns = [
    { title: '参数名', dataIndex: 'name', width: 140 },
    {
      title: '类型', dataIndex: 'type', width: 120,
      render: (_: unknown, r: ApiParamDef, i: number) => (
        <Select value={r.type} size="mini" style={{ width: 100 }}
          onChange={(t) => updateParam(i, { type: t })}
          options={[{ value: 'string', label: '字符串' }, { value: 'number', label: '数字' }]} />
      ),
    },
    {
      title: '必填', dataIndex: 'required', width: 80,
      render: (_: unknown, r: ApiParamDef, i: number) => (
        <Switch size="small" checked={r.required} onChange={(c) => updateParam(i, { required: c as boolean })} />
      ),
    },
    {
      title: '默认值', dataIndex: 'defaultValue',
      render: (_: unknown, r: ApiParamDef, i: number) => (
        <Input size="mini" value={r.defaultValue} placeholder="未填则传 NULL"
          onChange={(val) => updateParam(i, { defaultValue: val })} />
      ),
    },
  ];

  return (
    <Form
      form={form}
      layout="vertical"
      onSubmit={handleSubmit}
      initialValues={{
        name: initial?.name,
        path: initial?.path,
        datasourceId: initial?.datasourceId,
        schemaName: initial?.schemaName || undefined,
        tableName: initial?.tableName,
        columns: initial?.columns ?? [],
        filterColumns: initial?.filterColumns ?? [],
        maxRows: initial?.maxRows ?? 1000,
      }}
    >
      <Typography.Title heading={6} style={{ margin: '0 0 16px' }}>基础信息</Typography.Title>
      <Row gutter={16}>
        <Col span={12}>
          <Form.Item label="API 名称" field="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如：城市数据查询" />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item
            label="路径（全平台唯一）"
            field="path"
            rules={[
              { required: true, message: '请输入路径' },
              { match: /^[a-z0-9][a-z0-9_-]{1,63}$/, message: '仅小写字母/数字/-/_，2-64 位且以字母数字开头' },
            ]}
          >
            <Input addBefore="/api/open/" placeholder="city-data" disabled={!!initial} />
          </Form.Item>
        </Col>
      </Row>

      <Form.Item label="数据来源">
        <Radio.Group
          value={sourceType}
          onChange={(v) => setSourceType(v as ApiSourceType)}
          options={[
            { value: 'TABLE', label: '数据表' },
            { value: 'SQL', label: 'SQL 查询' },
          ]}
        />
      </Form.Item>

      {dsError && (
        <div className="page-error" role="alert" style={{ marginBottom: 16 }}>
          <Typography.Text>数据源加载失败，请重试。</Typography.Text>
          <div style={{ marginTop: 10 }}>
            <Button size="mini" onClick={loadDatasources}>重试</Button>
          </div>
        </div>
      )}
      <Form.Item label="数据源" field="datasourceId" rules={[{ required: true, message: '请选择数据源' }]}>
        <Select
          placeholder="选择数据源"
          loading={dsLoading}
          onChange={onDatasourceChange}
          options={dsList.map((d) => ({ value: d.id, label: `${d.name}（${d.type}）` }))}
        />
      </Form.Item>

      {sourceType === 'SQL' ? (
        <>
          <Form.Item label="查询 SQL（只读 SELECT，参数用 :名称 占位）" required>
            <Input.TextArea
              value={sqlText}
              onChange={setSqlText}
              placeholder={'SELECT id, name\nFROM sales.customers\nWHERE city = :city'}
              autoSize={{ minRows: 6, maxRows: 14 }}
              style={{ fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace' }}
            />
          </Form.Item>
          <Form.Item label="调用参数">
            {params.length === 0 ? (
              <Typography.Text type="secondary">SQL 中没有检测到 :参数，调用方无需传参。</Typography.Text>
            ) : (
              <Table
                size="mini"
                columns={paramColumns as any}
                data={params as any}
                rowKey="name"
                pagination={false}
              />
            )}
          </Form.Item>
        </>
      ) : (
        <>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item label="Schema" field="schemaName" extra="不选则使用数据源默认 schema">
                <Select
                  placeholder="默认 schema"
                  loading={schemaLoading}
                  allowClear
                  showSearch
                  disabled={!watchDs}
                  onChange={onSchemaChange}
                  options={schemaOptions}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item label="数据表" field="tableName" rules={[{ required: true, message: '请选择表' }]}>
                <Select
                  placeholder="选择要发布的表"
                  loading={tableLoading}
                  showSearch
                  disabled={!watchDs}
                  onChange={onTableChange}
                  options={tableOptions}
                />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Title heading={6} style={{ margin: '24px 0 16px' }}>数据权限</Typography.Title>
          {cols.length > 0 ? (
            <>
              <Form.Item label="暴露列" field="columns" rules={[{ required: true, message: '至少选择一列' }]}>
                <BoundedCheckboxGroup
                  options={cols.map((c) => ({ label: `${c.name} (${c.type})`, value: c.name }))}
                  onSelectAll={() => form.setFieldValue('columns', cols.map((c) => c.name))}
                  onClear={() => form.setFieldValue('columns', [])}
                />
              </Form.Item>
              <Form.Item
                label="可过滤列（等值查询，须为暴露列）"
                field="filterColumns"
                extra="外部调用时 ?列名=值 过滤；不选则只支持分页全量查询"
              >
                <BoundedCheckboxGroup
                  options={filterOptions}
                  onSelectAll={() => form.setFieldValue('filterColumns', columnsValue)}
                  onClear={() => form.setFieldValue('filterColumns', [])}
                />
              </Form.Item>
            </>
          ) : (
            <Typography.Text type="secondary">选择数据表后可配置暴露列与过滤列。</Typography.Text>
          )}
        </>
      )}

      <Typography.Title heading={6} style={{ margin: '24px 0 16px' }}>调用限制</Typography.Title>
      <Row gutter={16}>
        <Col span={12}>
          <Form.Item label="单次最大行数" field="maxRows">
            <InputNumber min={1} max={10000} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item label=" ">
            <Button type="primary" htmlType="submit" loading={saving}>{submitText}</Button>
          </Form.Item>
        </Col>
      </Row>
    </Form>
  );
}
