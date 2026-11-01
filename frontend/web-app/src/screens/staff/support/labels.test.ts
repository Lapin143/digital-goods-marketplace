import { describe, expect, it } from 'vitest';
import { t } from '../../../i18n';
import { checkLabel, checkTone, deliveryStatusLabel, deliveryStatusTone, emailChangeStatusLabel, isPlausibleEmail, parseOrderNumber, sourceLabel, ticketStatusLabel, ticketStatusTone } from './labels';
import { emailChangeBlockedReason, resendBlockedReason } from './TicketActions';
import type { StaffSupportTicket } from './types';
import { staffTicket } from '../../../test/staffSupport';

describe('номер заказа в поиске очереди (TC-707)', () => {
  it.each([
    ['1042', 1042],
    [' 1042 ', 1042],
    ['1', 1],
    ['123456789012345', 123456789012345],
  ])('«%s» это номер %i', (text, expected) => {
    expect(parseOrderNumber(text)).toBe(expected);
  });

  it.each(['', '   ', '10a2', '-5', '+5', '0', '00', '1.5', '1 042', '1234567890123456', '№1042'])('«%s» не номер', (text) => {
    expect(parseOrderNumber(text)).toBeUndefined();
  });
});

describe('новый адрес в форме смены e-mail (TC-710)', () => {
  it.each(['a@b.co', ' new.address@mail.example ', 'a+tag@sub.mail.example'])('«%s» похож на адрес', (text) => {
    expect(isPlausibleEmail(text)).toBe(true);
  });

  it.each(['', 'без-собаки', 'a@b', '@mail.example', 'a b@mail.example', 'a@@mail.example', `${'a'.repeat(250)}@mail.example`])('«%s» не адрес', (text) => {
    expect(isPlausibleEmail(text)).toBe(false);
  });
});

describe('подписи оператора (TC-704, TC-708)', () => {
  it('статус выдачи по SM-06: известные, пустой и незнакомый', () => {
    expect(deliveryStatusLabel('queued')).toBe(t('supportCard.delivery.queued'));
    expect(deliveryStatusLabel('sent')).toBe(t('supportCard.delivery.sent'));
    expect(deliveryStatusLabel('delivered')).toBe(t('supportCard.delivery.delivered'));
    expect(deliveryStatusLabel('failed')).toBe(t('supportCard.delivery.failed'));
    expect(deliveryStatusLabel(null)).toBe(t('supportCard.delivery.none'));
    expect(deliveryStatusLabel(undefined)).toBe(t('supportCard.delivery.none'));
    expect(deliveryStatusLabel('новый-статус')).toBe(t('supportCard.delivery.unknown'));
  });

  it('цвет статуса выдачи: доставлено хорошо, не доставлено плохо, в пути предупреждение, остальное нейтрально', () => {
    expect(deliveryStatusTone('delivered')).toBe('good');
    expect(deliveryStatusTone('failed')).toBe('bad');
    expect(deliveryStatusTone('queued')).toBe('warn');
    expect(deliveryStatusTone('sent')).toBe('warn');
    expect(deliveryStatusTone(null)).toBe('neutral');
  });

  it('статус обращения и источник словами оператора', () => {
    expect(ticketStatusLabel('created')).toBe(t('supportCard.status.created'));
    expect(ticketStatusLabel('in_progress')).toBe(t('supportCard.status.in_progress'));
    expect(ticketStatusLabel('resolved')).toBe(t('supportCard.status.resolved'));
    expect(ticketStatusLabel('иной')).toBe(t('supportCard.status.unknown'));
    expect(ticketStatusTone('resolved')).toBe('good');
    expect(ticketStatusTone('created')).toBe('warn');
    expect(ticketStatusTone('иной')).toBe('neutral');
    expect(sourceLabel('buyer')).toBe(t('supportCard.source.buyer'));
    expect(sourceLabel('system')).toBe(t('supportCard.source.system'));
    expect(sourceLabel('иной')).toBe(t('supportCard.source.unknown'));
  });

  it('результат проверки кода и этап смены e-mail: только слова, незнакомое значение нейтрально', () => {
    expect(checkLabel('passed')).toBe(t('supportCard.check.passed'));
    expect(checkLabel('failed')).toBe(t('supportCard.check.failed'));
    expect(checkLabel('иной')).toBe(t('supportCard.check.unknown'));
    expect(checkTone('passed')).toBe('good');
    expect(checkTone('failed')).toBe('bad');
    expect(checkTone('pending')).toBe('warn');
    expect(checkTone('not_started')).toBe('neutral');
    expect(emailChangeStatusLabel('awaiting_phone_code')).toBe(t('supportCard.change.awaiting_phone_code'));
    expect(emailChangeStatusLabel('иной')).toBe(t('supportCard.change.unknown'));
  });
});

describe('когда действия закрыты (TC-708, TC-710)', () => {
  const ticket = (overrides: Record<string, unknown>) => staffTicket({ status: 'in_progress', ...overrides }) as unknown as StaffSupportTicket;

  it('повторная отправка закрыта, если заказ не «выдан» или выдача уже «в очереди»; причина названа', () => {
    expect(resendBlockedReason(ticket({}))).toBeUndefined();
    expect(resendBlockedReason(ticket({ deliveryStatus: 'failed' }))).toBeUndefined();
    expect(resendBlockedReason(ticket({ deliveryStatus: null }))).toBeUndefined();
    expect(resendBlockedReason(ticket({ deliveryStatus: 'queued' }))).toBe('supportCard.resend.blocked.queued');
    expect(resendBlockedReason(ticket({ orderStatus: 'refunded' }))).toBe('supportCard.resend.blocked.order');
    expect(resendBlockedReason(ticket({ orderStatus: 'refunded', deliveryStatus: 'queued' }))).toBe('supportCard.resend.blocked.order');
  });

  it('смена e-mail закрыта только после завершения', () => {
    const change = (status: string) => ({ status, phoneCheck: 'passed', newEmailCheck: 'passed', newEmailMasked: 'n***@mail.example', startedAt: 'x', updatedAt: 'x' });
    expect(emailChangeBlockedReason(ticket({ emailChange: null }))).toBeUndefined();
    expect(emailChangeBlockedReason(ticket({ emailChange: change('awaiting_phone_code') }))).toBeUndefined();
    expect(emailChangeBlockedReason(ticket({ emailChange: change('failed') }))).toBeUndefined();
    expect(emailChangeBlockedReason(ticket({ emailChange: change('completed') }))).toBe('supportCard.emailChange.blocked.completed');
  });
});
