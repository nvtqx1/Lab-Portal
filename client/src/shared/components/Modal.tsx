import { X } from 'lucide-react';
import { useEffect, useId, useRef } from 'react';
import type { ReactNode } from 'react';

import { Button } from './Button';

export type ModalSize = 'sm' | 'md' | 'lg' | 'xl' | '2xl' | 'full';

interface ModalProps {
  backdropBlur?: boolean;
  centered?: boolean;
  children: ReactNode;
  closeDisabled?: boolean;
  closeLabel?: string;
  closeOnEscape?: boolean;
  footer?: ReactNode;
  isOpen?: boolean;
  onClose: () => void;
  subtitle?: ReactNode;
  title: ReactNode;
  size?: ModalSize;
}

const SIZE_CLASSES: Record<ModalSize, string> = {
  sm: 'max-w-md',
  md: 'max-w-xl',
  lg: 'max-w-2xl',
  xl: 'max-w-3xl',
  '2xl': 'max-w-5xl',
  full: 'max-w-7xl',
};

export function Modal({
  backdropBlur = false,
  centered = true,
  children,
  closeDisabled = false,
  closeLabel = 'Đóng',
  closeOnEscape = true,
  footer,
  isOpen = true,
  onClose,
  subtitle,
  title,
  size = 'md',
}: ModalProps) {
  const titleId = useId();
  const dialogRef = useRef<HTMLElement>(null);
  const onCloseRef = useRef(onClose);
  const closeDisabledRef = useRef(closeDisabled);
  const closeOnEscapeRef = useRef(closeOnEscape);
  onCloseRef.current = onClose;
  closeDisabledRef.current = closeDisabled;
  closeOnEscapeRef.current = closeOnEscape;

  useEffect(() => {
    if (!isOpen) {
      return undefined;
    }

    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const focusableSelector = [
      'button:not([disabled])',
      'a[href]',
      'input:not([disabled])',
      'select:not([disabled])',
      'textarea:not([disabled])',
      '[tabindex]:not([tabindex="-1"])',
    ].join(',');
    const focusFirstControl = window.requestAnimationFrame(() => {
      dialogRef.current?.querySelector<HTMLElement>(focusableSelector)?.focus();
    });
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && closeOnEscapeRef.current && !closeDisabledRef.current) {
        onCloseRef.current();
        return;
      }
      if (event.key !== 'Tab' || !dialogRef.current) return;
      const controls = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(focusableSelector));
      if (controls.length === 0) {
        event.preventDefault();
        dialogRef.current.focus();
        return;
      }
      const first = controls[0];
      const last = controls[controls.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.cancelAnimationFrame(focusFirstControl);
      window.removeEventListener('keydown', handleKeyDown);
      document.body.style.overflow = previousOverflow;
      previousFocus?.focus();
    };
  }, [isOpen]);

  if (!isOpen) {
    return null;
  }

  return (
    <div className={`fixed inset-0 z-modal flex justify-center overflow-y-auto overscroll-contain bg-slate-950/55 p-3 sm:p-6 ${centered ? 'items-center' : 'items-start'} ${backdropBlur ? 'backdrop-blur-sm' : ''}`}>
      <section
        ref={dialogRef}
        aria-labelledby={titleId}
        aria-modal="true"
        className={`flex max-h-[calc(100dvh-1.5rem)] min-w-0 w-full flex-col overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xl dark:border-slate-700 dark:bg-slate-900 sm:max-h-[calc(100dvh-3rem)] ${SIZE_CLASSES[size]}`}
        role="dialog"
        tabIndex={-1}
      >
        <header className="flex shrink-0 items-start justify-between gap-3 border-b border-slate-200 px-4 py-3 dark:border-slate-800 sm:px-6 sm:py-4">
          <div className="min-w-0">
            <h3 className="text-lg font-semibold text-slate-950 dark:text-white" id={titleId}>
              {title}
            </h3>
            {subtitle ? <div className="mt-1 text-sm text-slate-600 dark:text-slate-300">{subtitle}</div> : null}
          </div>
          <Button aria-label={closeLabel} disabled={closeDisabled} onClick={onClose} size="sm" variant="ghost">
            <X aria-hidden="true" className="h-5 w-5" />
            <span className="sr-only">{closeLabel}</span>
          </Button>
        </header>
        <div className="min-h-0 min-w-0 flex-1 overscroll-contain overflow-y-auto px-4 py-4 sm:px-6 sm:py-5">
          {children}
        </div>
        {footer ? (
          <footer className="flex shrink-0 flex-col-reverse justify-end gap-2 border-t border-slate-200 bg-slate-50 px-4 py-3 dark:border-slate-800 dark:bg-slate-950 sm:flex-row sm:px-6 sm:py-4">
            {footer}
          </footer>
        ) : null}
      </section>
    </div>
  );
}
