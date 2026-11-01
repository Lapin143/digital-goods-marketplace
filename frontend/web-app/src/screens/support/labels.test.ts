import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { isOpenTicket, resolutionText, ticketReasonLabel, ticketSourceLabel, ticketStateText, ticketStatusLabel, ticketStatusTone } from './labels';

describe('TC-701. Подписи обращения', () => {
  it('статусы SM-07 словами и цветом, незнакомый статус нейтрален', () => {
    expect(ticketStatusLabel('created')).toBe(t('ticket.status.created'));
    expect(ticketStatusLabel('in_progress')).toBe(t('ticket.status.in_progress'));
    expect(ticketStatusLabel('resolved')).toBe(t('ticket.status.resolved'));
    expect(ticketStatusLabel('arbitration')).toBe(t('ticket.status.unknown'));
    expect(ticketStatusTone('created')).toBe('warn');
    expect(ticketStatusTone('in_progress')).toBe('warn');
    expect(ticketStatusTone('resolved')).toBe('good');
    expect(ticketStatusTone('arbitration')).toBe('neutral');
    expect(ticketStateText('in_progress')).toBe(t('ticket.state.in_progress'));
    expect(ticketStateText('arbitration')).toBe(t('ticket.state.unknown'));
  });

  it('причины и источники из перечислений контракта, открытое перечисление не ломает экран', () => {
    for (const reason of ['key_not_received', 'not_delivered', 'attempts_exhausted', 'delivery_overdue']) {
      expect(ticketReasonLabel(reason)).not.toBe(t('ticket.reason.unknown'));
    }
    expect(ticketReasonLabel('new_reason')).toBe(t('ticket.reason.unknown'));
    expect(ticketSourceLabel('buyer')).toBe(t('ticket.source.buyer'));
    expect(ticketSourceLabel('system')).toBe(t('ticket.source.system'));
    expect(ticketSourceLabel('partner')).toBe(t('ticket.source.unknown'));
  });

  it('итог решённого обращения: кто решил и что сделано', () => {
    expect(resolutionText({ resolvedBy: 'system', action: null })).toBe(t('ticket.resolution.system.none'));
    expect(resolutionText({ resolvedBy: 'system', action: 'resend' })).toBe(t('ticket.resolution.system.resend'));
    expect(resolutionText({ resolvedBy: 'operator', action: 'resend' })).toBe(t('ticket.resolution.operator.resend'));
    expect(resolutionText({ resolvedBy: 'operator', action: 'email_change' })).toBe(t('ticket.resolution.operator.email_change'));
    expect(resolutionText({ resolvedBy: 'system', action: 'email_change' })).toBe(t('ticket.resolution.system.email_change'));
    expect(resolutionText(null)).toBe(t('ticket.resolution.unknown'));
  });

  it('опрос идёт, пока обращение не решено', () => {
    expect(isOpenTicket('created')).toBe(true);
    expect(isOpenTicket('in_progress')).toBe(true);
    expect(isOpenTicket('resolved')).toBe(false);
  });
});
