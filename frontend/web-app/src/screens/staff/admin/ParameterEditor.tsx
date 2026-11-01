import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useApi } from '../../../api/ApiContext';
import { parameterPath } from '../../../api/admin';
import { ApiError, fieldMessages, problemMessage } from '../../../api/problem';
import { t } from '../../../i18n';
import { Button } from '../../../ui/Button';
import { Notice } from '../../../ui/Notice';
import { TextField } from '../../../ui/TextField';
import type { PlatformParameter } from './types';
import { boundsText, checkValue, describeValue, formatParameterValue, parseWholeNumber, unitName } from './units';
import styles from './Admin.module.css';

/** Пара параметров, связанных правилом INV-07: срок платёжной сессии строго короче срока резерва. */
export const SESSION_KEY = 'payment-session.ttl-seconds';
export const RESERVE_KEY = 'reservation.ttl-seconds';

type Mode = 'view' | 'edit' | 'reset';

export interface ParameterEditorProps {
  parameter: PlatformParameter;
  /** Все параметры: для пары INV-07 экран называет текущее значение парного параметра. */
  parameters: PlatformParameter[];
  /** Сервер сохранил значение: в списке заменяется строка. */
  onSaved: (updated: PlatformParameter) => void;
  /** Версия параметра уже другая (412): список надо перечитать и повторить правку. */
  onStale: (message: string) => void;
  /** Список надо перечитать (парный параметр мог измениться). */
  onRefresh: () => void;
}

/** Объяснение нарушения INV-07 для параметра из пары; для остальных параметров пары нет. */
export function pairMessage(parameter: PlatformParameter, parameters: PlatformParameter[]): string | undefined {
  const partnerKey = parameter.key === SESSION_KEY ? RESERVE_KEY : parameter.key === RESERVE_KEY ? SESSION_KEY : undefined;
  const partner = parameters.find((item) => item.key === partnerKey);
  if (!partner) {
    return undefined;
  }
  return t(parameter.key === SESSION_KEY ? 'admin.params.error.session' : 'admin.params.error.reserve', {
    partner: describeValue(partner.unit, partner.value),
    limit: partner.value,
  });
}

/**
 * Значение параметра с правкой в строке (US-8.3). Значение вводится и сохраняется в единицах параметра (секунды, штуки, базисные пункты), а не в
 * минутах или процентах; читаемая форма видна рядом и в подсказке. Границы показаны у поля и проверяются до отправки. Сохранение идёт с `If-Match`
 * (версия параметра это его ETag): 412 значит, что параметр уже изменили, экран перечитывает список и просит повторить правку; 422 показывает границы
 * или, для пары «сессия и резерв», текущее значение парного параметра. «Вернуть умолчание» сохраняет значение по умолчанию тем же путём, с подтверждением.
 */
export function ParameterEditor({ parameter, parameters, onSaved, onStale, onRefresh }: ParameterEditorProps) {
  const api = useApi();
  const inFlight = useRef(false);
  const field = useRef<HTMLInputElement>(null);
  const [mode, setMode] = useState<Mode>('view');
  const [text, setText] = useState('');
  const [fieldError, setFieldError] = useState<string | undefined>();
  const [pairViolation, setPairViolation] = useState(false);
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (mode === 'edit') {
      field.current?.focus();
    }
  }, [mode]);

  function open(next: Mode) {
    if (inFlight.current) {
      return;
    }
    setText(String(parameter.value));
    setFieldError(undefined);
    setPairViolation(false);
    setError(undefined);
    setMode(next);
  }

  function close() {
    if (inFlight.current) {
      return;
    }
    setMode('view');
    setFieldError(undefined);
    setPairViolation(false);
    setError(undefined);
  }

  async function save(value: number) {
    if (inFlight.current) {
      return;
    }
    inFlight.current = true;
    setBusy(true);
    setFieldError(undefined);
    setPairViolation(false);
    setError(undefined);
    try {
      const response = await api.put<PlatformParameter>(parameterPath(parameter.key), { value }, { auth: 'staff', ifMatch: `"${parameter.version}"` });
      setMode('view');
      onSaved(response.data);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 412) {
        setMode('view');
        onStale(t('admin.params.conflict', { name: parameter.key }));
      } else if (failure instanceof ApiError && failure.status === 422) {
        if (pairMessage(parameter, parameters)) {
          setPairViolation(true);
          onRefresh();
        } else {
          setFieldError(`${fieldMessages(failure).value ?? t('problem.validation-failed')} ${boundsText(parameter)}`);
        }
      } else {
        setError(problemMessage(failure));
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    const check = checkValue(parameter, text);
    if (!check.ok) {
      setFieldError(check.message);
      return;
    }
    if (check.value === parameter.value) {
      setFieldError(t('admin.params.error.same'));
      return;
    }
    void save(check.value);
  }

  const pairText = pairViolation ? pairMessage(parameter, parameters) : undefined;
  const shownError = fieldError ?? pairText;

  if (mode === 'edit') {
    const typed = parseWholeNumber(text);
    const preview = typed !== undefined && parameter.unit !== 'count' ? ` ${t('admin.params.preview', { value: formatParameterValue(parameter.unit, typed) })}` : '';
    return (
      <form noValidate className={styles.editor} onSubmit={submit}>
        <TextField
          label={t('admin.params.edit.label', { unit: unitName(parameter.unit) })}
          hint={`${boundsText(parameter)}.${preview}`}
          inputMode="numeric"
          autoComplete="off"
          inputRef={field}
          value={text}
          error={shownError}
          disabled={busy}
          onChange={(event) => setText(event.target.value)}
        />
        {error ? <Notice kind="error">{error}</Notice> : null}
        <div className={styles.actions}>
          <Button type="submit" busy={busy}>
            {t('admin.params.save')}
          </Button>
          <Button variant="secondary" disabled={busy} onClick={close}>
            {t('admin.params.cancel')}
          </Button>
        </div>
      </form>
    );
  }

  if (mode === 'reset') {
    return (
      <div role="group" aria-label={t('admin.params.reset.aria', { name: parameter.key })} className={styles.editor}>
        <p>{t('admin.params.reset.confirm', { value: describeValue(parameter.unit, parameter.defaultValue) })}</p>
        {shownError ? <Notice kind="error">{shownError}</Notice> : null}
        {error ? <Notice kind="error">{error}</Notice> : null}
        <div className={styles.actions}>
          <Button busy={busy} onClick={() => void save(parameter.defaultValue)}>
            {t('admin.params.reset.confirmButton')}
          </Button>
          <Button variant="secondary" disabled={busy} onClick={close}>
            {t('admin.params.cancel')}
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.editor}>
      <strong>{describeValue(parameter.unit, parameter.value)}</strong>
      <div className={styles.actions}>
        <Button variant="secondary" aria-label={t('admin.params.edit.aria', { name: parameter.key })} onClick={() => open('edit')}>
          {t('admin.params.edit')}
        </Button>
        <Button
          variant="secondary"
          aria-label={t('admin.params.reset.button', { name: parameter.key })}
          disabled={parameter.value === parameter.defaultValue}
          onClick={() => open('reset')}
        >
          {t('admin.params.reset')}
        </Button>
      </div>
    </div>
  );
}
