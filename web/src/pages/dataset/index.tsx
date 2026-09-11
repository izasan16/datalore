import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button, Card, Drawer, Message, Modal, Space, Table,
} from '@arco-design/web-react';
import { IconPlus, IconRefresh } from '@arco-design/web-react/icon';
import { DatasetInfo, DatasetPreview, deleteDataset, listDatasets, previewDataset } from '../../api/dataset';

export default function DatasetPage() {
  const nav = useNavigate();
  const [list, setList] = useState<DatasetInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [preview, setPreview] = useState<DatasetPreview | null>(null);
  const [previewName, setPreviewName] = useState('');

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

  useEffect(() => {
    reload();
  }, [reload]);

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
    <Card
      title="数据集"
      extra={
        <Space>
          <Button icon={<IconRefresh />} onClick={reload} />
          <Button type="primary" icon={<IconPlus />} onClick={() => nav('/dataset/new')}>
            上传数据集
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
            title: '行数', dataIndex: 'rowCount', width: 120,
            render: (v: number) => v?.toLocaleString(),
          },
          { title: '来源文件', dataIndex: 'fileName', width: 180, ellipsis: true },
          { title: '创建时间', dataIndex: 'createdAt', width: 180, render: (v: string) => v.replace('T', ' ').slice(0, 19) },
          {
            title: '操作', width: 160,
            render: (_: unknown, row: DatasetInfo) => (
              <Space>
                <Button size="mini" type="text" onClick={() => openPreview(row)}>预览</Button>
                <Button size="mini" type="text" status="danger" onClick={() => onDelete(row)}>删除</Button>
              </Space>
            ),
          },
        ]}
      />

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
    </Card>
  );
}
