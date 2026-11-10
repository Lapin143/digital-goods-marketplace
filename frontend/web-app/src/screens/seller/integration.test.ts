import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { ApiError } from '../../api/problem';
import { deliveryStatusLabel, deliveryStatusTone, deliveryTypeLabel, integrationStatusLabel, integrationStatusTone, isEndpointRejected, validateEndpoint } from './integration';

describe('адрес подключения', () => {
  it('принимает https-адрес с путём и портом', () => {
    expect(validateEndpoint('https://shop.example/dgm')).toBeUndefined();
    expect(validateEndpoint('  HTTPS://shop.example:8443/dgm  ')).toBeUndefined();
  });

  it('пустой адрес, http и адрес без схемы отклоняются до запроса', () => {
    expect(validateEndpoint('')).toBe('field.required');
    expect(validateEndpoint('http://shop.example')).toBe('integration.error.https');
    expect(validateEndpoint('shop.example/dgm')).toBe('integration.error.https');
    expect(validateEndpoint('https://')).toBe('integration.error.https');
    expect(validateEndpoint('https://shop .example')).toBe('integration.error.https');
  });

  it('длиннее 2048 знаков отклоняется', () => {
    expect(validateEndpoint(`https://shop.example/${'a'.repeat(2048)}`)).toBe('field.too_long');
  });
});

describe('отказ платформы по адресу', () => {
  it('распознаёт только 422 endpoint-not-allowed', () => {
    expect(isEndpointRejected(new ApiError({ status: 422, code: 'endpoint-not-allowed' }))).toBe(true);
    expect(isEndpointRejected(new ApiError({ status: 422, code: 'validation-failed' }))).toBe(false);
    expect(isEndpointRejected(new ApiError({ status: 403, code: 'endpoint-not-allowed' }))).toBe(false);
    expect(isEndpointRejected(new Error('x'))).toBe(false);
  });
});

describe('подписи', () => {
  it('статус подключения', () => {
    expect(integrationStatusLabel('active')).toBe(t('integration.status.active'));
    expect(integrationStatusLabel('disabled')).toBe(t('integration.status.disabled'));
    expect(integrationStatusLabel('новый')).toBe('новый');
    expect(integrationStatusTone('active')).toBe('good');
    expect(integrationStatusTone('disabled')).toBe('neutral');
  });

  it('статус и тип уведомления; незнакомые значения показываются как есть', () => {
    expect(deliveryStatusLabel('failed')).toBe(t('integration.delivery.status.failed'));
    expect(deliveryStatusLabel('queued')).toBe(t('integration.delivery.status.queued'));
    expect(deliveryStatusLabel('новый')).toBe('новый');
    expect(deliveryStatusTone('sent')).toBe('good');
    expect(deliveryStatusTone('queued')).toBe('warn');
    expect(deliveryStatusTone('failed')).toBe('bad');
    expect(deliveryStatusTone('skipped')).toBe('neutral');
    expect(deliveryTypeLabel('order.paid')).toBe(t('integration.delivery.type.order.paid'));
    expect(deliveryTypeLabel('order.issued')).toBe(t('integration.delivery.type.order.issued'));
    expect(deliveryTypeLabel('order.new')).toBe('order.new');
  });
});
