import { useId } from 'react';
import type { InputHTMLAttributes, Ref } from 'react';
import styles from './TextField.module.css';

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  label: string;
  hint?: string;
  error?: string;
  /** Ссылка на поле, чтобы экран мог вернуть в него фокус после ошибки. */
  inputRef?: Ref<HTMLInputElement>;
}

/** Поле ввода с подписью, подсказкой и ошибкой, связанными с полем для чтения с экрана. */
export function TextField({ label, hint, error, inputRef, ...rest }: TextFieldProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : '', error ? errorId : ''].filter(Boolean).join(' ') || undefined;
  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <input {...rest} ref={inputRef} id={id} className={styles.input} aria-invalid={error ? true : undefined} aria-describedby={describedBy} />
      {hint ? (
        <p id={hintId} className={styles.hint}>
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} role="alert" className={styles.error}>
          {error}
        </p>
      ) : null}
    </div>
  );
}
