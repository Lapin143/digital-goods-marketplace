import type { TextKey } from '../../i18n';
import type { SellerApplication, SellerApplicationForm } from './types';

export const SELLER_TYPES = ['sole_proprietor', 'self_employed', 'legal_entity'] as const;

/** Допустимые файлы документов и предел размера: те же, что проверяет шлюз и сервис (PDF, JPEG, PNG до 2 МБ). */
export const DOCUMENT_TYPES = ['application/pdf', 'image/jpeg', 'image/png'] as const;
export const DOCUMENT_MAX_BYTES = 2 * 1024 * 1024;
export const DOCUMENTS_MAX = 10;

/** Анкета в том виде, в каком она лежит в полях ввода: всё текстом, пустая строка значит «не заполнено». */
export interface ApplicationValues {
  sellerType: string;
  name: string;
  recipientName: string;
  bankName: string;
  bik: string;
  accountNumber: string;
  assortmentDescription: string;
}

export const EMPTY_APPLICATION: ApplicationValues = {
  sellerType: '',
  name: '',
  recipientName: '',
  bankName: '',
  bik: '',
  accountNumber: '',
  assortmentDescription: '',
};

export type ApplicationErrors = Partial<Record<keyof ApplicationValues | 'documents', TextKey>>;

export function valuesFromApplication(application: SellerApplication | undefined): ApplicationValues {
  if (!application) {
    return EMPTY_APPLICATION;
  }
  return {
    sellerType: application.sellerType ?? '',
    name: application.name ?? '',
    recipientName: application.payoutDetails?.recipientName ?? '',
    bankName: application.payoutDetails?.bankName ?? '',
    bik: application.payoutDetails?.bik ?? '',
    accountNumber: application.payoutDetails?.accountNumber ?? '',
    assortmentDescription: application.assortmentDescription ?? '',
  };
}

/**
 * Анкета для API. Черновик можно сохранить неполным (US-2.1), поэтому в тело попадают только заполненные поля;
 * `PUT` заменяет анкету целиком, значит незаполненное поле отправкой пустого значения не «стирается», а просто отсутствует.
 */
export function toForm(values: ApplicationValues): SellerApplicationForm {
  const form: Record<string, unknown> = {};
  if (values.sellerType !== '') {
    form.sellerType = values.sellerType;
  }
  if (values.name.trim() !== '') {
    form.name = values.name.trim();
  }
  const payout: Record<string, string> = {};
  if (values.recipientName.trim() !== '') {
    payout.recipientName = values.recipientName.trim();
  }
  if (values.bankName.trim() !== '') {
    payout.bankName = values.bankName.trim();
  }
  if (values.bik.trim() !== '') {
    payout.bik = values.bik.trim();
  }
  if (values.accountNumber.trim() !== '') {
    payout.accountNumber = values.accountNumber.trim();
  }
  if (Object.keys(payout).length > 0) {
    form.payoutDetails = payout;
  }
  if (values.assortmentDescription.trim() !== '') {
    form.assortmentDescription = values.assortmentDescription.trim();
  }
  return form as SellerApplicationForm;
}

/** Формат заполненных полей: БИК 9 цифр, счёт 20 цифр. Пустое здесь не ошибка (черновик). */
export function validateDraft(values: ApplicationValues): ApplicationErrors {
  const errors: ApplicationErrors = {};
  if (values.bik.trim() !== '' && !/^\d{9}$/.test(values.bik.trim())) {
    errors.bik = 'application.error.bik';
  }
  if (values.accountNumber.trim() !== '' && !/^\d{20}$/.test(values.accountNumber.trim())) {
    errors.accountNumber = 'application.error.account';
  }
  return errors;
}

/** Полнота анкеты для отправки на проверку: все четыре блока и хотя бы один документ (FT-2.1). */
export function validateSubmit(values: ApplicationValues, documentsCount: number): ApplicationErrors {
  const errors = validateDraft(values);
  const required: Array<keyof ApplicationValues> = ['sellerType', 'name', 'recipientName', 'bankName', 'bik', 'accountNumber', 'assortmentDescription'];
  for (const field of required) {
    if (values[field].trim() === '' && errors[field] === undefined) {
      errors[field] = 'field.required';
    }
  }
  if (documentsCount === 0) {
    errors.documents = 'application.error.documents';
  }
  return errors;
}

/** Имя поля формы по указателю ошибки сервера: `payoutDetails.bik` это поле `bik`. */
export function fieldOfPointer(name: string): keyof ApplicationValues | undefined {
  const last = name.split('.').pop() ?? name;
  return last in EMPTY_APPLICATION ? (last as keyof ApplicationValues) : undefined;
}

export type DocumentCheck = { ok: true } | { ok: false; reason: 'application.error.fileType' | 'application.error.fileSize' | 'application.error.fileLimit' };

/** Проверка файла до загрузки: тип, размер, число документов. Сервер проверит то же самое ещё раз (413, 415, 409). */
export function checkDocument(file: { type: string; size: number }, existing: number): DocumentCheck {
  const known: readonly string[] = DOCUMENT_TYPES;
  if (!known.includes(file.type)) {
    return { ok: false, reason: 'application.error.fileType' };
  }
  if (file.size > DOCUMENT_MAX_BYTES) {
    return { ok: false, reason: 'application.error.fileSize' };
  }
  if (existing >= DOCUMENTS_MAX) {
    return { ok: false, reason: 'application.error.fileLimit' };
  }
  return { ok: true };
}
