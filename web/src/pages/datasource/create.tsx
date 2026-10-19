import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Button, Form, Grid, Input, InputNumber, Message, Select, Space, Typography } from '@arco-design/web-react';
import { createDatasource, listDatasources, updateDatasource } from '../../api/datasource';
import { http } from '../../api/http';

const { Row, Col } = Grid;

const TYPE_LABEL: Record<string, string> = {
  postgresql: 'PostgreSQL',
  mysql: 'MySQL',
  hive: 'Hive',
  spark: 'Spark',
  flink: 'Flink',
};

const TYPE_OPTIONS = Object.entries(TYPE_LABEL).map(([value, label]) => ({ value, label }));

const DEFAULT_PORT: Record<string, number> = {
  postgresql: 5432,
  mysql: 3306,
  hive: 10000,
  spark: 8998,
  flink: 8081,
};

const isComputeType = (t?: string) => t === 'spark' || t === 'flink';
const showDatabase = (t?: string) => t !== 'spark' && t !== 'flink';
const showSchema = (t?: string) => t === 'postgresql';
const showAuth = (t?: string) => !isComputeType(t);
const showWebhdfs = (t?: string) => t === 'spark';

const HOST_PLACEHOLDER: Record<string, string> = {
  spark: '填 Livy 服务地址，如 192.168.1.10',
  flink: '填 JobManager REST 地址，如 192.168.1.10',
};
const PORT_LABEL: Record<string, string> = {
  hive: 'HiveServer2 端口',
  spark: 'Livy REST 端口',
  flink: 'JobManager REST 端口',
};
const DB_PLACEHOLDER: Record<string, string> = {
  hive: '留空使用 default',
};

export default function DatasourceCreatePage() {
  const nav = useNavigate();
  const { id } = useParams();
  const editingId = id ? Number(id) : undefined;
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();
  const selectedType = Form.useWatch('type', form);
  const [testing, setTesting] = useState(false);
  const [loaded, setLoaded] = useState(!editingId);

  useEffect(() => {
    if (editingId && !loaded) {
      listDatasources().then((list: any[]) => {
        const ds = list.find((d: any) => d.id === editingId);
        if (ds) {
          let webhdfsUrl = '';
          try {
            if (ds.extraConfig) webhdfsUrl = JSON.parse(ds.extraConfig).webhdfsUrl || '';
          } catch { /* ignore */ }
          form.setFieldsValue({
            name: ds.name, type: ds.type, host: ds.host, port: ds.port,
            databaseName: ds.databaseName || undefined, schemaName: ds.schemaName || undefined,
            webhdfsUrl: webhdfsUrl || undefined,
            username: undefined, password: undefined,
          });
        }
        setLoaded(true);
      }).catch(() => setLoaded(true));
    }
  }, [editingId, loaded, form]);

  function onTypeChange(t: string) {
    form.setFieldValue('port', DEFAULT_PORT[t]);
    if (!showDatabase(t)) form.setFieldValue('databaseName', undefined);
    if (!showSchema(t)) form.setFieldValue('schemaName', undefined);
    if (!showAuth(t)) { form.setFieldValue('username', undefined); form.setFieldValue('password', undefined); }
    if (!showWebhdfs(t)) form.setFieldValue('webhdfsUrl', undefined);
  }

  async function onTest() {
    const t = selectedType;
    const fields: string[] = ['type', 'host'];
    if (!isComputeType(t)) fields.push('databaseName', 'username', 'password');
    let v: any;
    try { v = await form.validate(fields); } catch { return; }
    setTesting(true);
    try {
      await http.post('/datasources/test', {
        type: v.type, host: v.host, port: v.port || undefined,
        databaseName: v.databaseName || undefined, schemaName: v.schemaName || undefined,
        username: v.username || undefined, password: v.password || undefined,
        extraConfig: v.webhdfsUrl ? { webhdfsUrl: v.webhdfsUrl } : undefined,
      });
      Message.success('连接成功');
    } catch (e: any) {
      Message.error(e.response?.data?.message || '连接失败');
    } finally {
      setTesting(false);
    }
  }

  async function onSubmit(v: any) {
    setSaving(true);
    try {
      const extra = v.webhdfsUrl ? { webhdfsUrl: v.webhdfsUrl } : undefined;
      if (editingId) {
        await updateDatasource(editingId, {
          name: v.name, host: v.host, port: v.port || undefined,
          databaseName: v.databaseName || undefined, schemaName: v.schemaName || undefined,
          username: v.username || undefined, password: v.password || undefined,
          extraConfig: extra,
        });
        Message.success('数据源已更新');
      } else {
        await createDatasource({
          name: v.name, type: v.type, host: v.host, port: v.port || undefined,
          databaseName: v.databaseName || undefined, schemaName: v.schemaName || undefined,
          username: v.username || undefined, password: v.password || undefined,
          extraConfig: extra,
        });
        Message.success('数据源已创建');
      }
      nav('/datasource');
    } catch (e: any) {
      Message.error(e.response?.data?.message || (editingId ? '更新失败' : '创建失败'));
    } finally {
      setSaving(false);
    }
  }

  if (!loaded) return <div className="page-shell"><Typography.Text type="secondary">加载中…</Typography.Text></div>;

  const t = selectedType;

  return (
    <div className="page-shell">
      <div className="page-header">
        <div className="page-header-main">
          <Typography.Title heading={4} style={{ margin: 0 }}>
            {editingId ? '编辑数据源' : '新建数据源'}
          </Typography.Title>
          <Typography.Text type="secondary">
            {editingId ? '修改连接信息，密码留空保持不变' : '填写连接信息，保存前可先测试连通性'}
          </Typography.Text>
        </div>
        <div className="page-header-actions">
          <Button type="text" onClick={() => nav('/datasource')}>返回列表</Button>
        </div>
      </div>

      <section className="page-section" style={{ maxWidth: 800, width: '100%', margin: '0 auto' }}>
        <Form form={form} onSubmit={onSubmit} layout="vertical" initialValues={{ type: 'postgresql' }}>
          <Typography.Title heading={6} style={{ margin: '0 0 16px' }}>基本信息</Typography.Title>
          <Row gutter={[16, 0]}>
            <Col xs={24} sm={12}>
              <Form.Item label="名称" field="name" rules={[{ required: true, message: '请输入名称' }]}>
                <Input placeholder="如：业务库" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item label="类型" field="type" rules={[{ required: true, message: '请选择类型' }]}>
                <Select options={TYPE_OPTIONS} disabled={!!editingId} onChange={onTypeChange} />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Title heading={6} style={{ margin: '8px 0 16px' }}>连接信息</Typography.Title>
          <Row gutter={[16, 0]}>
            <Col xs={24} sm={12}>
              <Form.Item label="主机" field="host" rules={[{ required: true, message: '请输入主机' }]}>
                <Input placeholder={HOST_PLACEHOLDER[t || ''] || '如 192.168.1.10'} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item label={PORT_LABEL[t || ''] || '端口（留空用默认）'} field="port">
                <InputNumber placeholder={`默认 ${DEFAULT_PORT[t || 'postgresql']}`} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            {showDatabase(t) && (
              <Col xs={24} sm={12}>
                <Form.Item label={t === 'hive' ? '数据库（可选）' : '数据库'} field="databaseName"
                  rules={[{ required: t !== 'hive', message: '请输入数据库名' }]}>
                  <Input placeholder={DB_PLACEHOLDER[t || ''] || 'database'} />
                </Form.Item>
              </Col>
            )}
            {showSchema(t) && (
              <Col xs={24} sm={12}>
                <Form.Item label="Schema（可选）" field="schemaName">
                  <Input placeholder="public" />
                </Form.Item>
              </Col>
            )}
            {showWebhdfs(t) && (
              <Col xs={24} sm={12}>
                <Form.Item label="WebHDFS 地址（可选）" field="webhdfsUrl">
                  <Input placeholder="http://namenode:9870" />
                </Form.Item>
              </Col>
            )}
            {showAuth(t) && (
              <>
                <Col xs={24} sm={12}>
                  <Form.Item label="用户名" field="username"
                    rules={[{ required: t === 'postgresql' || t === 'mysql', message: '请输入用户名' }]}>
                    <Input placeholder={t === 'hive' ? '可选（NONE 模式可留空）' : '用户名'} />
                  </Form.Item>
                </Col>
                <Col xs={24} sm={12}>
                  <Form.Item label={editingId ? '密码（留空保持不变）' : '密码'} field="password"
                    rules={editingId ? [] : [{ required: t === 'postgresql' || t === 'mysql', message: '请输入密码' }]}>
                    <Input.Password placeholder={editingId ? '留空保持原密码' : (t === 'hive' ? '可选' : '密码')} />
                  </Form.Item>
                </Col>
              </>
            )}
          </Row>
          <Space style={{ marginTop: 8 }} wrap>
            <Button onClick={onTest} loading={testing}>测试连接</Button>
            <Button type="primary" htmlType="submit" loading={saving}>保存</Button>
          </Space>
        </Form>
      </section>
    </div>
  );
}