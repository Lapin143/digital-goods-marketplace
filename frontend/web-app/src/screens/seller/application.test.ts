import { describe, expect, it } from 'vitest';
import {
  DOCUMENTS_MAX,
  DOCUMENT_MAX_BYTES,
  EMPTY_APPLICATION,
  checkDocument,
  fieldOfPointer,
  toForm,
  validateDraft,
  validateSubmit,
  valuesFromApplication,
} from './application';
import type { ApplicationValues } from './application';
import type { SellerApplication } from './types';

const FULL: ApplicationValues = {
  sellerType: 'sole_proprietor',
  name: ' ИП Иванов ',
  recipientName: 'Иванов Иван Иванович',
  bankName: 'Банк',
  bik: '044525225',
  accountNumber: '40702810400000000001',
  assortmentDescription: 'Ключи для игр',
};

describe('анкета продавца: тело запроса (TC-221)', () => {
  it('пустая анкета даёт пустое тело, черновик можно сохранить неполным', () => {
    expect(toForm(EMPTY_APPLICATION)).toEqual({});
  });

  it('пробелы по краям убираются, реквизиты собираются в payoutDetails', () => {
    expect(toForm(FULL)).toEqual({
      sellerType: 'sole_proprietor',
      name: 'ИП Иванов',
      payoutDetails: { recipientName: 'Иванов Иван Иванович', bankName: 'Банк', bik: '044525225', accountNumber: '40702810400000000001' },
      assortmentDescription: 'Ключи для игр',
    });
  });

  it('незаполненное поле реквизитов в тело не попадает', () => {
    const form = toForm({ ...EMPTY_APPLICATION, bik: '044525225' });
    expect(form).toEqual({ payoutDetails: { bik: '044525225' } });
  });

  it('значения из заявки возвращаются в поля, отсутствующее становится пустой строкой', () => {
    const application = {
      status: 'draft',
      sellerType: 'self_employed',
      name: 'Самозанятый',
      payoutDetails: { recipientName: 'П', bankName: 'Б', bik: '044525225', accountNumber: '40702810400000000001' },
      documents: [],
    } as unknown as SellerApplication;
    expect(valuesFromApplication(application)).toEqual({
      sellerType: 'self_employed',
      name: 'Самозанятый',
      recipientName: 'П',
      bankName: 'Б',
      bik: '044525225',
      accountNumber: '40702810400000000001',
      assortmentDescription: '',
    });
    expect(valuesFromApplication(undefined)).toEqual(EMPTY_APPLICATION);
  });
});

describe('анкета продавца: проверки до запроса (TC-221)', () => {
  it('черновик: пустое не ошибка, неверный формат БИК и счёта ошибка', () => {
    expect(validateDraft(EMPTY_APPLICATION)).toEqual({});
    expect(validateDraft({ ...EMPTY_APPLICATION, bik: '1234' }).bik).toBe('application.error.bik');
    expect(validateDraft({ ...EMPTY_APPLICATION, bik: '04452522a' }).bik).toBe('application.error.bik');
    expect(validateDraft({ ...EMPTY_APPLICATION, accountNumber: '1'.repeat(19) }).accountNumber).toBe('application.error.account');
    expect(validateDraft({ ...EMPTY_APPLICATION, accountNumber: '1'.repeat(21) }).accountNumber).toBe('application.error.account');
    expect(validateDraft(FULL)).toEqual({});
  });

  it('отправка: все поля и хотя бы один документ', () => {
    expect(validateSubmit(FULL, 1)).toEqual({});
    const empty = validateSubmit(EMPTY_APPLICATION, 0);
    expect(Object.keys(empty).sort()).toEqual(['accountNumber', 'assortmentDescription', 'bankName', 'bik', 'documents', 'name', 'recipientName', 'sellerType']);
    expect(empty.name).toBe('field.required');
    expect(empty.documents).toBe('application.error.documents');
  });

  it('отправка: у поля с неверным форматом остаётся ошибка формата, а не «обязательное»', () => {
    expect(validateSubmit({ ...FULL, bik: '12' }, 1).bik).toBe('application.error.bik');
  });

  it('пробелы вместо значения считаются пустым полем', () => {
    expect(validateSubmit({ ...FULL, name: '   ' }, 1).name).toBe('field.required');
  });
});

describe('указатель ошибки сервера (TC-221)', () => {
  it('имя поля берётся по последнему сегменту', () => {
    expect(fieldOfPointer('payoutDetails.bik')).toBe('bik');
    expect(fieldOfPointer('name')).toBe('name');
    expect(fieldOfPointer('payoutDetails.accountNumber')).toBe('accountNumber');
  });

  it('незнакомое поле даёт undefined', () => {
    expect(fieldOfPointer('documents')).toBeUndefined();
    expect(fieldOfPointer('payoutDetails.unknown')).toBeUndefined();
  });
});

describe('проверка документа до загрузки (TC-221)', () => {
  it('PDF, JPEG и PNG до 2 МБ проходят', () => {
    for (const type of ['application/pdf', 'image/jpeg', 'image/png']) {
      expect(checkDocument({ type, size: DOCUMENT_MAX_BYTES }, 0)).toEqual({ ok: true });
    }
  });

  it('другой тип отвергается', () => {
    expect(checkDocument({ type: 'image/gif', size: 10 }, 0)).toEqual({ ok: false, reason: 'application.error.fileType' });
    expect(checkDocument({ type: '', size: 10 }, 0)).toEqual({ ok: false, reason: 'application.error.fileType' });
  });

  it('больше 2 МБ отвергается', () => {
    expect(checkDocument({ type: 'application/pdf', size: DOCUMENT_MAX_BYTES + 1 }, 0)).toEqual({ ok: false, reason: 'application.error.fileSize' });
  });

  it('одиннадцатый документ отвергается', () => {
    expect(checkDocument({ type: 'application/pdf', size: 1 }, DOCUMENTS_MAX - 1)).toEqual({ ok: true });
    expect(checkDocument({ type: 'application/pdf', size: 1 }, DOCUMENTS_MAX)).toEqual({ ok: false, reason: 'application.error.fileLimit' });
  });
});
