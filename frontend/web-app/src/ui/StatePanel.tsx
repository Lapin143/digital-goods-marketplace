import { t } from '../i18n';
import { Button } from './Button';
import styles from './StatePanel.module.css';

export type StateKind = 'loading' | 'empty' | 'error';

export interface StatePanelProps {
  kind: StateKind;
  /** Свой текст вместо стандартного для состояния. */
  message?: string;
  onRetry?: () => void;
}

/** Три состояния любого списка и экрана: загрузка, пусто, ошибка (с повтором). */
export function StatePanel({ kind, message, onRetry }: StatePanelProps) {
  const text = message ?? t(kind === 'loading' ? 'state.loading' : kind === 'empty' ? 'state.empty' : 'state.error');
  return (
    <div className={`${styles.panel} ${kind === 'error' ? styles.error : ''}`} role={kind === 'error' ? 'alert' : 'status'}>
      <p>{text}</p>
      {kind === 'error' && onRetry ? (
        <Button variant="secondary" onClick={onRetry}>
          {t('state.retry')}
        </Button>
      ) : null}
    </div>
  );
}
