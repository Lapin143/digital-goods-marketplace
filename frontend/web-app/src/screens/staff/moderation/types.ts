import type { components as CatalogComponents } from '../../../api/generated/catalog';

export type ApplicationQueueItem = CatalogComponents['schemas']['SellerApplicationQueueItem'];
export type ApplicationCard = CatalogComponents['schemas']['SellerApplicationCard'];
export type ReviewDocument = CatalogComponents['schemas']['ReviewDocument'];
export type ProductQueueItem = CatalogComponents['schemas']['ModerationQueueItem'];
export type ModerationProduct = CatalogComponents['schemas']['SellerProduct'];

/** Страница очереди: элементы и курсор следующей страницы (одинаковая для заявок и товаров). */
export interface QueuePage<T> {
  items: T[];
  page: { limit: number; nextCursor?: string | null };
}
