import { X } from 'lucide-react';
import { useEffect, useState } from 'react';

import { PENDING_TOAST_KEY, TOAST_EVENT, type ToastPayload } from './toast';

interface ToastState extends ToastPayload {
  id: number;
}

const VARIANT_CLASSES = {
  success: 'border-emerald-200 bg-emerald-50 text-emerald-800',
  error: 'border-red-200 bg-red-50 text-red-800',
  warning: 'border-amber-200 bg-amber-50 text-amber-800',
  info: 'border-blue-200 bg-blue-50 text-blue-800',
} as const;

const VARIANT_LABELS = {
  success: 'Thành công',
  error: 'Lỗi',
  warning: 'Cảnh báo',
  info: 'Thông tin',
} as const;

export function ToastContainer() {
  const [toast, setToast] = useState<ToastState | null>(null);

  useEffect(() => {
    const pendingToast = sessionStorage.getItem(PENDING_TOAST_KEY);
    if (pendingToast) {
      sessionStorage.removeItem(PENDING_TOAST_KEY);
      try {
        const parsedToast = JSON.parse(pendingToast) as ToastPayload;
        setToast({ ...parsedToast, id: Date.now() });
      } catch {
        // Ignore malformed persisted toast payloads.
      }
    }

    const handleToast = (event: Event) => {
      const customEvent = event as CustomEvent<ToastPayload>;
      setToast({ ...customEvent.detail, id: Date.now() });
    };

    window.addEventListener(TOAST_EVENT, handleToast);
    return () => window.removeEventListener(TOAST_EVENT, handleToast);
  }, []);

  useEffect(() => {
    if (!toast) {
      return undefined;
    }

    const timeoutId = window.setTimeout(() => setToast(null), 5000);
    return () => window.clearTimeout(timeoutId);
  }, [toast]);

  if (!toast) {
    return null;
  }

  return (
    <div className="toast-enter fixed inset-x-3 bottom-3 z-toast sm:inset-x-auto sm:bottom-4 sm:right-4 sm:w-96 sm:max-w-[calc(100vw-2rem)]">
      <div
        className={[
          'relative break-words rounded-xl border px-4 py-3 pr-14 text-sm shadow-lg',
          VARIANT_CLASSES[toast.variant],
        ].join(' ')}
        role={toast.variant === 'error' ? 'alert' : 'status'}
      >
        <p className="font-semibold">{toast.title ?? VARIANT_LABELS[toast.variant]}</p>
        <p className="mt-1">{toast.message}</p>
        <button
          aria-label="Đóng thông báo"
          className="absolute right-1.5 top-1.5 flex h-11 w-11 items-center justify-center rounded-lg transition-colors duration-200 hover:bg-black/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-current"
          type="button"
          onClick={() => setToast(null)}
        >
          <X aria-hidden="true" className="h-4 w-4" />
        </button>
      </div>
    </div>
  );
}
