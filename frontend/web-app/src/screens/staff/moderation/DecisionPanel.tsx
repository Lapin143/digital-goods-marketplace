import { useEffect, useRef, useState } from 'react';
import { useApi } from '../../../api/ApiContext';
import { ApiError, fieldMessages, problemMessage } from '../../../api/problem';
import { useRequestKeys } from '../../../api/requestKey';
import { t } from '../../../i18n';
import { Button } from '../../../ui/Button';
import { Notice } from '../../../ui/Notice';
import { TextAreaField } from '../../../ui/TextAreaField';
import { REASON_MAX, checkReason } from './decision';
import styles from './Moderation.module.css';

/** Одно решение модератора. `id` это и действие, и последний сегмент адреса (`approve`, `reject`, `return`, `block`). */
export interface DecisionOption {
  id: string;
  label: string;
  variant: 'primary' | 'secondary' | 'danger';
  /** Поле причины или комментария (1–1000 символов). Без него решение принимается одним подтверждением. */
  text?: { field: 'reason' | 'comment'; label: string };
  /** Вопрос на шаге подтверждения. */
  confirm: string;
  /** Сообщение после принятого решения. */
  done: string;
}

export interface DecisionPanelProps {
  /** Идентификатор заявки или товара: входит в отпечаток ключа идемпотентности. */
  subjectId: string;
  /** Адрес объекта без действия, например `/api/v1/staff/products/p-1`. */
  path: string;
  options: DecisionOption[];
  /** Название статуса для сообщения «уже решено». */
  statusLabel: (status: string) => string;
  /** Решение принято: карточку надо загрузить заново. */
  onDecided: (message: string) => void;
  /** Сервер ответил 409: объект уже в другом статусе (решил другой модератор), карточку надо загрузить заново. */
  onConflict: (message: string) => void;
}

/**
 * Решения над заявкой или товаром. Каждое проходит два шага: выбор действия и подтверждение (для отказа, возврата и блокировки
 * ещё и причина). От двойного нажатия защищает флаг запроса в `ref` (второй клик до перерисовки не создаёт запрос) и недоступная
 * кнопка; от потери ответа и повтора после сбоя сети защищает `Idempotency-Key`: пока исход неизвестен, повтор уходит с тем же
 * ключом и сервер вернёт тот же результат, а не применит решение второй раз.
 */
export function DecisionPanel({ subjectId, path, options, statusLabel, onDecided, onConflict }: DecisionPanelProps) {
  const api = useApi();
  const keys = useRequestKeys();
  const inFlight = useRef(false);
  const panel = useRef<HTMLDivElement>(null);
  const [chosen, setChosen] = useState<DecisionOption | undefined>(undefined);
  const [text, setText] = useState('');
  const [fieldError, setFieldError] = useState<string | undefined>(undefined);
  const [error, setError] = useState<string | undefined>(undefined);
  const [busy, setBusy] = useState(false);

  // Шаг подтверждения открылся: фокус на него, чтобы с клавиатуры и с экрана читалось сразу
  useEffect(() => {
    if (chosen) {
      panel.current?.focus();
    }
  }, [chosen]);

  function choose(option: DecisionOption) {
    if (inFlight.current) {
      return;
    }
    setChosen(option);
    setText('');
    setFieldError(undefined);
    setError(undefined);
  }

  function cancel() {
    if (inFlight.current) {
      return;
    }
    setChosen(undefined);
    setFieldError(undefined);
    setError(undefined);
  }

  async function confirm() {
    if (!chosen || inFlight.current) {
      return;
    }
    if (chosen.text) {
      const problem = checkReason(text);
      if (problem) {
        setFieldError(t(problem));
        return;
      }
    }
    inFlight.current = true;
    setBusy(true);
    setFieldError(undefined);
    setError(undefined);
    const body = chosen.text ? { [chosen.text.field]: text.trim() } : undefined;
    try {
      await api.post(`${path}/${chosen.id}`, body, { auth: 'staff', idempotencyKey: keys.keyFor(`${chosen.id}:${subjectId}:${text.trim()}`) });
      keys.settle();
      setChosen(undefined);
      onDecided(chosen.done);
    } catch (failure) {
      keys.settle(failure);
      if (failure instanceof ApiError && failure.status === 409 && failure.code === 'state-conflict') {
        setChosen(undefined);
        onConflict(failure.problem.currentStatus ? t('moderation.alreadyDecided', { status: statusLabel(failure.problem.currentStatus) }) : t('moderation.alreadyDecidedNoStatus'));
      } else {
        const reason = Object.values(fieldMessages(failure))[0];
        if (reason && chosen.text) {
          setFieldError(reason);
        }
        setError(problemMessage(failure));
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  return (
    <div className={styles.decisions}>
      <h2>{t('moderation.decision.title')}</h2>
      {!chosen ? (
        <div className={styles.actions}>
          {options.map((option) => (
            <Button key={option.id} variant={option.variant} onClick={() => choose(option)}>
              {option.label}
            </Button>
          ))}
        </div>
      ) : (
        <div ref={panel} tabIndex={-1} role="group" aria-label={chosen.label} className={styles.confirm}>
          <p>{chosen.confirm}</p>
          {chosen.text ? (
            <TextAreaField
              label={chosen.text.label}
              hint={t('moderation.reason.hint', { limit: REASON_MAX })}
              maxLength={REASON_MAX}
              value={text}
              error={fieldError}
              disabled={busy}
              onChange={(event) => setText(event.target.value)}
            />
          ) : null}
          {error ? <Notice kind="error">{error}</Notice> : null}
          <div className={styles.actions}>
            <Button variant={chosen.variant} busy={busy} onClick={() => void confirm()}>
              {t('moderation.decision.confirm')}
            </Button>
            <Button variant="secondary" disabled={busy} onClick={cancel}>
              {t('moderation.decision.cancel')}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
