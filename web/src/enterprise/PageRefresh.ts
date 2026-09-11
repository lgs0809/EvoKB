import { createContext } from 'preact';
import { useContext, useEffect, useRef } from 'preact/hooks';

/** Refresh page data without remounting forms, filters or active query sessions. */
export const PageRefreshContext = createContext(0);

export function usePageRefresh(refresh: () => void | Promise<void>) {
  const version = useContext(PageRefreshContext);
  const previous = useRef(version);
  const current = useRef(refresh);
  current.current = refresh;
  useEffect(() => {
    if (previous.current === version) return;
    previous.current = version;
    void current.current();
  }, [version]);
}
