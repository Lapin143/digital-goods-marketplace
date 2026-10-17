import { createContext, useContext, useMemo } from 'react';
import type { ReactNode } from 'react';
import { useAuth } from '../auth/AuthContext';
import { createApiClient } from './client';
import type { ApiClient } from './client';

const ApiContext = createContext<ApiClient | undefined>(undefined);

export interface ApiProviderProps {
  children: ReactNode;
  /** Для тестов: готовый клиент вместо настоящего. */
  client?: ApiClient;
}

export function ApiProvider({ children, client }: ApiProviderProps) {
  const { token, forget } = useAuth();
  const value = useMemo(() => client ?? createApiClient({ token, onUnauthorized: forget }), [client, token, forget]);
  return <ApiContext.Provider value={value}>{children}</ApiContext.Provider>;
}

export function useApi(): ApiClient {
  const value = useContext(ApiContext);
  if (!value) {
    throw new Error('useApi must be used inside ApiProvider');
  }
  return value;
}
