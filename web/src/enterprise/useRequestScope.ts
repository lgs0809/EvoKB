import { useEffect, useMemo, useRef } from 'preact/hooks';

/** A result may update the view only while its request and read scope are current. */
export function useRequestScope(key?: unknown) {
  const state = useRef({ key, revision: 0, active: true });
  if (state.current.key !== key) {
    state.current.key = key;
    ++state.current.revision;
  }
  useEffect(() => {
    state.current.active = true;
    return () => {
      state.current.active = false;
      ++state.current.revision;
    };
  }, []);
  return useMemo(() => {
    const snapshot = () => {
      const revision = state.current.revision;
      return () => state.current.active && state.current.revision === revision;
    };
    return {
      snapshot,
      begin: () => { ++state.current.revision; return snapshot(); },
      invalidate: () => { ++state.current.revision; },
    };
  }, []);
}
