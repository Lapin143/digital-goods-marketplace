import type { components as CatalogComponents } from '../../api/generated/catalog';

// Типы берутся из OpenAPI (npm run gen:api): расхождение интерфейса и контракта ломает проверку типов
export type StorefrontPage = CatalogComponents['schemas']['StorefrontPage'];
export type StorefrontItem = CatalogComponents['schemas']['StorefrontItem'];
export type StorefrontProduct = CatalogComponents['schemas']['StorefrontProduct'];
