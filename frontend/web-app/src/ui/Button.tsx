import type { ButtonHTMLAttributes, ReactNode, Ref } from 'react';
import styles from './Button.module.css';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger';
  /** Идёт запрос: кнопка недоступна, повторное нажатие не создаёт второго запроса. */
  busy?: boolean;
  /** Ссылка на кнопку, чтобы экран мог вернуть на неё фокус после закрытия панели. */
  buttonRef?: Ref<HTMLButtonElement>;
  children: ReactNode;
}

export function Button({ variant = 'primary', busy = false, disabled, className, children, type = 'button', buttonRef, ...rest }: ButtonProps) {
  const classes = [styles.button, styles[variant], busy ? styles.busy : '', className ?? ''].filter(Boolean).join(' ');
  return (
    <button {...rest} ref={buttonRef} type={type} className={classes} disabled={disabled || busy} aria-busy={busy || undefined}>
      {children}
    </button>
  );
}
