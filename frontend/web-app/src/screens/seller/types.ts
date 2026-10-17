import type { components as CatalogComponents } from '../../api/generated/catalog';
import type { components as InventoryComponents } from '../../api/generated/inventory';

export type SellerApplication = CatalogComponents['schemas']['SellerApplication'];
export type SellerApplicationForm = CatalogComponents['schemas']['SellerApplicationForm'];
export type SellerDocument = CatalogComponents['schemas']['SellerDocument'];
export type DocumentLink = CatalogComponents['schemas']['DocumentLink'];
export type ProductInput = CatalogComponents['schemas']['ProductInput'];
export type SellerProduct = CatalogComponents['schemas']['SellerProduct'];
export type SellerProductPage = CatalogComponents['schemas']['SellerProductPage'];
export type StockInfo = InventoryComponents['schemas']['StockInfo'];
export type KeyUploadReport = InventoryComponents['schemas']['KeyUploadReport'];
