import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button, Card, Message, Modal, Space, Spin, Table, Tag, Tree, Typography,
} from '@arco-design/web-react';
import { IconDelete, IconPlus, IconRefresh } from '@arco-design/web-react/icon';
import {
  ColumnInfo, DataSourceInfo, listColumns, listDatasources,
  listSchemas, listTables, previewTable, TablePreview,
} from '../../api/datasource';
import { ImportedTableInfo, deleteImported, listImported } from '../../api/imported';

/** 数据管理：左侧数据源→schema→表树，右侧列结构 + 数据预览（前 100 行）。 */
export default function DataManagePage() {
  const nav = useNavigate();
  const [dsList, setDsList] = useState<DataSourceInfo[]>([]);
  const [datasourceError, setDatasourceError] = useState(false);
  const [imported, setImported] = useState<ImportedTableInfo[]>([]);

  const [schemasByDs, setSchemasByDs] = useState<Record<number, { name: string }[]>>({});
  const [tablesBySchema, setTablesBySchema] = useState<Record<string, { name: string }[]>>({});
  const [loadingNode, setLoadingNode] = useState<string | null>(null);
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const [selected, setSelected] = useState<{ dsId: number; dsName: string; schema: string; table: string } | null>(null);

  const [columns, setColumns] = useState<ColumnInfo[]>([]);
  const [columnsLoading, setColumnsLoading] = useState(false);
  const [preview, setPreview] = useState<TablePreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);

  const loadDatasources = useCallback(async () => {
    try {
      setDsList(await listDatasources());
      setDatasourceError(false);
    } catch (e: any) {
      setDatasourceError(true);
      Message.error(e.response?.data?.message || '数据源加载失败');
    }
  }, []);

  const loadImported = useCallback(async () => {
    try {
      setImported(await listImported());
    } catch {
      // 导入记录加载失败只影响"导入"标记，不阻塞浏览
      setImported([]);
    }
  }, []);

  useEffect(() => {
    loadDatasources();
    loadImported();
  }, [loadDatasources, loadImported]);

  async function loadSchemasOf(ds: DataSourceInfo) {
    if (schemasByDs[ds.id]) return;
    setLoadingNode(`ds-${ds.id}`);
    try {
      const schemas = await listSchemas(ds.id);
      setSchemasByDs((prev) => ({ ...prev, [ds.id]: schemas }));
    } catch (e: any) {
      Message.error(e.response?.data?.message || `读取「${ds.name}」schema 失败`);
      setSchemasByDs((prev) => ({ ...prev, [ds.id]: [] }));
    } finally {
      setLoadingNode(null);
    }
  }

  async function loadTablesOf(dsId: number, schema: string) {
    const key = `sc-${dsId}-${schema}`;
    if (tablesBySchema[key]) return;
    setLoadingNode(key);
    try {
      const tables = await listTables(dsId, schema);
      setTablesBySchema((prev) => ({ ...prev, [key]: tables }));
    } catch (e: any) {
      Message.error(e.response?.data?.message || '读取表列表失败');
      setTablesBySchema((prev) => ({ ...prev, [key]: [] }));
    } finally {
      setLoadingNode(null);
    }
  }

  function onTreeSelect(keys: any) {
    const key: string = String(keys?.[0] || '');
    if (!key) return;
    if (key.startsWith('tb-')) {
      const rest = key.slice(3);
      const sep1 = rest.indexOf('-');
      const dsId = Number(rest.slice(0, sep1));
      const rest2 = rest.slice(sep1 + 1);
      const sep2 = rest2.indexOf('-');
      const schema = rest2.slice(0, sep2);
      const table = rest2.slice(sep2 + 1);
      const ds = dsList.find((d) => d.id === dsId);
      if (!ds) return;
      setSelected({ dsId, dsName: ds.name, schema, table });
    }
  }

  useEffect(() => {
    if (!selected) return;
    setColumns([]);
    setPreview(null);
    setColumnsLoading(true);
    setPreviewLoading(true);
    listColumns(selected.dsId, selected.table, selected.schema)
      .then(setColumns)
      .catch((e: any) => Message.error(e.response?.data?.message || '读取列信息失败'))
      .finally(() => setColumnsLoading(false));
    previewTable(selected.dsId, selected.table, selected.schema)
      .then(setPreview)
      .catch((e: any) => Message.error(e.response?.data?.message || '数据预览失败'))
      .finally(() => setPreviewLoading(false));
  }, [selected]);

  function isImported(dsId: number, schema: string, table: string) {
    return imported.some((r) => r.datasourceId === dsId && r.tableName === table
      && (!r.schemaName || r.schemaName === schema));
  }

  function importedRecord(dsId: number, table: string) {
    return imported.find((r) => r.datasourceId === dsId && r.tableName === table);
  }

  function onDeleteImported() {
    if (!selected) return;
    const rec = importedRecord(selected.dsId, selected.table);
    if (!rec) return;
    Modal.confirm({
      title: '删除导入表',
      content: `确定删除「${rec.name}」吗？数据库中的表 ${selected.schema}.${selected.table} 将一并删除，该操作不可恢复。`,
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        try {
          await deleteImported(rec.id);
          Message.success('已删除');
          setSelected(null);
          setTablesBySchema((prev) => {
            const key = `sc-${selected.dsId}-${selected.schema}`;
            return { ...prev, [key]: (prev[key] || []).filter((t) => t.name !== selected.table) };
          });
          loadImported();
        } catch (e: any) {
          Message.error(e.response?.data?.message || '删除失败');
        }
      },
    });
  }

  async function handleLoadMore(node: any) {
    const key = String(node?._key ?? node?.key ?? node?.props?._key ?? '');
    if (key.startsWith('ds-')) {
      const ds = dsList.find((d) => d.id === Number(key.slice(3)));
      if (ds) await loadSchemasOf(ds);
    } else if (key.startsWith('sc-')) {
      const rest = key.slice(3);
      const sep = rest.indexOf('-');
      const dsId = Number(rest.slice(0, sep));
      const schema = rest.slice(sep + 1);
      await loadTablesOf(dsId, schema);
    }
  }

  const treeData = dsList.map((ds) => ({
    key: `ds-${ds.id}`,
    title: (
      <Space size={6}>
        {ds.name}
        <Tag size="small">{ds.type}</Tag>
        {loadingNode === `ds-${ds.id}` && <Spin size={12} />}
      </Space>
    ),
    isLeaf: false,
    children: schemasByDs[ds.id] ? schemasByDs[ds.id].map((s) => {
      const scKey = `sc-${ds.id}-${s.name}`;
      return {
        key: scKey,
        title: (
          <Space size={6}>
            {s.name}
            {loadingNode === scKey && <Spin size={12} />}
          </Space>
        ),
        isLeaf: false,
        children: tablesBySchema[scKey] ? tablesBySchema[scKey].map((t) => ({
          key: `tb-${ds.id}-${s.name}-${t.name}`,
          title: (
            <Space size={6}>
              {t.name}
              {isImported(ds.id, s.name, t.name) && <Tag size="small" color="green">导入</Tag>}
            </Space>
          ),
          isLeaf: true,
        })) : undefined,
      };
    }) : undefined,
  }));

  const selectedImported = selected ? importedRecord(selected.dsId, selected.table) : undefined;

  return (
    <div className="page-shell">
      <div className="page-header">
        <div className="page-header-main">
          <Typography.Title heading={4} style={{ margin: 0 }}>数据管理</Typography.Title>
          <Typography.Text type="secondary">浏览数据源的库表结构与数据</Typography.Text>
        </div>
        <div className="page-header-actions">
          <Space>
            <Button icon={<IconRefresh />} onClick={() => { loadDatasources(); loadImported(); setSchemasByDs({}); setTablesBySchema({}); setSelected(null); }}>
              刷新
            </Button>
            <Button type="primary" icon={<IconPlus />} onClick={() => nav('/datamanage/new', { state: selected ? { datasourceId: selected.dsId, schemaName: selected.schema } : undefined })}>
              导入数据
            </Button>
          </Space>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 12, flex: 1, minHeight: 0 }}>
        <Card className="card-fill" style={{ width: 300, flexShrink: 0 }} bodyStyle={{ padding: 8, overflowY: 'auto' }}>
          {dsList.length > 0 ? (
            <Tree
              size="small"
              treeData={treeData as any}
              expandedKeys={expandedKeys}
              onExpand={(keys: any) => setExpandedKeys(keys.map(String))}
              selectedKeys={selected ? [`tb-${selected.dsId}-${selected.schema}-${selected.table}`] : []}
              loadMore={handleLoadMore}
              onSelect={onTreeSelect}
            />
          ) : datasourceError ? (
            <div className="page-error">
              <Typography.Text>数据源加载失败</Typography.Text>
              <div style={{ marginTop: 8 }}>
                <Button size="mini" type="text" onClick={loadDatasources}>重试</Button>
              </div>
            </div>
          ) : (
            <div className="page-empty" style={{ padding: 12 }}>
              <Typography.Text type="secondary">暂无数据源，先到「数据源」接入一个数据库</Typography.Text>
            </div>
          )}
        </Card>

        <Card
          className="card-fill"
          style={{ flex: 1, minWidth: 0 }}
          title={selected ? `${selected.dsName} · ${selected.schema}.${selected.table}` : '表详情'}
          extra={selectedImported && (
            <Button size="mini" status="danger" icon={<IconDelete />} onClick={onDeleteImported}>删除表</Button>
          )}
        >
          {!selected ? (
            <div className="page-empty" style={{ marginTop: 40 }}>
              <Typography.Text type="secondary">在左侧选择一张表查看结构与数据</Typography.Text>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, height: '100%', minHeight: 0 }}>
              <div>
                <Typography.Title heading={6} style={{ marginTop: 0 }}>列结构</Typography.Title>
                <Table
                  loading={columnsLoading}
                  data={columns}
                  rowKey="name"
                  size="mini"
                  pagination={false}
                  scroll={{ y: 180 }}
                  columns={[
                    { title: '列名', dataIndex: 'name' },
                    { title: '类型', dataIndex: 'type', width: 160 },
                  ]}
                />
              </div>
              <div style={{ flex: 1, minHeight: 0 }}>
                <Space style={{ marginBottom: 8 }}>
                  <Typography.Title heading={6} style={{ margin: 0 }}>数据预览</Typography.Title>
                  {preview && (
                    <Typography.Text type="secondary">
                      共 {preview.total.toLocaleString()} 行，显示前 {preview.rows.length} 行
                    </Typography.Text>
                  )}
                </Space>
                <Table
                  loading={previewLoading}
                  data={(preview?.rows || []).map((row, i) => ({ __i: i, row }))}
                  rowKey="__i"
                  size="mini"
                  pagination={false}
                  scroll={{ x: '100%', y: 260 }}
                  columns={(preview?.columns || []).map((c, idx) => ({
                    title: `${c.name} (${c.type})`,
                    ellipsis: true,
                    width: 150,
                    render: (_: unknown, record: { row: string[] }) => record.row[idx] ?? '',
                  }))}
                />
              </div>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
