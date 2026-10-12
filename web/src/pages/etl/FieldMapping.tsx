import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Empty, Space, Typography } from '@arco-design/web-react';

export interface FieldCol { name: string; type: string }
export interface FieldPair { source: string; target: string }

interface Props {
  sourceCols: FieldCol[];
  targetCols: FieldCol[];
  value: FieldPair[];
  onChange: (pairs: FieldPair[]) => void;
}

interface LineState { x1: number; y1: number; x2: number; y2: number }

export default function FieldMappingPanel({ sourceCols, targetCols, value, onChange }: Props) {
  const boxRef = useRef<HTMLDivElement>(null);
  const srcDots = useRef(new Map<string, HTMLElement>());
  const tgtDots = useRef(new Map<string, HTMLElement>());
  const [lines, setLines] = useState<Record<string, LineState>>({});
  const [drag, setDrag] = useState<{ source: string; x: number; y: number } | null>(null);

  const pointOf = useCallback((el: HTMLElement | undefined, edge: 'left' | 'right') => {
    const box = boxRef.current;
    if (!box || !el) return null;
    const b = box.getBoundingClientRect();
    const r = el.getBoundingClientRect();
    return {
      x: (edge === 'right' ? r.right : r.left) - b.left,
      y: r.top + r.height / 2 - b.top,
    };
  }, []);

  const recompute = useCallback(() => {
    const next: Record<string, LineState> = {};
    for (const pair of value) {
      const p1 = pointOf(srcDots.current.get(pair.source), 'right');
      const p2 = pointOf(tgtDots.current.get(pair.target), 'left');
      if (p1 && p2) next[`${pair.source}->${pair.target}`] = { x1: p1.x, y1: p1.y, x2: p2.x, y2: p2.y };
    }
    setLines(next);
  }, [value, pointOf]);

  useEffect(() => { recompute(); }, [recompute, sourceCols, targetCols]);
  useEffect(() => {
    window.addEventListener('resize', recompute);
    return () => window.removeEventListener('resize', recompute);
  }, [recompute]);

  useEffect(() => {
    if (!drag) return;
    const move = (e: MouseEvent) => {
      const box = boxRef.current;
      if (!box) return;
      const b = box.getBoundingClientRect();
      setDrag((d) => (d ? { ...d, x: e.clientX - b.left, y: e.clientY - b.top } : d));
    };
    const up = (e: MouseEvent) => {
      const el = document.elementFromPoint(e.clientX, e.clientY) as HTMLElement | null;
      const hit = el?.closest('[data-target-dot]') as HTMLElement | null;
      const target = hit?.dataset.targetDot;
      if (target && !value.some((p) => p.target === target)) {
        onChange([...value.filter((p) => p.source !== drag.source), { source: drag.source, target }]);
      }
      setDrag(null);
    };
    document.addEventListener('mousemove', move);
    document.addEventListener('mouseup', up);
    return () => {
      document.removeEventListener('mousemove', move);
      document.removeEventListener('mouseup', up);
    };
  }, [drag, value, onChange]);

  const usedSources = new Set(value.map((p) => p.source));
  const usedTargets = new Set(value.map((p) => p.target));
  const common = sourceCols.filter((s) => targetCols.some((t) => t.name === s.name));

  const startDrag = (e: React.MouseEvent, source: string) => {
    e.preventDefault();
    const p = pointOf(srcDots.current.get(source), 'right');
    if (p) setDrag({ source, x: p.x, y: p.y });
  };

  const renderRows = (cols: FieldCol[], side: 'source' | 'target') => cols.map((c) => {
    const connected = side === 'source' ? usedSources.has(c.name) : usedTargets.has(c.name);
    const dot = (
      <span
        data-source-dot={side === 'source' ? c.name : undefined}
        data-target-dot={side === 'target' ? c.name : undefined}
        ref={(el) => { if (el) { (side === 'source' ? srcDots : tgtDots).current.set(c.name, el); } else { (side === 'source' ? srcDots : tgtDots).current.delete(c.name); } }}
        onMouseDown={side === 'source' ? (e) => startDrag(e, c.name) : undefined}
        style={{
          width: 10, height: 10, borderRadius: 5, flexShrink: 0,
          background: connected ? '#00b42a' : '#165dff',
          cursor: side === 'source' ? 'crosshair' : 'pointer',
          opacity: connected ? 0.85 : 1,
        }}
      />
    );
    return (
      <div key={c.name} style={{
        display: 'flex', alignItems: 'center', gap: 8, padding: '7px 12px',
        borderBottom: '1px solid var(--color-border-2)', fontSize: 12,
        flexDirection: side === 'source' ? 'row' : 'row-reverse',
      }}>
        {side === 'source' ? (
          <><span style={{ fontWeight: 500 }}>{c.name}</span><span style={{ color: 'var(--color-text-3)' }}>{c.type}</span><span style={{ flex: 1 }} />{dot}</>
        ) : (
          <>{dot}<span style={{ flex: 1 }} /><span style={{ color: 'var(--color-text-3)' }}>{c.type}</span><span style={{ fontWeight: 500 }}>{c.name}</span></>
        )}
      </div>
    );
  });

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>拖动源字段圆点到目标字段建立映射；每个字段只能连线一次</Typography.Text>
        <Space>
          <Button size="mini" onClick={() => onChange(common.map((c) => ({ source: c.name, target: c.name })))} disabled={common.length === 0}>同名映射</Button>
          <Button size="mini" status="danger" onClick={() => onChange([])} disabled={value.length === 0}>取消映射</Button>
        </Space>
      </div>
      <div ref={boxRef} style={{ position: 'relative', display: 'flex', border: '1px solid var(--color-border-2)', borderRadius: 4 }}
        onScroll={() => requestAnimationFrame(recompute)}>
        <div style={{ flex: 1, maxHeight: 300, overflowY: 'auto' }}>
          <div style={{ padding: '8px 12px', fontWeight: 600, fontSize: 12, borderBottom: '1px solid var(--color-border-2)', background: 'var(--color-fill-1)' }}>来源字段</div>
          {sourceCols.length === 0 ? <div style={{ padding: 24 }}><Empty description="选择源表后展示字段" /></div> : renderRows(sourceCols, 'source')}
        </div>
        <div style={{ width: 160, position: 'relative', background: 'var(--color-fill-1)' }}>
          <svg style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', pointerEvents: 'none' }}>
            {Object.entries(lines).map(([key, l]) => (
              <g key={key} style={{ pointerEvents: 'auto', cursor: 'pointer' }}
                onClick={() => onChange(value.filter((p) => `${p.source}->${p.target}` !== key))}>
                <line {...l} stroke="#165dff" strokeWidth={2} />
                <circle cx={(l.x1 + l.x2) / 2} cy={(l.y1 + l.y2) / 2} r={7} fill="#fff" stroke="#165dff" strokeWidth={1} />
                <text x={(l.x1 + l.x2) / 2} y={(l.y1 + l.y2) / 2 + 3.5} textAnchor="middle" fontSize={9} fill="#f53f3f">×</text>
              </g>
            ))}
            {drag && (() => {
              const p = pointOf(srcDots.current.get(drag.source), 'right');
              return p ? <line x1={p.x} y1={p.y} x2={drag.x} y2={drag.y} stroke="#165dff" strokeWidth={1.5} strokeDasharray="4 3" /> : null;
            })()}
          </svg>
        </div>
        <div style={{ flex: 1, maxHeight: 300, overflowY: 'auto' }}>
          <div style={{ padding: '8px 12px', fontWeight: 600, fontSize: 12, borderBottom: '1px solid var(--color-border-2)', background: 'var(--color-fill-1)' }}>目标字段</div>
          {targetCols.length === 0 ? <div style={{ padding: 24 }}><Empty description="选择目标表后展示字段" /></div> : renderRows(targetCols, 'target')}
        </div>
      </div>
    </div>
  );
}
