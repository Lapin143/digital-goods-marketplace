import { useEffect, useId, useRef, useState } from 'react';
import { t } from '../../i18n';
import { Button } from '../../ui/Button';
import styles from './Panels.module.css';

export interface OneTimeSecretProps {
  /** Что за значение: «Ваш новый API-ключ», «Секрет подписи». */
  heading: string;
  /** Предупреждение, что значение показывается один раз. */
  warning: string;
  value: string;
  /** Явное подтверждение: значение сохранено, панель можно закрыть. */
  onClose: () => void;
}

/**
 * Панель разового значения (ключ API, секрет подписи). Значение живёт только в свойстве `value` и в разметке этой панели:
 * после закрытия родитель убирает его из своего состояния, панель исчезает вместе с ним. Закрыть панель можно только кнопкой
 * после отметки «сохранил»; клавиша Escape и щелчок вне панели её не закрывают, чтобы значение не пропало случайно.
 * Копирование идёт через буфер обмена, а если он недоступен, значение можно выделить в рамке вручную.
 */
export function OneTimeSecret({ heading, warning, value, onClose }: OneTimeSecretProps) {
  const headingId = useId();
  const warningId = useId();
  const panel = useRef<HTMLElement>(null);
  const [saved, setSaved] = useState(false);
  const [copy, setCopy] = useState<'idle' | 'done' | 'failed'>('idle');

  useEffect(() => {
    panel.current?.focus();
  }, []);

  async function copyValue() {
    try {
      await navigator.clipboard.writeText(value);
      setCopy('done');
    } catch {
      setCopy('failed');
    }
  }

  return (
    <section ref={panel} tabIndex={-1} role="alertdialog" aria-labelledby={headingId} aria-describedby={warningId} className={styles.panel}>
      <h2 id={headingId} className={styles.heading}>
        {heading}
      </h2>
      <p id={warningId}>
        <strong>{warning}</strong>
      </p>
      <code className={styles.value}>{value}</code>
      <div className={styles.row}>
        <Button variant="secondary" onClick={() => void copyValue()}>
          {t('secret.copy')}
        </Button>
        {copy === 'done' ? <span role="status"> {t('secret.copied')}</span> : null}
        {copy === 'failed' ? <span role="alert"> {t('secret.copyFailed')}</span> : null}
      </div>
      <label className={styles.confirm}>
        <input type="checkbox" checked={saved} onChange={(event) => setSaved(event.target.checked)} />
        <span>{t('secret.saved')}</span>
      </label>
      <Button disabled={!saved} onClick={onClose}>
        {t('secret.close')}
      </Button>
    </section>
  );
}
