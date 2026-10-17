import type { ReactNode } from 'react';
import styles from './Notice.module.css';

export interface NoticeProps {
  kind: 'error' | 'info' | 'success';
  children: ReactNode;
}

/** Сообщение над формой или списком. Ошибка читается с экрана сразу (alert), остальное вежливо (status). */
export function Notice({ kind, children }: NoticeProps) {
  return (
    <div className={`${styles.notice} ${styles[kind]}`} role={kind === 'error' ? 'alert' : 'status'}>
      {children}
    </div>
  );
}
