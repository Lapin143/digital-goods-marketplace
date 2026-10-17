import { useEffect, useState } from 'react';

/** Сколько секунд осталось до момента `targetMs` (мс с начала эпохи); раз в секунду обновляет экран и останавливается на нуле. */
export function useRemainingSeconds(targetMs: number | undefined): number {
  const [current, setCurrent] = useState(() => Date.now());
  useEffect(() => {
    if (targetMs === undefined) {
      return undefined;
    }
    setCurrent(Date.now());
    const timer = window.setInterval(() => {
      const value = Date.now();
      setCurrent(value);
      if (value >= targetMs) {
        window.clearInterval(timer);
      }
    }, 1000);
    return () => window.clearInterval(timer);
  }, [targetMs]);
  return targetMs === undefined ? 0 : Math.max(0, Math.ceil((targetMs - current) / 1000));
}
