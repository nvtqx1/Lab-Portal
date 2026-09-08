import type { ReactNode } from 'react';

import { Button } from './Button';

interface MessageStateProps {
  children?: ReactNode;
  className?: string;
}

interface ErrorStateProps extends MessageStateProps {
  onRetry?: () => void;
}

export function LoadingState({ children, className = '' }: MessageStateProps) {
  return (
    <div className={`flex items-center gap-3 rounded-xl border border-slate-200 bg-white p-4 text-sm text-slate-600 shadow-sm dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300 ${className}`} role="status">
      <span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-slate-300 border-t-slate-700 dark:border-slate-700 dark:border-t-slate-200" />
      <span>{children ?? 'Đang tải dữ liệu...'}</span>
    </div>
  );
}

export function EmptyState({ children, className = '' }: MessageStateProps) {
  return (
    <div className={`rounded-xl border border-dashed border-slate-300 bg-white p-5 text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 ${className}`}>
      {children ?? 'Chưa có dữ liệu.'}
    </div>
  );
}

export function ErrorState({ children, className = '', onRetry }: ErrorStateProps) {
  return (
    <div
      className={`flex flex-wrap items-center gap-3 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-300 ${className}`}
      role="alert"
    >
      <span>{children ?? 'Không thể tải dữ liệu. Vui lòng thử lại.'}</span>
      {onRetry ? (
        <Button onClick={onRetry} size="sm" variant="ghost">
          Tải lại
        </Button>
      ) : null}
    </div>
  );
}

export function ResponsiveTable({ children, className = '' }: MessageStateProps) {
  return (
    <div className={`max-w-full overscroll-x-contain overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900 ${className}`}>
      {children}
    </div>
  );
}
