import { useId } from 'react';
import type { SelectHTMLAttributes } from 'react';
import styles from './TextField.module.css';

export interface SelectOption {
  value: string;
  label: string;
}

export interface SelectFieldProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'id' | 'children'> {
  label: string;
  options: SelectOption[];
  /** Пункт «не выбрано» с этой подписью; без него выбор обязателен. */
  placeholder?: string;
  hint?: string;
  error?: string;
}

/** Список выбора с подписью, подсказкой и ошибкой, связанными с полем для чтения с экрана. */
export function SelectField({ label, options, placeholder, hint, error, ...rest }: SelectFieldProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : '', error ? errorId : ''].filter(Boolean).join(' ') || undefined;
  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <select {...rest} id={id} className={styles.input} aria-invalid={error ? true : undefined} aria-describedby={describedBy}>
        {placeholder !== undefined ? <option value="">{placeholder}</option> : null}
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
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
