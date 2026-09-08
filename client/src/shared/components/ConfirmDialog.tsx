import type { ReactNode } from 'react';

import { Button } from './Button';
import { Modal } from './Modal';

interface ConfirmDialogProps {
  cancelLabel?: string;
  confirmLabel?: string;
  isOpen: boolean;
  isPending?: boolean;
  message: ReactNode;
  onCancel: () => void;
  onConfirm: () => void;
  title: ReactNode;
  variant?: 'danger' | 'primary' | 'success';
}

export function ConfirmDialog({
  cancelLabel = 'Không',
  confirmLabel = 'Xác nhận',
  isOpen,
  isPending = false,
  message,
  onCancel,
  onConfirm,
  title,
  variant = 'danger',
}: ConfirmDialogProps) {
  return (
    <Modal
      centered
      closeDisabled={isPending}
      closeOnEscape
      footer={(
        <>
          <Button disabled={isPending} onClick={onCancel} variant="secondary">
            {cancelLabel}
          </Button>
          <Button loading={isPending} onClick={onConfirm} variant={variant}>
            {confirmLabel}
          </Button>
        </>
      )}
      isOpen={isOpen}
      onClose={onCancel}
      size="sm"
      title={title}
    >
      <div className="text-sm leading-6 text-slate-600 dark:text-slate-300">{message}</div>
    </Modal>
  );
}
