import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import type { FormEvent } from 'react';
import { useApi } from '../../api/ApiContext';
import { ApiError, fieldMessages, problemMessage } from '../../api/problem';
import { t } from '../../i18n';
import { Button } from '../../ui/Button';
import { Notice } from '../../ui/Notice';
import { StatePanel } from '../../ui/StatePanel';
import { TextField } from '../../ui/TextField';
import { formatMoment } from '../../ui/format';
import { ConfirmPanel } from './ConfirmPanel';
import { useReturnFocus } from './focus';
import { parseQuantity, stockEtag } from './stock';
import { stockKey, stockPath, useStock } from './useStock';
import type { ApiStock, StockInfo } from './types';
import styles from './SellerProducts.module.css';

type Message = { kind: 'error' | 'success'; text: string } | undefined;

/** Остаток, который человек видел, когда начал править поле: его версия уходит в `If-Match`, а не та, что подтянулась позже. */
interface Baseline {
  version: number;
  available: number;
}

/** Конфликт версий (412): что человек ввёл, каким остаток был у него на экране и каким стал на сервере. */
interface Conflict {
  mine: number;
  was: number;
  current: StockInfo;
}

/**
 * Остаток товара с выдачей по API (US-4.7). Значение заменяет остаток целиком и уходит с `If-Match` по версии записи (ADR-008):
 * пока продавец правил форму, заказы могли уменьшить остаток. Ответ 412 не стирает введённое и не повторяет запрос молча:
 * экран перечитывает остаток, показывает «сейчас N, было M» и предлагает два явных выбора: применить своё значение поверх
 * актуального (новый запрос с новой версией) или оставить актуальное.
 */
export function ApiStockSection({ productId, readOnly }: { productId: string; readOnly: boolean }) {
  const api = useApi();
  const queryClient = useQueryClient();
  const query = useStock(productId);
  const inputFocus = useReturnFocus<HTMLInputElement>();

  const [text, setText] = useState('');
  const [dirty, setDirty] = useState(false);
  const [baseline, setBaseline] = useState<Baseline | undefined>(undefined);
  const [fieldError, setFieldError] = useState<string | undefined>(undefined);
  const [message, setMessage] = useState<Message>(undefined);
  const [busy, setBusy] = useState(false);
  const [conflict, setConflict] = useState<Conflict | undefined>(undefined);

  if (query.isPending) {
    return <StatePanel kind="loading" />;
  }
  if (query.isError) {
    return <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.refetch()} />;
  }
  const stock = query.data;
  // Пока человек не менял поле, в нём актуальный остаток; после правки остаётся его текст, даже если остаток на сервере изменился
  const shown = dirty ? text : String(stock.available);

  async function put(quantity: number, version: number, was: number) {
    setBusy(true);
    setMessage(undefined);
    try {
      await api.put<ApiStock>(`${stockPath(productId)}/api-stock`, { quantity }, { auth: 'staff', ifMatch: stockEtag(version) });
      setConflict(undefined);
      setDirty(false);
      setBaseline(undefined);
      setText('');
      setFieldError(undefined);
      setMessage({ kind: 'success', text: t('apiStock.saved', { quantity }) });
      await queryClient.invalidateQueries({ queryKey: stockKey(productId) });
    } catch (error) {
      if (error instanceof ApiError && error.status === 412) {
        try {
          const response = await api.get<StockInfo>(`${stockPath(productId)}/stock`, { auth: 'staff' });
          queryClient.setQueryData(stockKey(productId), response.data);
          setConflict({ mine: quantity, was, current: response.data });
        } catch (reload) {
          setMessage({ kind: 'error', text: problemMessage(reload) });
        }
      } else {
        setFieldError(fieldMessages(error).quantity);
        setMessage({ kind: 'error', text: problemMessage(error) });
      }
    } finally {
      setBusy(false);
    }
  }

  function submit() {
    const parsed = parseQuantity(shown);
    if (parsed.kind !== 'value') {
      setFieldError(t(parsed.kind === 'empty' ? 'field.required' : 'apiStock.error.range'));
      return;
    }
    setFieldError(undefined);
    const known = dirty && baseline ? baseline : { version: stock.version ?? 0, available: stock.available };
    void put(parsed.value, known.version, known.available);
  }

  return (
    <section>
      <h2>{t('apiStock.title')}</h2>
      <p>{t('apiStock.lead')}</p>
      <dl className={styles.stock}>
        <dt>{t('apiStock.available')}</dt>
        <dd>{stock.available}</dd>
        <dt>{t('apiStock.reserved')}</dt>
        <dd>{stock.reserved}</dd>
        <dt>{t('apiStock.version')}</dt>
        <dd>{stock.version ?? 0}</dd>
        {stock.updatedAt ? (
          <>
            <dt>{t('keys.stock.updatedAt')}</dt>
            <dd>{formatMoment(stock.updatedAt)}</dd>
          </>
        ) : null}
      </dl>

      {message ? <Notice kind={message.kind}>{message.text}</Notice> : null}

      {conflict ? (
        <ConfirmPanel
          heading={t('apiStock.conflict.heading')}
          confirmLabel={t('apiStock.conflict.apply', { mine: conflict.mine })}
          cancelLabel={t('apiStock.conflict.keep', { now: conflict.current.available })}
          busy={busy}
          onConfirm={() => void put(conflict.mine, conflict.current.version ?? 0, conflict.current.available)}
          onCancel={() => {
            setConflict(undefined);
            setDirty(false);
            setBaseline(undefined);
            setText('');
            setFieldError(undefined);
            inputFocus.restore();
          }}
        >
          <p>{t('apiStock.conflict.state', { now: conflict.current.available, was: conflict.was })}</p>
          <p>{t('apiStock.conflict.choice', { mine: conflict.mine })}</p>
        </ConfirmPanel>
      ) : null}

      <form
        className={styles.form}
        noValidate
        onSubmit={(event: FormEvent) => {
          event.preventDefault();
          submit();
        }}
      >
        <TextField
          label={t('apiStock.quantity')}
          hint={t('apiStock.quantity.hint')}
          inputMode="numeric"
          autoComplete="off"
          inputRef={inputFocus.ref}
          disabled={readOnly || conflict !== undefined}
          value={shown}
          error={fieldError}
          onChange={(event) => {
            if (!dirty) {
              setBaseline({ version: stock.version ?? 0, available: stock.available });
            }
            setText(event.target.value);
            setDirty(true);
          }}
        />
        <Button type="submit" busy={busy && conflict === undefined} disabled={readOnly || busy || conflict !== undefined}>
          {t('apiStock.save')}
        </Button>
      </form>
    </section>
  );
}
