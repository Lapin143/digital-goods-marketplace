import { problemMessage } from '../../../api/problem';
import { plural, t } from '../../../i18n';
import { Button } from '../../../ui/Button';
import { StatePanel } from '../../../ui/StatePanel';
import { Table } from '../../../ui/Table';
import type { Column } from '../../../ui/Table';
import type { QueuePage } from './types';

/** То, что список берёт у запроса `useInfiniteQuery`: страницы, состояния и два действия. */
export interface QueueQuery<T> {
  data: { pages: Array<QueuePage<T>> } | undefined;
  error: unknown;
  isPending: boolean;
  isError: boolean;
  isFetching: boolean;
  isFetchNextPageError: boolean;
  isFetchingNextPage: boolean;
  hasNextPage: boolean;
  refetch: () => unknown;
  fetchNextPage: () => unknown;
}

export interface QueueListProps<T> {
  query: QueueQuery<T>;
  caption: string;
  columns: Array<Column<T>>;
  rowKey: (row: T) => string;
  emptyText: string;
}

/** Очередь страницами: загрузка, пусто, ошибка с повтором, «Показать ещё» по курсору. */
export function QueueList<T>({ query, caption, columns, rowKey, emptyText }: QueueListProps<T>) {
  const items: T[] = query.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <div aria-live="polite" aria-busy={query.isFetching}>
      {query.isPending ? <StatePanel kind="loading" /> : null}
      {query.isError && !query.data ? <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.refetch()} /> : null}
      {query.data && items.length === 0 ? <StatePanel kind="empty" message={emptyText} /> : null}
      {items.length > 0 ? (
        <>
          <p>{plural(items.length, { one: 'moderation.count.one', few: 'moderation.count.few', many: 'moderation.count.many' })}</p>
          <Table caption={caption} columns={columns} rows={items} rowKey={rowKey} />
          {query.isFetchNextPageError ? <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.fetchNextPage()} /> : null}
          {query.hasNextPage && !query.isFetchNextPageError ? (
            <Button variant="secondary" busy={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
              {t('catalog.more')}
            </Button>
          ) : null}
        </>
      ) : null}
    </div>
  );
}
