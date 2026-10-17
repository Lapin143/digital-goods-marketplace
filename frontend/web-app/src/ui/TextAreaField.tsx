import { useId } from 'react';
import type { TextareaHTMLAttributes } from 'react';
import styles from './TextField.module.css';

export interface TextAreaFieldProps extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'id'> {
  label: string;
  hint?: string;
  error?: string;
}

/** Многострочное поле с подписью, подсказкой и ошибкой. */
export const TextAreaField = ({ label, hint, error, ...rest }: TextAreaFieldProps) => {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : '', error ? errorId : ''].filter(Boolean).join(' ') || undefined;
  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <textarea {...rest} id={id} className={`${styles.input} ${styles.area}`} aria-invalid={error ? true : undefined} aria-describedby={describedBy} />
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
};
