import { forwardRef } from 'react';
import type { ButtonHTMLAttributes } from 'react';

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'outline' | 'ghost' | 'success';
export type ButtonSize = 'sm' | 'md' | 'lg';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  loadingText?: string;
}

const VARIANT_CLASSES: Record<ButtonVariant, string> = {
  primary: 'bg-slate-900 text-white shadow-sm hover:bg-slate-800 focus-visible:ring-slate-500 dark:bg-white dark:text-slate-950 dark:hover:bg-slate-200 dark:border-transparent',
  secondary: 'bg-slate-100 text-slate-900 hover:bg-slate-200 focus-visible:ring-slate-400 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700 dark:border-slate-700',
  outline:
    'border border-slate-300 bg-white text-slate-800 shadow-sm hover:bg-slate-50 focus-visible:ring-slate-400 dark:border-slate-700 dark:bg-transparent dark:text-slate-200 dark:hover:bg-slate-800',
  ghost: 'text-slate-700 hover:bg-slate-100 hover:text-slate-950 focus-visible:ring-slate-400 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white',
  danger: 'bg-red-700 text-white shadow-sm hover:bg-red-800 focus-visible:ring-red-500 dark:bg-red-600 dark:hover:bg-red-700',
  success: 'bg-emerald-700 text-white shadow-sm hover:bg-emerald-800 focus-visible:ring-emerald-500 dark:bg-emerald-600 dark:hover:bg-emerald-700',
};

const SIZE_CLASSES: Record<ButtonSize, string> = {
  sm: 'min-h-11 px-3 py-2 text-sm',
  md: 'min-h-11 px-4 py-2 text-sm',
  lg: 'min-h-12 px-5 py-3 text-base',
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    children,
    className = '',
    disabled,
    loading = false,
    loadingText,
    size = 'md',
    type = 'button',
    variant = 'primary',
    ...props
  },
  ref,
) {
  return (
    <button
      ref={ref}
      aria-busy={loading || undefined}
      className={[
        'inline-flex shrink-0 items-center justify-center gap-2 rounded-lg font-semibold leading-none transition-colors duration-200',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2',
        'disabled:cursor-not-allowed disabled:opacity-60',
        VARIANT_CLASSES[variant],
        SIZE_CLASSES[size],
        className,
      ].join(' ')}
      disabled={disabled || loading}
      type={type}
      {...props}
    >
      {loading && loadingText ? loadingText : children}
    </button>
  );
});
