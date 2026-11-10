import { useEffect, useRef, useState } from 'react';

/**
 * Возврат фокуса на кнопку или поле после закрытия панели. Элемент мог быть недоступен, пока панель открыта, поэтому фокус ставится
 * не сразу, а после перерисовки, когда элемент снова включён. Если человек уже перешёл на другой элемент, фокус не отбирается.
 */
export function useReturnFocus<T extends HTMLButtonElement | HTMLInputElement = HTMLButtonElement>() {
  const ref = useRef<T>(null);
  const [wanted, setWanted] = useState(false);
  useEffect(() => {
    if (!wanted) {
      return;
    }
    const active = document.activeElement;
    if (active && active !== document.body) {
      setWanted(false);
      return;
    }
    const target = ref.current;
    if (target && !target.disabled) {
      target.focus();
      setWanted(false);
    }
  });
  return { ref, restore: () => setWanted(true) };
}
