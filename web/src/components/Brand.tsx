/** 品牌 Logo 标记（渐变方块 + 白色数据折线） */
export function LogoMark({ size = 28 }: { size?: number }) {
  return (
    <span
      className="ws-sider-logo-mark"
      style={{ width: size, height: size, borderRadius: size * 0.28 }}
      aria-hidden
    >
      <svg width={size * 0.62} height={size * 0.62} viewBox="0 0 24 24" fill="none">
        <path
          d="M3 17.5 9 10l4 4.5L21 5.5"
          stroke="#fff"
          strokeWidth="2.6"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <circle cx="21" cy="5.5" r="2.2" fill="#fff" />
      </svg>
    </span>
  );
}

/** 品牌名 */
export function BrandName({ light = false }: { light?: boolean }) {
  return (
    <span style={{ color: light ? '#fff' : 'var(--daaspro-text-main)', letterSpacing: 0.3 }}>
      daaspro
    </span>
  );
}

/** 认证页特性列表里的对勾图标 */
export function CheckIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden>
      <circle cx="12" cy="12" r="10" fill="rgba(255,255,255,0.14)" />
      <path
        d="m7.5 12.2 3 3 6-6.4"
        stroke="#7fd0ff"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
