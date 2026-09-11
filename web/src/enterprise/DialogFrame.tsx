import type { ComponentChildren } from 'preact';
import { useEffect, useRef } from 'preact/hooks';

/** Native dialog supplies focus containment, inert background, Escape and focus restoration. */
export function DialogFrame({ title, onClose, children, side = 'center' }: {
  title: string; onClose: () => void; children: ComponentChildren; side?: 'center' | 'right' | 'left';
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const node = dialog.current;
    if (!node) return;
    node.showModal();
    return () => { if (node.open) node.close(); };
  }, []);
  return <dialog ref={dialog} aria-label={title} class={`evokb-dialog ${side === 'right' ? 'evokb-drawer' : side === 'left' ? 'evokb-drawer-left' : ''}`}
    onCancel={event => { event.preventDefault(); onClose(); }}
    onClick={event => { if (event.target === event.currentTarget) onClose(); }}>{children}</dialog>;
}
