import type { ReactNode } from 'react';
import styles from './Badge.module.css';

export interface BadgeProps {
  tone: 'neutral' | 'good' | 'warn' | 'bad';
  children: ReactNode;
}

/** Метка статуса: цвет не единственный признак, смысл несёт текст внутри. */
export function Badge({ tone, children }: BadgeProps) {
  return <span className={`${styles.badge} ${styles[tone]}`}>{children}</span>;
}
