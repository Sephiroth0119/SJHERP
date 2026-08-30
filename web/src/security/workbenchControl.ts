export interface InFlightGuard {
  tryAcquire: () => boolean;
  release: () => void;
  isLocked: () => boolean;
}

/** 同步占位，确保同一事件循环中的连续点击只能发起一次写请求。 */
export function createInFlightGuard(): InFlightGuard {
  let locked = false;
  return {
    tryAcquire: () => {
      if (locked) return false;
      locked = true;
      return true;
    },
    release: () => { locked = false; },
    isLocked: () => locked,
  };
}

export interface RequestGate {
  next: () => number;
  isCurrent: (token: number) => boolean;
}

/** 为异步读取签发单调 token，迟到响应不能覆盖更新后的界面状态。 */
export function createRequestGate(): RequestGate {
  let current = 0;
  return {
    next: () => { current += 1; return current; },
    isCurrent: (token) => token === current,
  };
}
