import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { ApiProvider } from './api/ApiContext';
import { ApiError } from './api/problem';
import { App } from './app/App';
import { AuthProvider } from './auth/AuthContext';
import { t } from './i18n';
import './ui/base.css';

// Повторяем только сбой сети и временную недоступность; ответы 4xx не повторяются: это решение сервера, а не сбой
const RETRYABLE = new Set([0, 502, 503, 504]);
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      retry: (count, error) => count < 2 && error instanceof ApiError && RETRYABLE.has(error.status),
    },
  },
});

document.title = t('app.title');
const container = document.getElementById('root');
if (!container) {
  throw new Error('В index.html нет элемента #root');
}

createRoot(container).render(
  <StrictMode>
    <BrowserRouter>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <ApiProvider>
            <App />
          </ApiProvider>
        </AuthProvider>
      </QueryClientProvider>
    </BrowserRouter>
  </StrictMode>,
);
