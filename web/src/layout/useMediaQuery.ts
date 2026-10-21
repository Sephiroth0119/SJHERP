import { useEffect, useState } from 'react';

/** 手机 + 平板：侧栏改为抽屉。桌面 ≥1024 保持固定侧栏。 */
export const COMPACT_SHELL_MEDIA = '(max-width: 1023px)';

/** 窄屏聊天：会话列表改为抽屉。平板横屏仍可并排。 */
export const COMPACT_CHAT_MEDIA = '(max-width: 767px)';

function readMedia(query: string): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return false;
  }
  return window.matchMedia(query).matches;
}

/** 订阅 `window.matchMedia`，供壳层在窄屏切换抽屉。 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => readMedia(query));
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return undefined;
    const media = window.matchMedia(query);
    const sync = () => setMatches(media.matches);
    sync();
    media.addEventListener('change', sync);
    return () => media.removeEventListener('change', sync);
  }, [query]);
  return matches;
}
