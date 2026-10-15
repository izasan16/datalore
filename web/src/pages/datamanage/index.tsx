import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button, Checkbox, Input, InputNumber, Message, Modal, Select, Space, Spin, Switch, Table, Tabs, Tag, Tooltip, Tree, Typography,
} from '@arco-design/web-react';
import { IconDelete, IconDownload, IconPlus, IconRefresh, IconSearch } from '@arco-design/web-react/icon';
import {
  alterColumn, batchColumns, ColumnChange, ColumnInfo, columnTypes, ColumnTypeDef, DataSourceInfo,
  deleteRows, exportRows, FILTER_OPS, insertRow,
  listColumns, listDatasources, listRows, listSchemas, listTables,
  TableFilter, TableRef, TableRowsResult, tableRefs, deleteTable,
} from '../../api/datasource';
import { ImportedTableInfo, listImported } from '../../api/imported';

type RowRec = { __rid: string; row: string[] };

type FieldEditRow = {
  key: string;
  op: 'add' | 'update';
  column?: string;
  originalType?: string;
  name: string;
  base: string;
  length?: number;
  precision?: number;
  scale?: number;
  nullable: boolean;
  defaultValue: string;
  origDefault: string;
  comment: string;
};

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
  const [fieldEditMode, setFieldEditMode] = useState(false);
  const [editRows, setEditRows] = useState<FieldEditRow[]>([]);
  const [catalog, setCatalog] = useState<ColumnTypeDef[]>([]);
  const [selectedField, setSelectedField] = useState<string | null>(null);
  const [sqlStatements, setSqlStatements] = useState<string[] | null>(null);
  const [pendingChanges, setPendingChanges] = useState<ColumnChange[] | null>(null);
  const [executing, setExecuting] = useState(false);
  const [fieldKeySeq, setFieldKeySeq] = useState(1000);
  const [dropTarget, setDropTarget] = useState<ColumnInfo | null>(null);
  const [dropAck, setDropAck] = useState(false);
  const [deleteRefs, setDeleteRefs] = useState<TableRef[] | null>(null);
  const [deleteAck, setDeleteAck] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);

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
      setFieldEditMode(false);
      setSelectedField(null);
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

  function afterTableDeleted() {
    if (!selected) return;
    Message.success('已删除');
    setSelected(null);
    setTablesBySchema((prev) => {
      const key = `sc-${selected.dsId}-${selected.schema}`;
      return { ...prev, [key]: (prev[key] || []).filter((t) => t.name !== selected.table) };
    });
    loadImported();
  }

  async function onDeleteTable() {
    if (!selected) return;
    try {
      const refs = await tableRefs(selected.dsId, selected.schema, selected.table);
      if (refs.length === 0) {
        Modal.confirm({
          title: '删除表',
          content: `确定删除 ${selected.schema}.${selected.table} 吗？该操作不可恢复。`,
          okButtonProps: { status: 'danger' },
          onOk: async () => {
            await deleteTable(selected.dsId, selected.schema, selected.table, false);
            afterTableDeleted();
          },
        });
      } else {
        setDeleteRefs(refs);
        setDeleteAck(false);
      }
    } catch (e: any) {
      Message.error(e.response?.data?.message || '引用检查失败');
    }
  }

  async function forceDeleteTable() {
    if (!selected) return;
    setDeleteLoading(true);
    try {
      await deleteTable(selected.dsId, selected.schema, selected.table, true);
      setDeleteRefs(null);
      afterTableDeleted();
    } catch (e: any) {
      Message.error(e.response?.data?.message || '删除失败');
    } finally {
      setDeleteLoading(false);
    }
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

  // ---------- 字段整表编辑 ----------

  function dsType(): string {
    return dsList.find((d) => d.id === selected?.dsId)?.type || '';
  }

  function toEditRow(c: ColumnInfo, key: string, cat: ColumnTypeDef[]): FieldEditRow {
    const typeLower = (c.type || '').toLowerCase();
    let base = '__keep__';
    let length: number | undefined;
    let precision: number | undefined;
    let scale: number | undefined;
    const def = cat.find((d) => d.aliases.some((a) => a.toLowerCase() === typeLower)
      && (d.param === 'NONE'
        || (d.param === 'LENGTH' ? Number(c.charLength) > 0 : Number(c.numericPrecision) > 0)));
    if (def) {
      base = def.base;
      if (def.param === 'LENGTH') length = Number(c.charLength);
      if (def.param === 'PRECISION') {
        precision = Number(c.numericPrecision);
        scale = Number(c.numericScale);
      }
    }
    return {
      key, op: 'update', column: c.name, originalType: c.type, name: c.name, base,
      length, precision, scale,
      nullable: c.nullable !== 'NO',
      defaultValue: c.default || '', origDefault: c.default || '', comment: c.comment || '',
    };
  }

  async function enterFieldEdit() {
    if (!selected) return;
    let cat: ColumnTypeDef[];
    try {
      cat = await columnTypes(dsType());
      setCatalog(cat);
    } catch {
      Message.error('类型目录加载失败');
      return;
    }
    setEditRows(columns.map((c, i) => toEditRow(c, String(i), cat)));
    setFieldEditMode(true);
  }

  function exitFieldEdit() {
    setFieldEditMode(false);
    setEditRows([]);
    setPendingChanges(null);
    setSqlStatements(null);
  }

  function updateEditRow(key: string, patch: Partial<FieldEditRow>) {
    setEditRows((prev) => prev.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  }

  function addEditRow() {
    const key = 'new' + fieldKeySeq;
    setFieldKeySeq(fieldKeySeq + 1);
    setEditRows((prev) => [...prev, {
      key, op: 'add', name: '', base: catalog[0]?.base || '__keep__',
      nullable: true, defaultValue: '', origDefault: '', comment: '',
    }]);
  }

  function removeEditRow(key: string) {
    setEditRows((prev) => prev.filter((r) => r.key !== key));
  }

  const FIELD_IDENT_RE = /^[\p{L}_][\p{L}\p{N}_]{0,62}$/u;

  async function previewFieldChanges() {
    if (!selected) return;
    const names = editRows.map((r) => r.name.trim());
    if (names.some((n) => !n)) { Message.error('列名不能为空'); return; }
    if (names.some((n) => !FIELD_IDENT_RE.test(n))) {
      Message.error('列名仅支持中英文、数字、下划线，且以中文、字母或下划线开头');
      return;
    }
    const lowered = names.map((n) => n.toLowerCase());
    if (new Set(lowered).size !== lowered.length) { Message.error('列名不能重复'); return; }
    for (const r of editRows) {
      const def = catalog.find((d) => d.base === r.base);
      if (r.base !== '__keep__' && !def) { Message.error('请选择字段类型: ' + (r.name || '(新列)')); return; }
      if (def?.param === 'LENGTH' && (!r.length || r.length < 1)) { Message.error(r.name + ' 需要长度参数'); return; }
      if (def?.param === 'PRECISION' && (!r.precision || r.precision < 1)) { Message.error(r.name + ' 需要精度参数'); return; }
      if (def?.family === 'BOOL' && r.defaultValue.trim()
        && !['TRUE', 'FALSE'].includes(r.defaultValue.trim().toUpperCase())) {
        Message.error(r.name + ' 默认值只能为 TRUE/FALSE');
        return;
      }
    }
    const changes: ColumnChange[] = editRows.map((r) => ({
      op: r.op, column: r.column, name: r.name.trim(),
      base: r.base === '__keep__' ? null : r.base,
      length: r.length, precision: r.precision, scale: r.scale,
      nullable: r.nullable,
      defaultValue: r.op === 'add' || r.defaultValue !== r.origDefault ? r.defaultValue : undefined,
      comment: r.comment,
    }));
    try {
      const stmts = await batchColumns(selected.dsId, selected.schema, selected.table, changes, true);
      if (stmts.length === 0) { Message.warning('无变更'); return; }
      setPendingChanges(changes);
      setSqlStatements(stmts);
    } catch (e: any) {
      Message.error(e.response?.data?.message || '生成 DDL 失败');
    }
  }

  async function executeFieldChanges() {
    if (!selected || !pendingChanges) return;
    setExecuting(true);
    try {
      await batchColumns(selected.dsId, selected.schema, selected.table, pendingChanges, false);
      Message.success('字段已更新');
      setSqlStatements(null);
      setPendingChanges(null);
      setFieldEditMode(false);
      setEditRows([]);
      loadColumns();
      loadRows();
    } catch (e: any) {
      Message.error(e.response?.data?.message || '执行失败');
    } finally {
      setExecuting(false);
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

  const dataColumns = (rowsResult?.columns || []).map((c) => ({
    title: c.name,
    dataIndex: c.name,
    sorter: () => 0,
    sortOrder: sort.orderBy === c.name
      ? (sort.orderDir === 'desc' ? 'descend' : 'ascend') as 'ascend' | 'descend' | undefined
      : undefined,
    ellipsis: true,
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

  const fieldReadColumns = [
    { title: '列名', dataIndex: 'name' },
    { title: '类型', dataIndex: 'type', width: 200 },
    { title: '可空', dataIndex: 'nullable', width: 80, render: (v: string) => (v === 'NO' ? '否' : '是') },
    { title: '默认值', dataIndex: 'default', render: (v: string) => (v
      ? <Typography.Text style={{ fontSize: 13 }}>{v}</Typography.Text>
      : <Typography.Text type="secondary">NULL</Typography.Text>) },
    { title: '注释', dataIndex: 'comment', ellipsis: true },
  ];

  const fieldEditColumns = [
    {
      title: '列名', width: 180,
      render: (_: unknown, r: FieldEditRow) => (
        <Input size="small" value={r.name} onChange={(v: string) => updateEditRow(r.key, { name: v })} />
      ),
    },
    {
      title: '类型', width: 240,
      render: (_: unknown, r: FieldEditRow) => {
        const def = catalog.find((d) => d.base === r.base);
        const options = catalog.map((d) => ({ label: d.label, value: d.base }));
        if (r.base === '__keep__') {
          options.push({ label: '保持现有类型（' + (r.originalType || '') + '）', value: '__keep__' });
        }
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <Select size="small" value={r.base} options={options}
              onChange={(v: string) => updateEditRow(r.key, { base: v })} />
            {def?.param === 'LENGTH' && (
              <InputNumber size="mini" placeholder="长度" value={r.length}
                onChange={(v: any) => updateEditRow(r.key, { length: v === undefined || v === null || v === '' ? undefined : Number(v) })} />
            )}
            {def?.param === 'PRECISION' && (
              <Space size={4}>
                <InputNumber size="mini" placeholder="精度" value={r.precision}
                  onChange={(v: any) => updateEditRow(r.key, { precision: v === undefined || v === null || v === '' ? undefined : Number(v) })} />
                <InputNumber size="mini" placeholder="小数位" value={r.scale}
                  onChange={(v: any) => updateEditRow(r.key, { scale: v === undefined || v === null || v === '' ? undefined : Number(v) })} />
              </Space>
            )}
          </div>
        );
      },
    },
    {
      title: '可空', width: 70,
      render: (_: unknown, r: FieldEditRow) => (
        <Switch size="small" checked={r.nullable} onChange={(v: boolean) => updateEditRow(r.key, { nullable: v })} />
      ),
    },
    {
      title: '默认值', width: 180,
      render: (_: unknown, r: FieldEditRow) => {
        const def = catalog.find((d) => d.base === r.base);
        const isSqlServer = dsType() === 'sqlserver';
        const disabled = isSqlServer || r.base === '__keep__';
        const tip = isSqlServer ? 'SQL Server 暂不支持该属性' : '该列类型不在类型目录中，暂不支持修改默认值';
        let control: JSX.Element;
        if (disabled) {
          control = <Input size="small" disabled placeholder={tip} value={r.defaultValue}
            onChange={(v: string) => updateEditRow(r.key, { defaultValue: v })} />;
        } else if (def?.family === 'INT' || def?.family === 'DECIMAL') {
          control = <InputNumber size="small" value={r.defaultValue ? Number(r.defaultValue) : undefined}
            onChange={(v: any) => updateEditRow(r.key, { defaultValue: v === undefined || v === null || v === '' ? '' : String(v) })} />;
        } else if (def?.family === 'BOOL') {
          control = <Select size="small" allowClear placeholder="TRUE/FALSE" value={r.defaultValue || undefined}
            options={[{ label: 'TRUE', value: 'TRUE' }, { label: 'FALSE', value: 'FALSE' }]}
            onChange={(v: any) => updateEditRow(r.key, { defaultValue: v || '' })} />;
        } else {
          control = <Input size="small" value={r.defaultValue} placeholder="字面量或 CURRENT_TIMESTAMP"
            onChange={(v: string) => updateEditRow(r.key, { defaultValue: v })} />;
        }
        return disabled ? <Tooltip content={tip}><span style={{ width: '100%' }}>{control}</span></Tooltip> : control;
      },
    },
    {
      title: '注释', width: 200,
      render: (_: unknown, r: FieldEditRow) => {
        const disabled = dsType() === 'sqlserver';
        const ta = <Input.TextArea autoSize={{ minRows: 1, maxRows: 3 }} disabled={disabled} value={r.comment}
          onChange={(v: string) => updateEditRow(r.key, { comment: v })} />;
        return disabled ? <Tooltip content="SQL Server 暂不支持该属性"><span>{ta}</span></Tooltip> : ta;
      },
    },
    {
      title: '', width: 40,
      render: (_: unknown, r: FieldEditRow) => (r.op === 'add'
        ? <Button size="mini" type="text" status="danger" icon={<IconDelete />} onClick={() => removeEditRow(r.key)} />
        : <span />),
    },
  ];

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
            <Button size="small" status="danger" icon={<IconDelete />} onClick={onDeleteTable}>删除表</Button>
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
                    <Button size="small" type="primary" icon={<IconPlus />} onClick={() => { setInsertValues({}); setInsertVisible(true); }}>
                      新增行
                    </Button>
                    <Button size="small" status="danger" icon={<IconDelete />} disabled={!rowsResult?.deletable || selectedRowKeys.length === 0} onClick={handleDeleteRows}>
                      删除选中{selectedRowKeys.length > 0 ? `（${selectedRowKeys.length}）` : ''}
                    </Button>
                    <Button size="small" icon={<IconDownload />} onClick={handleExport}>导出 CSV</Button>
                    {rowsResult && !rowsResult.deletable && (
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>{rowsResult.deleteReason}</Typography.Text>
                    )}
                  </Space>

                  <div style={{ border: '1px solid var(--color-border-2)', borderRadius: 4, padding: 8, display: 'flex', flexDirection: 'column', gap: 6 }}>
                    {filters.map((f, idx) => (
                      <Space key={idx} size={6}>
                        <Select size="small" value={f.col} options={colOptions} style={{ width: 150 }} onChange={(v: string) => updateFilter(idx, { col: v })} />
                        <Select size="small" value={f.op} options={FILTER_OPS as any} style={{ width: 120 }} onChange={(v: any) => updateFilter(idx, { op: v })} />
                        {f.op !== 'isnull' && f.op !== 'notnull' && (
                          <Input size="small" value={f.value} style={{ width: 160 }} placeholder="值" onChange={(v: string) => updateFilter(idx, { value: v })} />
                        )}
                        <Button size="small" icon={<IconDelete />} onClick={() => removeFilter(idx)} />
                      </Space>
                    ))}
                    <Space size={6}>
                      <Button size="small" icon={<IconPlus />} onClick={addFilter}>添加条件</Button>
                      <Button size="small" type="primary" icon={<IconSearch />} onClick={() => { setPageNo(1); setAppliedFilters(filters); }}>查询</Button>
                      <Button size="small" onClick={() => { setFilters([]); setAppliedFilters([]); setPageNo(1); }}>重置</Button>
                    </Space>
                  </div>

                  <Table
                    loading={rowsLoading}
                    data={tableRows}
                    rowKey="__rid"
                    size="default"
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
                      size: 'default',
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
                    {fieldEditMode ? (
                      <>
                        <Button size="small" type="primary" onClick={previewFieldChanges}>保存</Button>
                        <Button size="small" onClick={exitFieldEdit}>取消</Button>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          保存后生成 DDL 预览，确认执行后立即生效
                        </Typography.Text>
                      </>
                    ) : (
                      <>
                        <Button size="small" type="primary" onClick={enterFieldEdit}>编辑</Button>
                        <Button size="small" status="danger" icon={<IconDelete />} disabled={!selectedField}
                          onClick={() => {
                            const c = columns.find((x) => x.name === selectedField);
                            if (c) { setDropTarget(c); setDropAck(false); }
                          }}>
                          删除字段
                        </Button>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          删除字段会立即修改数据库表结构，请谨慎操作
                        </Typography.Text>
                      </>
                    )}
                  </Space>
                  <Table
                    loading={columnsLoading}
                    data={(fieldEditMode ? editRows : columns) as any}
                    rowKey={fieldEditMode ? 'key' : 'name'}
                    size="default"
                    pagination={false}
                    rowSelection={fieldEditMode ? undefined : {
                      type: 'radio' as const,
                      selectedRowKeys: selectedField ? [selectedField] : [],
                      onChange: (keys: (string | number)[]) => setSelectedField((keys[0] as string) || null),
                    }}
                    columns={(fieldEditMode ? fieldEditColumns : fieldReadColumns) as any}
                  />
                  {fieldEditMode && (
                    <Button size="small" type="dashed" icon={<IconPlus />} onClick={addEditRow} style={{ width: 120 }}>
                      添加字段
                    </Button>
                  )}
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
        title="SQL 预览"
        visible={!!sqlStatements}
        confirmLoading={executing}
        okText="确认执行"
        style={{ width: 680 }}
        onOk={executeFieldChanges}
        onCancel={() => setSqlStatements(null)}
        unmountOnExit
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <Typography.Text type="secondary">
            将执行以下 {sqlStatements ? sqlStatements.length : 0} 条语句，确认后立即生效：
          </Typography.Text>
          <pre style={{
            margin: 0, padding: 12, background: 'var(--color-fill-2)', borderRadius: 4,
            maxHeight: 320, overflow: 'auto', fontSize: 12, lineHeight: 1.6, whiteSpace: 'pre-wrap',
          }}>
            {(sqlStatements || []).map((stmt, i) => (i + 1) + '. ' + stmt).join('\n')}
          </pre>
        </div>
      </Modal>

      <Modal
        title="删除字段"
        visible={!!dropTarget}
        okText="删除"
        okButtonProps={{ status: 'danger', disabled: !dropAck }}
        onOk={async () => {
          if (!selected || !dropTarget) return;
          try {
            await alterColumn(selected.dsId, selected.schema, selected.table, 'dropColumn', { column: dropTarget.name });
            Message.success('字段已删除');
            setDropTarget(null);
            loadColumns();
          } catch (e: any) {
            Message.error(e.response?.data?.message || '删除失败');
          }
        }}
        onCancel={() => setDropTarget(null)}
        unmountOnExit
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <Typography.Text>确定删除字段「{dropTarget?.name}」吗？该列数据将全部丢失且不可恢复。</Typography.Text>
          <Checkbox checked={dropAck} onChange={(v: boolean) => setDropAck(v)}>我已知晓该列数据将丢失</Checkbox>
        </div>
      </Modal>

      <Modal
        title="该表被以下资源引用"
        visible={!!deleteRefs}
        confirmLoading={deleteLoading}
        okText="强制删除"
        okButtonProps={{ status: 'danger', disabled: !deleteAck }}
        onOk={forceDeleteTable}
        onCancel={() => setDeleteRefs(null)}
        unmountOnExit
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {(deleteRefs || []).map((r, i) => (
            <Space key={i} size={6}>
              <Tag size="small" color={
                r.type === 'api_pub' ? 'blue' : r.type === 'imported' ? 'green' : 'orange'
              }>
                {r.type === 'etl_source' ? 'ETL 源表'
                  : r.type === 'etl_target' ? 'ETL 目标表'
                  : r.type === 'api_pub' ? 'API 发布' : '导入表'}
              </Tag>
              <Typography.Text>{r.name}</Typography.Text>
              {r.running && <Tag size="small" color="red">运行中</Tag>}
              {r.enabled && <Tag size="small" color="cyan">启用中</Tag>}
            </Space>
          ))}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            强制删除后，上述资源将无法正常工作，访问时报数据库错误。
          </Typography.Text>
          <Checkbox checked={deleteAck} onChange={(v: boolean) => setDeleteAck(v)}>
            我已知晓删除后上述资源将无法正常工作
          </Checkbox>
        </div>
      </Modal>
    </div>
  );
}
