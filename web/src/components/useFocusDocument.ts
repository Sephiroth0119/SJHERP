import { useEffect, useRef } from 'react';

/**
 * 当 hash 带入单据号时加载一次详情。相同单号不重复拉取；
 * load 用 ref 保存，避免工作台每次渲染都重跑。
 */
export function useFocusDocument(
  focusDocNo: string | undefined,
  load: (docNo: string) => void,
): void {
  const loadRef = useRef(load);
  loadRef.current = load;
  const loaded = useRef<string | undefined>(undefined);

  useEffect(() => {
    const docNo = focusDocNo?.trim();
    if (!docNo || loaded.current === docNo) return;
    loaded.current = docNo;
    loadRef.current(docNo);
  }, [focusDocNo]);
}
