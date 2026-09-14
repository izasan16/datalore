import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button, Card, Drawer, Message, Modal, Space, Spin, Table, Tag, Tree, Typography,
} from '@arco-design/web-react';
import { IconPlus, IconRefresh } from '@arco-design/web-react/icon';
import {
  ColumnDef, DatasetInfo, DatasetPreview, deleteDataset,
  listDatasets, previewDataset,
} from '../../api/dataset';
import { ColumnInfo, DataSourceInfo, listColumns, listDatasources, listTables } from '../../api/datasource';

/** 数据管理：左侧数据源-表树（点击数据源懒加载其表），右侧本平台导入的数据集列表 */
export default function DatasetPage() {
  const nav = useNavigate();
  const [list, setList] = useState<DatasetInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [preview, setPreview] = useState<DatasetPreview | null>(null);
  const [previewName, setPreviewName] = useState('');

  // 左侧树
  const [dsList, setDsList] = useState<DataSourceInfo[]>([]);
  const [tablesByDs, setTablesByDs] = useState<Record<number, { name: string }[]>>({});
  const [loadingDs, setLoadingDs] = useState<number | null>(null);
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const [selectedKey, setSelectedKey] = useState<string>('');
  const [columns, setColumns] = useState<ColumnDef[] | ColumnInfo[] | null>(null);
  const [colLabel, setColLabel] = useState<string>('');

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setList(await listDatasets());
    } catch (e: any) {
      Message.error(e.response?.data?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  const loadDatasources = useCallback(async () => {
    try {
      setDsList(await listDatasources());
    } catch (e: any) {
      Message.error(e.response?.data?.message || '数据源加载失败');
    }
  }, []);

  useEffect(() => {
    reload();
    loadDatasources();
  }, [reload, loadDatasources]);

  async function loadTablesOf(ds: DataSourceInfo) {
    if (tablesByDs[ds.id]) return;
    setLoadingDs(ds.id);
    try {
      const tables = await listTables(ds.id);
      setTablesByDs((prev) => ({ ...prev, [ds.id]: tables }));
    } catch (e: any) {
      Message.error(e.response?.data?.message || `读取「${ds.name}」表列表失败`);
      setTablesByDs((prev) => ({ ...prev, [ds.id]: [] }));
    } finally {
      setLoadingDs(null);
    }
  }

  function dsTitle(ds: DataSourceInfo) {
    return (
      <Space size={6}>
        {ds.name}
        {ds.builtin ? <Tag size="small" color="arcoblue">内置</Tag> : <Tag size="small">{ds.type}</Tag>}
        {loadingDs === ds.id && <Spin size={12} />}
      </Space>
    );
  }

  function onTreeSelect(keys: any) {
    const key: string = String(keys?.[0] || '');
    if (!key) return;
    setSelectedKey(key);
    if (key.startsWith('ds-')) {
      const ds = dsList.find((d) => d.id === Number(key.slice(3)));
      if (!ds) return;
      setColumns(null);
      setColLabel('');
      setExpandedKeys((prev) => (prev.includes(key) ? prev : [...prev, key]));
      loadTablesOf(ds);
    } else if (key.startsWith('tb-')) {
      // tb-{dsId}-{table}，table 名本身可含 '-'，用第一个 '-' 分割
      const rest = key.slice(3);
      const sep = rest.indexOf('-');
      const dsId = Number(rest.slice(0, sep));
      const table = rest.slice(sep + 1);
      const ds = dsList.find((d) => d.id === dsId);
      if (!ds) return;
      setColLabel(`${ds.name} · ${table}`);
      setColumns(null);
      listColumns(dsId, table)
        .then(setColumns)
        .catch((e: any) => Message.error(e.response?.data?.message || '读取列信息失败'));
    }
  }

  const treeData = dsList.map((ds) => ({
    key: `ds-${ds.id}`,
    title: dsTitle(ds),
    children: (tablesByDs[ds.id] || []).map((t) => ({
      key: `tb-${ds.id}-${t.name}`,
      title: t.name,
      isLeaf: true,
    })),
  }));

  async function openPreview(d: DatasetInfo) {
    try {
      const p = await previewDataset(d.id);
      setPreview(p);
      setPreviewName(d.name);
    } catch (e: any) {
      Message.error(e.response?.data?.message || '预览失败');
    }
  }

  function onDelete(d: DatasetInfo) {
    Modal.confirm({
      title: '删除数据集',
      content: `确定删除「${d.name}」吗？对应数据表将一并删除，该操作不可恢复。`,
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        try {
          await deleteDataset(d.id);
          Message.success('已删除');
          reload();
        } catch (e: any) {
          Message.error(e.response?.data?.message || '删除失败');
        }
      },
    });
  }

  return (
    <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
      <Card
        title="数据源与表"
        style={{ width: 340, flexShrink: 0 }}
        extra={
          <Button size="mini" icon={<IconRefresh />} onClick={() => {
            setTablesByDs({});
            loadDatasources();
          }} />
        }
      >
        <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 8 }}>
          点击数据源加载其表，点击表名查看列结构
        </Typography.Text>
        {treeData.length > 0 ? (
          <Tree
            treeData={treeData as any}
            expandedKeys={expandedKeys}
            onExpand={(keys: any) => setExpandedKeys(keys.map(String))}
            selectedKeys={selectedKey ? [selectedKey] : []}
            onSelect={onTreeSelect}
          />
        ) : (
          <Typography.Text type="secondary">暂无数据源</Typography.Text>
        )}
        {columns && (
          <div style={{ marginTop: 12, borderTop: '1px solid var(--color-border-2)', paddingTop: 12 }}>
            <Typography.Title heading={6} style={{ marginTop: 0 }}>
              列结构 · {colLabel}
            </Typography.Title>
            {columns.map((c) => (
              <div key={c.name} style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, padding: '2px 0' }}>
                <span>{c.name}</span>
                <Typography.Text type="secondary">{c.type}</Typography.Text>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card
        title="导入的数据集"
        style={{ flex: 1, minWidth: 0 }}
        extra={
          <Space>
            <Button icon={<IconRefresh />} onClick={reload} />
            <Button type="primary" icon={<IconPlus />} onClick={() => nav('/datamanage/new')}>
              上传数据
            </Button>
          </Space>
        }
      >
        <Table
          loading={loading}
          data={list}
          rowKey="id"
          pagination={false}
          columns={[
            { title: '名称', dataIndex: 'name' },
            {
              title: '行数', dataIndex: 'rowCount', width: 110,
              render: (v: number) => v?.toLocaleString(),
            },
            { title: '来源文件', dataIndex: 'fileName', width: 170, ellipsis: true },
            { title: '创建时间', dataIndex: 'createdAt', width: 170, render: (v: string) => v.replace('T', ' ').slice(0, 19) },
            {
              title: '操作', width: 150,
              render: (_: unknown, row: DatasetInfo) => (
                <Space>
                  <Button size="mini" type="text" onClick={() => openPreview(row)}>预览</Button>
                  <Button size="mini" type="text" status="danger" onClick={() => onDelete(row)}>删除</Button>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Drawer
        title={`预览 · ${previewName}`}
        visible={!!preview}
        onCancel={() => setPreview(null)}
        width={720}
        footer={null}
      >
        {preview && (
          <Table
            data={preview.rows.map((row, i) => ({ __i: i, row }))}
            rowKey="__i"
            size="small"
            pagination={false}
            columns={preview.columns.map((c, idx) => ({
              title: `${c.name}`,
              render: (_: unknown, record: { row: string[] }) => record.row[idx] ?? '',
              ellipsis: true,
            }))}
          />
        )}
      </Drawer>
    </div>
  );
}
