import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button, Checkbox, Input, Message, Modal, Select, Space, Spin, Table, Tabs, Tag, Tree, Typography,
} from '@arco-design/web-react';
import { IconDelete, IconDownload, IconPlus, IconRefresh, IconSearch } from '@arco-design/web-react/icon';
import {
  alterColumn, ColumnInfo, DataSourceInfo, deleteRows, exportRows, FILTER_OPS, insertRow,
  listColumns, listDatasources, listRows, listSchemas, listTables,
  STD_COLUMN_TYPES, TableFilter, TableRowsResult,
} from '../../api/datasource';
import { ImportedTableInfo, deleteImported, listImported } from '../../api/imported';

type RowRec = { __rid: string; row: string[] };

/** 数据管理：左侧数据源→schema→表树，右侧 Tabs（数据 CRUD / 字段 DDL）。 */
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

  const [activeTab, setActiveTab] = useState('data');
  const [columns, setColumns] = useState<ColumnInfo[]>([]);
  const [columnsLoading, setColumnsLoading] = useState(false);
  const [rowsResult, setRowsResult] = useState<TableRowsResult | null>(null);
  const [rowsLoading, setRowsLoading] = useState(false);
  const [pageNo, setPageNo] = useState(1);
  const [pageSize] = useState(50);
  const [filters, setFilters] = useState<TableFilter[]>([]);
  const [appliedFilters, setAppliedFilters] = useState<TableFilter[]>([]);
  const [sort, setSort] = useState<{ orderBy?: string; orderDir?: 'asc' | 'desc' }>({});
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);

  const [insertVisible, setInsertVisible] = useState(false);
  const [insertValues, setInsertValues] = useState<Record<string, string>>({});
  const [addFieldVisible, setAddFieldVisible] = useState(false);
  const [addFieldName, setAddFieldName] = useState('');
  const [addFieldType, setAddFieldType] = useState('text');
  const [renameTarget, setRenameTarget] = useState<ColumnInfo | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [typeTarget, setTypeTarget] = useState<ColumnInfo | null>(null);
  const [typeValue, setTypeValue] = useState('text');
  const [dropTarget, setDropTarget] = useState<ColumnInfo | null>(null);
  const [dropAck, setDropAck] = useState(false);

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
      setPageNo(1);
      setFilters([]);
      setAppliedFilters([]);
      setSort({});
      setSelectedRowKeys([]);
      setActiveTab('data');
    }
  }

  const loadColumns = useCallback(async () => {
    if (!selected) return;
    setColumnsLoading(true);
    try {
      setColumns(await listColumns(selected.dsId, selected.table, selected.schema));
    } catch (e: any) {
      Message.error(e.response?.data?.message || '读取列信息失败');
      setColumns([]);
    } finally {
      setColumnsLoading(false);
    }
  }, [selected]);

  const loadRows = useCallback(async () => {
    if (!selected) return;
    setRowsLoading(true);
    try {
      const res = await listRows(selected.dsId, {
        schema: selected.schema, table: selected.table,
        page: pageNo, pageSize, filters: appliedFilters,
        orderBy: sort.orderBy, orderDir: sort.orderDir,
      });
      setRowsResult(res);
      setSelectedRowKeys([]);
    } catch (e: any) {
      Message.error(e.response?.data?.message || '读取表数据失败');
      setRowsResult(null);
    } finally {
      setRowsLoading(false);
    }
  }, [selected, pageNo, pageSize, appliedFilters, sort]);

  useEffect(() => {
    if (!selected) {
      setColumns([]);
      setRowsResult(null);
      return;
    }
    loadColumns();
    loadRows();
  }, [selected, loadColumns, loadRows]);

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

  async function handleInsertRow() {
    if (!selected) return;
    try {
      await insertRow(selected.dsId, selected.schema, selected.table, insertValues);
      Message.success('已新增 1 行');
      setInsertVisible(false);
      setInsertValues({});
      loadRows();
    } catch (e: any) {
      Message.error(e.response?.data?.message || '新增失败');
    }
  }

  async function handleDeleteRows() {
    if (!selected || selectedRowKeys.length === 0) return;
    Modal.confirm({
      title: '删除选中行',
      content: `确定删除选中的 ${selectedRowKeys.length} 行数据吗？该操作不可恢复。`,
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        try {
          await deleteRows(selected.dsId, selected.schema, selected.table, selectedRowKeys);
          Message.success('已删除');
          loadRows();
        } catch (e: any) {
          Message.error(e.response?.data?.message || '删除失败');
        }
      },
    });
  }

  async function handleExport() {
    if (!selected) return;
    try {
      const res = await exportRows(selected.dsId, selected.schema, selected.table, appliedFilters);
      const esc = (v: string) => `"${(v ?? '').replace(/"/g, '""')}"`;
      const lines = [
        res.columns.map((c) => esc(c.name)).join(','),
        ...res.rows.map((row) => row.map(esc).join(',')),
      ];
      const blob = new Blob(['\ufeff' + lines.join('\n')], { type: 'text/csv;charset=utf-8' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = `${selected.table}.csv`;
      a.click();
      URL.revokeObjectURL(a.href);
      if (res.rows.length >= 10000) {
        Message.warning('已导出前 10000 行，如需完整导出请使用 SQL 分析');
      } else {
        Message.success(`已导出 ${res.rows.length} 行`);
      }
    } catch (e: any) {
      Message.error(e.response?.data?.message || '导出失败');
    }
  }

  async function runColumnDdl(action: 'addColumn' | 'renameColumn' | 'alterType' | 'dropColumn',
                             column: string, payload: { newColumn?: string; type?: string }) {
    if (!selected) return;
    try {
      await alterColumn(selected.dsId, selected.schema, selected.table, action, { column, ...payload });
      Message.success('字段已更新');
      loadColumns();
      loadRows();
    } catch (e: any) {
      Message.error(e.response?.data?.message || '字段修改失败');
    }
  }

  function addFilter() {
    const firstCol = columns[0]?.name || '';
    setFilters((prev) => [...prev, { col: firstCol, op: 'eq', value: '' }]);
  }

  function updateFilter(idx: number, patch: Partial<TableFilter>) {
    setFilters((prev) => prev.map((f, i) => (i === idx ? { ...f, ...patch } : f)));
  }

  function removeFilter(idx: number) {
    setFilters((prev) => prev.filter((_, i) => i !== idx));
  }

  function onTableChange(pag: any, sorter: any, _filters: any, extra: any) {
    if (extra?.action === 'paginate') {
      setPageNo(pag.current ?? 1);
    } else if (extra?.action === 'sort') {
      const s = Array.isArray(sorter) ? sorter[0] : sorter;
      if (s?.order) setSort({ orderBy: s.field as string, orderDir: s.order === 'descend' ? 'desc' : 'asc' });
      else setSort({});
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

  const dataColumns = (rowsResult?.columns || []).map((c) => ({
    title: c.name,
    dataIndex: c.name,
    sorter: () => 0,
    sortOrder: sort.orderBy === c.name
      ? (sort.orderDir === 'desc' ? 'descend' : 'ascend') as 'ascend' | 'descend' | undefined
      : undefined,
    ellipsis: true,
    width: 150,
    render: (_: unknown, record: RowRec) => {
      const idx = (rowsResult?.columns || []).findIndex((x) => x.name === c.name);
      return record.row[idx] ?? '';
    },
  }));

  const tableRows: RowRec[] = (rowsResult?.rows || []).map((row, i) => ({
    __rid: rowsResult?.rowIds?.[i] ?? `__i${i}`,
    row,
  }));

  const colOptions = columns.map((c) => ({ label: c.name, value: c.name }));

  return (
    <div className="page-fill" style={{ flexDirection: 'column', gap: 16 }}>
      <div className="page-header">
        <div className="page-header-main">
          <Typography.Title heading={4} style={{ margin: 0 }}>数据管理</Typography.Title>
          <Typography.Text type="secondary">浏览数据源的库表结构，管理表数据与字段</Typography.Text>
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
        <div
          className="card-fill"
          style={{ width: 300, flexShrink: 0, overflowY: 'auto', padding: 8, background: 'var(--color-bg-2)', borderRadius: 4 }}
        >
          {dsList.length > 0 ? (
            <Tree
              size="small"
              treeData={treeData as any}
              expandedKeys={expandedKeys}
              onExpand={(keys: any) => setExpandedKeys(keys.map(String))}
              loadMore={handleLoadMore}
              onSelect={onTreeSelect}
              selectedKeys={selected ? [`tb-${selected.dsId}-${selected.schema}-${selected.table}`] : []}
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
        </div>

        <div
          className="card-fill"
          style={{ flex: 1, minWidth: 0, background: 'var(--color-bg-2)', borderRadius: 4, padding: '12px 16px', display: 'flex', flexDirection: 'column' }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
            <Typography.Title heading={6} style={{ margin: 0 }}>
              {selected ? `${selected.dsName} · ${selected.schema}.${selected.table}` : '表详情'}
            </Typography.Title>
            {selectedImported && (
              <Button size="mini" status="danger" icon={<IconDelete />} onClick={onDeleteImported}>删除表</Button>
            )}
          </div>

          {!selected ? (
            <div className="page-empty" style={{ marginTop: 40 }}>
              <Typography.Text type="secondary">在左侧选择一张表，查看数据并进行增删、筛选与字段管理</Typography.Text>
            </div>
          ) : (
            <Tabs activeTab={activeTab} onChange={setActiveTab} size="small" style={{ flex: 1, minHeight: 0 }}>
              <Tabs.TabPane key="data" title="数据">
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8, height: '100%', minHeight: 0 }}>
                  <Space wrap size={8}>
                    <Button size="mini" type="primary" icon={<IconPlus />} onClick={() => { setInsertValues({}); setInsertVisible(true); }}>
                      新增行
                    </Button>
                    <Button size="mini" status="danger" icon={<IconDelete />} disabled={!rowsResult?.deletable || selectedRowKeys.length === 0} onClick={handleDeleteRows}>
                      删除选中{selectedRowKeys.length > 0 ? `（${selectedRowKeys.length}）` : ''}
                    </Button>
                    <Button size="mini" icon={<IconDownload />} onClick={handleExport}>导出 CSV</Button>
                    {rowsResult && !rowsResult.deletable && (
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>{rowsResult.deleteReason}</Typography.Text>
                    )}
                  </Space>

                  <div style={{ border: '1px solid var(--color-border-2)', borderRadius: 4, padding: 8, display: 'flex', flexDirection: 'column', gap: 6 }}>
                    {filters.map((f, idx) => (
                      <Space key={idx} size={6}>
                        <Select size="mini" value={f.col} options={colOptions} style={{ width: 140 }} onChange={(v: string) => updateFilter(idx, { col: v })} />
                        <Select size="mini" value={f.op} options={FILTER_OPS as any} style={{ width: 110 }} onChange={(v: any) => updateFilter(idx, { op: v })} />
                        {f.op !== 'isnull' && f.op !== 'notnull' && (
                          <Input size="mini" value={f.value} style={{ width: 150 }} placeholder="值" onChange={(v: string) => updateFilter(idx, { value: v })} />
                        )}
                        <Button size="mini" icon={<IconDelete />} onClick={() => removeFilter(idx)} />
                      </Space>
                    ))}
                    <Space size={6}>
                      <Button size="mini" icon={<IconPlus />} onClick={addFilter}>添加条件</Button>
                      <Button size="mini" type="primary" icon={<IconSearch />} onClick={() => { setPageNo(1); setAppliedFilters(filters); }}>查询</Button>
                      <Button size="mini" onClick={() => { setFilters([]); setAppliedFilters([]); setPageNo(1); }}>重置</Button>
                    </Space>
                  </div>

                  <Table
                    loading={rowsLoading}
                    data={tableRows}
                    rowKey="__rid"
                    size="mini"
                    border={{ cell: true }}
                    scroll={{ x: '100%' }}
                    rowSelection={{
                      type: 'checkbox',
                      selectedRowKeys,
                      onChange: (keys: any) => setSelectedRowKeys(keys.map(String)),
                    }}
                    pagination={{
                      total: rowsResult?.total ?? 0,
                      pageSize,
                      current: pageNo,
                      showTotal: true,
                      size: 'mini',
                      onChange: (p: number) => setPageNo(p),
                    }}
                    onChange={onTableChange as any}
                    columns={dataColumns as any}
                  />
                </div>
              </Tabs.TabPane>

              <Tabs.TabPane key="fields" title="字段">
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  <Space>
                    <Button size="mini" type="primary" icon={<IconPlus />} onClick={() => { setAddFieldName(''); setAddFieldType('text'); setAddFieldVisible(true); }}>
                      新增字段
                    </Button>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>改类型 / 删除字段会立即修改数据库表结构，请谨慎操作</Typography.Text>
                  </Space>
                  <Table
                    loading={columnsLoading}
                    data={columns}
                    rowKey="name"
                    size="mini"
                    pagination={false}
                    columns={[
                      { title: '列名', dataIndex: 'name' },
                      { title: '类型', dataIndex: 'type', width: 200 },
                      { title: '可空', dataIndex: 'nullable', width: 80, render: (v: string) => (v === 'YES' ? '是' : '否') },
                      {
                        title: '操作', width: 220,
                        render: (_: unknown, c: ColumnInfo) => (
                          <Space size={4}>
                            <Button size="mini" onClick={() => { setRenameTarget(c); setRenameValue(c.name); }}>改名</Button>
                            <Button size="mini" onClick={() => { setTypeTarget(c); setTypeValue('text'); }}>改类型</Button>
                            <Button size="mini" status="danger" onClick={() => { setDropTarget(c); setDropAck(false); }}>删除</Button>
                          </Space>
                        ),
                      },
                    ] as any}
                  />
                </div>
              </Tabs.TabPane>
            </Tabs>
          )}
        </div>
      </div>

      <Modal
        title="新增行"
        visible={insertVisible}
        onOk={handleInsertRow}
        onCancel={() => setInsertVisible(false)}
        okText="保存"
        unmountOnExit
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {(rowsResult?.columns || []).map((c) => (
            <div key={c.name} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Typography.Text style={{ width: 160, flexShrink: 0 }} ellipsis>{c.name}（{c.type}）</Typography.Text>
              <Input
                size="small"
                value={insertValues[c.name] ?? ''}
                placeholder="留空则不填该列"
                onChange={(v: string) => setInsertValues((prev) => ({ ...prev, [c.name]: v }))}
              />
            </div>
          ))}
        </div>
      </Modal>

      <Modal
        title="新增字段"
        visible={addFieldVisible}
        onOk={async () => {
          if (!addFieldName.trim()) { Message.error('请输入字段名'); return; }
          await runColumnDdl('addColumn', addFieldName.trim(), { type: addFieldType });
          setAddFieldVisible(false);
        }}
        onCancel={() => setAddFieldVisible(false)}
        okText="创建"
        unmountOnExit
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Typography.Text style={{ width: 64 }}>字段名</Typography.Text>
            <Input value={addFieldName} placeholder="仅字母、数字、下划线" onChange={setAddFieldName} />
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Typography.Text style={{ width: 64 }}>类型</Typography.Text>
            <Select value={addFieldType} options={STD_COLUMN_TYPES as any} style={{ width: 200 }} onChange={(v: string) => setAddFieldType(v)} />
          </div>
        </div>
      </Modal>

      <Modal
        title={`改名：${renameTarget?.name ?? ''}`}
        visible={!!renameTarget}
        onOk={async () => {
          if (!renameValue.trim()) { Message.error('请输入新字段名'); return; }
          await runColumnDdl('renameColumn', renameTarget!.name, { newColumn: renameValue.trim() });
          setRenameTarget(null);
        }}
        onCancel={() => setRenameTarget(null)}
        okText="保存"
        unmountOnExit
      >
        <Input value={renameValue} placeholder="新字段名" onChange={setRenameValue} />
      </Modal>

      <Modal
        title={`改类型：${typeTarget?.name ?? ''}`}
        visible={!!typeTarget}
        onOk={async () => {
          await runColumnDdl('alterType', typeTarget!.name, { type: typeValue });
          setTypeTarget(null);
        }}
        onCancel={() => setTypeTarget(null)}
        okText="确认修改"
        unmountOnExit
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <Typography.Text type="secondary">当前类型：{typeTarget?.type}</Typography.Text>
          <Select value={typeValue} options={STD_COLUMN_TYPES as any} style={{ width: 200 }} onChange={(v: string) => setTypeValue(v)} />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>类型转换可能导致数据截断或失败，以数据库返回结果为准</Typography.Text>
        </div>
      </Modal>

      <Modal
        title="删除字段"
        visible={!!dropTarget}
        okText="删除"
        okButtonProps={{ status: 'danger', disabled: !dropAck }}
        onOk={async () => {
          await runColumnDdl('dropColumn', dropTarget!.name, {});
          setDropTarget(null);
        }}
        onCancel={() => setDropTarget(null)}
        unmountOnExit
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <Typography.Text>确定删除字段「{dropTarget?.name}」吗？该列数据将全部丢失且不可恢复。</Typography.Text>
          <Checkbox checked={dropAck} onChange={(v: boolean) => setDropAck(v)}>我已知晓该列数据将丢失</Checkbox>
        </div>
      </Modal>
    </div>
  );
}
