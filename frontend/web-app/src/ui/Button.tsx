import type { ButtonHTMLAttributes, ReactNode } from 'react';
import styles from './Button.module.css';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger';
  /** Идёт запрос: кнопка недоступна, повторное нажатие не создаёт второго запроса. */
  busy?: boolean;
  children: ReactNode;
}

export function Button({ variant = 'primary', busy = false, disabled, className, children, type = 'button', ...rest }: ButtonProps) {
  const classes = [styles.button, styles[variant], busy ? styles.busy : '', className ?? ''].filter(Boolean).join(' ');
  return (
    <button {...rest} type={type} className={classes} disabled={disabled || busy} aria-busy={busy || undefined}>
      {children}
    </button>
  );
}
