import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button, Card, Drawer, Message, Modal, Select, Space, Table, Tag, Tree, Typography,
} from '@arco-design/web-react';
import { IconPlus, IconRefresh } from '@arco-design/web-react/icon';
import {
  ColumnDef, DatasetInfo, DatasetPreview, deleteDataset, listDatasetTables,
  listDatasets, previewDataset,
} from '../../api/dataset';
import { DataSourceInfo, listDatasources } from '../../api/datasource';

/** 数据管理：左侧数据源-表树，右侧本平台导入的数据集列表 */
export default function DatasetPage() {
  const nav = useNavigate();
  const [list, setList] = useState<DatasetInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [preview, setPreview] = useState<DatasetPreview | null>(null);
  const [previewName, setPreviewName] = useState('');

  // 左侧树
  const [dsList, setDsList] = useState<DataSourceInfo[]>([]);
  const [selectedDs, setSelectedDs] = useState<DataSourceInfo | null>(null);
  const [tables, setTables] = useState<{ name: string }[]>([]);
  const [columns, setColumns] = useState<ColumnDef[] | null>(null);
  const [colTable, setColTable] = useState<string>('');

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
      const list = await listDatasources();
      setDsList(list);
      if (list.length > 0 && !selectedDs) {
        pickDatasource(list[0]);
      }
    } catch (e: any) {
      Message.error(e.response?.data?.message || '数据源加载失败');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    reload();
    loadDatasources();
  }, [reload, loadDatasources]);

  async function pickDatasource(ds: DataSourceInfo) {
    setSelectedDs(ds);
    setColumns(null);
    setColTable('');
    try {
      setTables(await listDatasetTables(ds.id));
    } catch (e: any) {
      Message.error(e.response?.data?.message || '读取表列表失败');
      setTables([]);
    }
  }

  async function pickTable(t: string) {
    if (!selectedDs) return;
    setColTable(t);
    setColumns(null);
    try {
      setColumns(await previewColumns(selectedDs.id, t));
    } catch (e: any) {
      Message.error(e.response?.data?.message || '读取列信息失败');
    }
  }

  async function previewColumns(id: number, table: string): Promise<ColumnDef[]> {
    const { http } = await import('../../api/http');
    return http.get(`/datasources/${id}/tables/${table}/columns`).then((r) => r.data);
  }

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

  const treeData = dsList.map((ds) => ({
    key: `ds-${ds.id}`,
    title: (
      <Space>
        {ds.name}
        {ds.builtin ? <Tag size="small" color="arcoblue">内置</Tag> : <Tag size="small">{ds.type}</Tag>}
      </Space>
    ),
    children: tables.map((t) => ({ key: `tb-${t.name}`, title: t.name })),
  }));

  return (
    <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
      <Card
        title="数据源与表"
        style={{ width: 320, flexShrink: 0 }}
        extra={
          <Select
            size="mini"
            style={{ width: 110 }}
            value={selectedDs?.id}
            onChange={(v) => {
              const ds = dsList.find((d) => d.id === v);
              if (ds) pickDatasource(ds);
            }}
            options={dsList.map((d) => ({ value: d.id, label: d.name }))}
          />
        }
      >
        {selectedDs ? (
          <Tree
            {...({
              treeData,
              defaultExpandAll: true,
              selectedKeys: colTable ? [`tb-${colTable}`] : [],
              onSelect: (keys: any) => {
                const key = String(keys?.[0] || '');
                if (key.startsWith('tb-')) pickTable(key.slice(3));
              },
            } as any)}
          />
        ) : (
          <Typography.Text type="secondary">暂无数据源</Typography.Text>
        )}
        {columns && (
          <div style={{ marginTop: 12 }}>
            <Typography.Title heading={6} style={{ marginTop: 0 }}>
              列结构 · {colTable}
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
