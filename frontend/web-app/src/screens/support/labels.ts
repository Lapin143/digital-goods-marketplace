import { hasText, t } from '../../i18n';
import type { SupportTicket } from './types';

type Tone = 'neutral' | 'good' | 'warn' | 'bad';

/** Статус обращения по SM-07 словами покупателя. Незнакомый статус (перечисление может вырасти) показывается нейтрально. */
export function ticketStatusLabel(status: string): string {
  const key = `ticket.status.${status}`;
  return hasText(key) ? t(key) : t('ticket.status.unknown');
}

export function ticketStatusTone(status: string): Tone {
  return status === 'resolved' ? 'good' : status === 'created' || status === 'in_progress' ? 'warn' : 'neutral';
}

/** Что сказать покупателю о ходе обращения. */
export function ticketStateText(status: string): string {
  const key = `ticket.state.${status}`;
  return hasText(key) ? t(key) : t('ticket.state.unknown');
}

/** Причина обращения (перечисление открытое): незнакомая причина получает общий текст. */
export function ticketReasonLabel(reason: string): string {
  const key = `ticket.reason.${reason}`;
  return hasText(key) ? t(key) : t('ticket.reason.unknown');
}

export function ticketSourceLabel(source: string): string {
  const key = `ticket.source.${source}`;
  return hasText(key) ? t(key) : t('ticket.source.unknown');
}

/** Итог решённого обращения: кто решил и что было сделано. Решено системой без действия значит «письмо доставлено». */
export function resolutionText(resolution: SupportTicket['resolution']): string {
  if (!resolution) {
    return t('ticket.resolution.unknown');
  }
  const key = `ticket.resolution.${resolution.resolvedBy}.${resolution.action ?? 'none'}`;
  return hasText(key) ? t(key) : t('ticket.resolution.unknown');
}

/** Обращение, по которому покупателю ещё что-то делает поддержка: опрос статуса идёт, пока оно не решено. */
export function isOpenTicket(status: string): boolean {
  return status !== 'resolved';
}
