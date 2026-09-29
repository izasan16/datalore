import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button, Card, Checkbox, Form, Grid, Input, InputNumber, Message, Select, Space, Typography,
} from '@arco-design/web-react';
import { ApiPubInfo, ColumnDef, createApi, datasetColumns } from '../../api/apipub';
import { DatasetInfo, listDatasets } from '../../api/dataset';

const { Row, Col } = Grid;
const CheckboxGroup = Checkbox.Group;

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
      <Space size="mini" style={{ marginBottom: 8 }}>
        <Button type="text" size="mini" onClick={onSelectAll}>全选</Button>
        <Button type="text" size="mini" onClick={onClear}>清空</Button>
      </Space>
      <div className="bounded-checks">
        <CheckboxGroup value={value} onChange={(nextValue) => onChange?.(nextValue as string[])} options={options} />
      </div>
    </div>
  );
}

export default function ApiPubNewPage() {
  const nav = useNavigate();
  const [form] = Form.useForm();
  const [datasets, setDatasets] = useState<DatasetInfo[]>([]);
  const [cols, setCols] = useState<ColumnDef[]>([]);
  const [saving, setSaving] = useState(false);
  const [datasetsLoading, setDatasetsLoading] = useState(false);
  const [datasetsError, setDatasetsError] = useState(false);

  const loadDatasets = useCallback(async () => {
    setDatasetsLoading(true);
    setDatasetsError(false);
    try {
      setDatasets(await listDatasets());
    } catch {
      setDatasetsError(true);
    } finally {
      setDatasetsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadDatasets();
  }, [loadDatasets]);

  async function onDatasetChange(id: number) {
    form.setFieldValue('columns', []);
    form.setFieldValue('filterColumns', []);
    setCols([]);
    try {
      const c = await datasetColumns(id);
      setCols(c);
      // 默认全部暴露
      form.setFieldValue('columns', c.map((x) => x.name));
    } catch (e: any) {
      Message.error(e.response?.data?.message || '读取列失败');
      setCols([]);
    }
  }

  function updateColumns(value: string[]) {
    form.setFieldValue('columns', value);
  }

  function updateFilterColumns(value: string[]) {
    form.setFieldValue('filterColumns', value.filter((name) => columnsValue.includes(name)));
  }

  async function onSubmit(v: any) {
    setSaving(true);
    try {
      const api: ApiPubInfo = await createApi({
        name: v.name,
        path: v.path,
        datasetId: v.datasetId,
        columns: v.columns,
        filterColumns: v.filterColumns || [],
        maxRows: v.maxRows || undefined,
      });
      Message.success('发布成功');
      nav(`/apipub/${api.id}`);
    } catch (e: any) {
      Message.error(e.response?.data?.message || '发布失败');
    } finally {
      setSaving(false);
    }
  }

  const columnsValue: string[] = Form.useWatch('columns', form) || [];
  const filterColumnsValue: string[] = Form.useWatch('filterColumns', form) || [];
  const columnNames = cols.map((c) => c.name);
  const filterOptions = cols
    .filter((c) => columnsValue.includes(c.name))
    .map((c) => ({ label: c.name, value: c.name }));

  useEffect(() => {
    const nextFilterColumns = filterColumnsValue.filter((name) => columnsValue.includes(name));
    if (nextFilterColumns.length !== filterColumnsValue.length) {
      form.setFieldValue('filterColumns', nextFilterColumns);
    }
  }, [columnsValue, filterColumnsValue, form]);

  return (
    <Card
      title="新建 API 发布"
      style={{ maxWidth: 760, margin: '0 auto' }}
      extra={<Button type="text" onClick={() => nav('/apipub')}>返回列表</Button>}
    >
      <Form form={form} layout="vertical" onSubmit={onSubmit} initialValues={{ maxRows: 1000 }}>
        <Typography.Title heading={6} style={{ margin: '0 0 16px' }}>
          基础信息
        </Typography.Title>
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
              <Input addBefore="/api/open/" placeholder="city-data" />
            </Form.Item>
          </Col>
        </Row>
        {datasetsError && (
          <div className="page-error" role="alert" style={{ marginBottom: 16 }}>
            <Typography.Text>数据集加载失败，请重试。</Typography.Text>
            <div style={{ marginTop: 10 }}>
              <Button size="mini" onClick={loadDatasets}>重试</Button>
            </div>
          </div>
        )}
        <Form.Item label="数据集" field="datasetId" rules={[{ required: true, message: '请选择数据集' }]}>
          <Select
            placeholder="选择要发布的数据集（表）"
            loading={datasetsLoading}
            disabled={datasetsLoading}
            onChange={onDatasetChange}
            options={datasets.map((d) => ({ value: d.id, label: `${d.name}（${d.rowCount} 行）` }))}
          />
        </Form.Item>

        <Typography.Title heading={6} style={{ margin: '24px 0 16px' }}>
          数据权限
        </Typography.Title>
        {cols.length > 0 && (
          <>
            <Form.Item label="暴露列" field="columns" rules={[{ required: true, message: '至少选择一列' }]}>
              <BoundedCheckboxGroup
                options={cols.map((c) => ({ label: `${c.name} (${c.type})`, value: c.name }))}
                onSelectAll={() => updateColumns(columnNames)}
                onClear={() => updateColumns([])}
              />
            </Form.Item>
            <Form.Item
              label="可过滤列（等值查询，须为暴露列）"
              field="filterColumns"
              extra="外部调用时 ?列名=值 过滤；不选则只支持分页全量查询"
            >
              <BoundedCheckboxGroup
                options={filterOptions}
                onSelectAll={() => updateFilterColumns(columnsValue)}
                onClear={() => updateFilterColumns([])}
              />
            </Form.Item>
          </>
        )}

        <Row gutter={16} align="center" style={{ marginTop: 24 }}>
          <Col span={12}>
            <Typography.Title heading={6} style={{ margin: '0 0 16px' }}>
              调用限制
            </Typography.Title>
          </Col>
          <Col span={12}>
            <Form.Item label="单次最大行数" field="maxRows">
              <InputNumber min={1} max={10000} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>
        <Space style={{ marginTop: 8 }}>
          <Button type="primary" htmlType="submit" loading={saving}>发布</Button>
        </Space>
        <Typography.Text type="secondary" style={{ display: 'block', marginTop: 12, fontSize: 12 }}>
          发布后需在「应用管理」创建调用方应用，并在详情页勾选授权，才能实际调用。
        </Typography.Text>
      </Form>
    </Card>
  );
}
