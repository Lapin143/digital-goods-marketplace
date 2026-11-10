import { useEffect, useReducer, useRef } from 'react';

/**
 * Возврат фокуса на кнопку или поле после закрытия панели. Элемент мог быть недоступен, пока панель открыта, поэтому фокус ставится
 * не сразу, а после перерисовки, когда элемент снова включён. Если человек уже перешёл на другой элемент, фокус не отбирается.
 *
 * Признак «фокус нужен» лежит в ссылке, а не в состоянии: эффект без списка зависимостей должен срабатывать после каждой перерисовки,
 * пока элемент не станет доступным, и правило хуков не позволяет менять состояние внутри такого эффекта.
 */
export function useReturnFocus<T extends HTMLButtonElement | HTMLInputElement = HTMLButtonElement>() {
  const ref = useRef<T>(null);
  const wanted = useRef(false);
  const [, rerender] = useReducer((count: number) => count + 1, 0);
  useEffect(() => {
    if (!wanted.current) {
      return;
    }
    const active = document.activeElement;
    if (active && active !== document.body) {
      wanted.current = false;
      return;
    }
    const target = ref.current;
    if (target && !target.disabled) {
      target.focus();
      wanted.current = false;
    }
  });
  return {
    ref,
    restore: () => {
      wanted.current = true;
      rerender();
    },
  };
}
