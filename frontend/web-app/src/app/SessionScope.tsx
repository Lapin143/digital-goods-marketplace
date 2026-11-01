import { createContext, useContext, useMemo } from 'react';
import type { ReactNode } from 'react';
import { ApiProvider, useApi } from '../api/ApiContext';
import { withDefaultAuth } from '../api/client';

/** Какой вход открыт на экранах покупателя: обычный (`web`) или ограниченный по коду из SMS (`sms`). */
export type BuyerSessionKind = 'web' | 'sms';

export interface BuyerScope {
  kind: BuyerSessionKind;
  /** Начало адресов экранов покупателя: `/account` для обычной сессии, `/sms` для сессии по SMS. */
  base: string;
}

const WEB_SCOPE: BuyerScope = { kind: 'web', base: '/account' };
const SMS_SCOPE: BuyerScope = { kind: 'sms', base: '/sms' };

const ScopeContext = createContext<BuyerScope>(WEB_SCOPE);

/** В какой сессии открыт экран: от этого зависят токен запросов, адреса ссылок и то, что сессия по SMS не может (оплата). */
export function useBuyerScope(): BuyerScope {
  return useContext(ScopeContext);
}

export interface SessionScopeProps {
  kind: BuyerSessionKind;
  children: ReactNode;
}

/**
 * Экраны заказов и обращений одни и те же для обеих сессий (FT-1.7: вход по SMS открывает заказы и обращения). Обёртка подписывает их запросы
 * токеном своего входа и задаёт адреса ссылок; права всё равно проверяет шлюз, сессия по SMS не получит ничего сверх `orders.read` и `support.write`.
 */
export function SessionScope({ kind, children }: SessionScopeProps) {
  const api = useApi();
  const scoped = useMemo(() => withDefaultAuth(api, kind), [api, kind]);
  const scope = kind === 'sms' ? SMS_SCOPE : WEB_SCOPE;
  return (
    <ScopeContext.Provider value={scope}>
      <ApiProvider client={scoped}>{children}</ApiProvider>
    </ScopeContext.Provider>
  );
}
