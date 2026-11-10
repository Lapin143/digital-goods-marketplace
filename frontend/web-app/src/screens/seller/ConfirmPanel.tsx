import { useEffect, useId, useRef } from 'react';
import type { ReactNode } from 'react';
import { Button } from '../../ui/Button';
import styles from './Panels.module.css';

export interface ConfirmPanelProps {
  heading: string;
  children: ReactNode;
  confirmLabel: string;
  cancelLabel: string;
  /** Опасное действие (отзыв, смена секрета): кнопка подтверждения красная. */
  danger?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * Подтверждение необратимого действия в той же странице. Фокус при открытии на «Отмена» (безопасный выбор), Escape отменяет,
 * обе кнопки доступны с клавиатуры. Вернуть фокус на кнопку, открывшую панель, должен экран после отмены.
 */
export function ConfirmPanel({ heading, children, confirmLabel, cancelLabel, danger = false, busy = false, onConfirm, onCancel }: ConfirmPanelProps) {
  const headingId = useId();
  const textId = useId();
  const cancel = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    cancel.current?.focus();
  }, []);

  return (
    <section
      role="alertdialog"
      aria-labelledby={headingId}
      aria-describedby={textId}
      className={styles.panel}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && !busy) {
          event.stopPropagation();
          onCancel();
        }
      }}
    >
      <h2 id={headingId} className={styles.heading}>
        {heading}
      </h2>
      <div id={textId}>{children}</div>
      <div className={styles.actions}>
        <Button variant={danger ? 'danger' : 'primary'} busy={busy} onClick={onConfirm}>
          {confirmLabel}
        </Button>
        <Button variant="secondary" buttonRef={cancel} disabled={busy} onClick={onCancel}>
          {cancelLabel}
        </Button>
      </div>
    </section>
  );
}
