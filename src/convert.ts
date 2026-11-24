import type {
  NativeAlarm,
  NativeAttendee,
  NativeCalendar,
  NativeEvent,
  NativeEventInput,
  NativeRecurrenceRule,
} from './NativeReactNativeDeviceCalendar';
import type {
  Alarm,
  AlarmMethod,
  Attendee,
  AttendeeRole,
  AttendeeStatus,
  AttendeeType,
  Availability,
  Calendar,
  CalendarEvent,
  DayOfTheWeek,
  EventChanges,
  EventDetails,
  EventStatus,
  Frequency,
  RecurrenceRule,
} from './types';

const DAYS: readonly DayOfTheWeek[] = [
  'sunday',
  'monday',
  'tuesday',
  'wednesday',
  'thursday',
  'friday',
  'saturday',
];
const FREQUENCIES: readonly Frequency[] = [
  'daily',
  'weekly',
  'monthly',
  'yearly',
];
const AVAILABILITIES: readonly Availability[] = [
  'busy',
  'free',
  'tentative',
  'unavailable',
  'notSupported',
];
const ALARM_METHODS: readonly AlarmMethod[] = [
  'default',
  'alert',
  'email',
  'sms',
];

/** One of the allowed values, or the fallback for a value a newer platform may bring */
function oneOf<T extends string>(
  value: string,
  allowed: readonly T[],
  fallback: T
): T {
  return (allowed as readonly string[]).includes(value)
    ? (value as T)
    : fallback;
}

function midnight(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function nextMidnight(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + 1);
}

function isDate(value: Date): boolean {
  return value instanceof Date && !Number.isNaN(value.getTime());
}

/** The local midnights an all-day event runs between, the end exclusive and at least one day after the start */
export function allDayRange(
  startDate: Date,
  endDate: Date
): { start: Date; end: Date } {
  const start = midnight(startDate);
  let end = midnight(endDate);
  if (end < endDate) end = nextMidnight(endDate);
  if (end <= start) end = nextMidnight(start);
  return { start, end };
}

export function checkColor(color: string): void {
  if (!/^#[0-9a-fA-F]{6}$/.test(color)) {
    throw new RangeError(`color must be #RRGGBB, not ${color}`);
  }
}

function checkInteger(
  values: readonly number[] | undefined,
  name: string,
  limit: number
): number[] {
  for (const value of values ?? []) {
    if (!Number.isInteger(value) || value === 0 || Math.abs(value) > limit) {
      throw new RangeError(
        `${name} must hold whole numbers from 1 to ${limit} or -1 to -${limit}`
      );
    }
  }
  return [...(values ?? [])];
}

export function ruleToNative(rule: RecurrenceRule): NativeRecurrenceRule {
  if (!FREQUENCIES.includes(rule.frequency)) {
    throw new RangeError(`frequency must be one of ${FREQUENCIES.join(', ')}`);
  }
  const interval = rule.interval ?? 1;
  if (!Number.isInteger(interval) || interval < 1) {
    throw new RangeError('interval must be a whole number of 1 or more');
  }
  if (rule.endDate !== undefined && rule.occurrence !== undefined) {
    throw new RangeError('a rule ends by endDate or by occurrence, not both');
  }
  if (rule.endDate !== undefined && !isDate(rule.endDate)) {
    throw new RangeError('endDate must be a valid date');
  }
  if (
    rule.occurrence !== undefined &&
    (!Number.isInteger(rule.occurrence) || rule.occurrence < 1)
  ) {
    throw new RangeError('occurrence must be a whole number of 1 or more');
  }
  const positional =
    rule.frequency === 'monthly' || rule.frequency === 'yearly';
  const daysOfTheWeek = (rule.daysOfTheWeek ?? []).map(
    ({ day, weekNumber }) => {
      if (!DAYS.includes(day)) {
        throw new RangeError(`day must be one of ${DAYS.join(', ')}`);
      }
      const number = weekNumber ?? 0;
      if (number !== 0 && !positional) {
        throw new RangeError('weekNumber needs a monthly or yearly rule');
      }
      if (!Number.isInteger(number) || Math.abs(number) > 53) {
        throw new RangeError('weekNumber must be from -53 to 53');
      }
      return { day: DAYS.indexOf(day) + 1, weekNumber: number };
    }
  );
  const monthsOfTheYear = [...(rule.monthsOfTheYear ?? [])];
  for (const month of monthsOfTheYear) {
    if (!Number.isInteger(month) || month < 1 || month > 12) {
      throw new RangeError('monthsOfTheYear must hold 1 to 12');
    }
  }
  return {
    frequency: rule.frequency,
    interval,
    endDate: rule.endDate ? rule.endDate.getTime() : null,
    occurrence: rule.occurrence ?? null,
    daysOfTheWeek,
    daysOfTheMonth: checkInteger(rule.daysOfTheMonth, 'daysOfTheMonth', 31),
    monthsOfTheYear,
    weeksOfTheYear: checkInteger(rule.weeksOfTheYear, 'weeksOfTheYear', 53),
    daysOfTheYear: checkInteger(rule.daysOfTheYear, 'daysOfTheYear', 366),
    setPositions: checkInteger(rule.setPositions, 'setPositions', 366),
  };
}

export function ruleFromNative(rule: NativeRecurrenceRule): RecurrenceRule {
  const result: RecurrenceRule = {
    frequency: oneOf(rule.frequency, FREQUENCIES, 'daily'),
    interval: rule.interval,
  };
  if (rule.endDate !== null) result.endDate = new Date(rule.endDate);
  if (rule.occurrence !== null) result.occurrence = rule.occurrence;
  // other apps write ordinals into daily and weekly rules, where RFC 5545 gives them no meaning; leaving them out keeps the
  // rule writable when the event is changed
  const positional =
    result.frequency === 'monthly' || result.frequency === 'yearly';
  if (rule.daysOfTheWeek.length > 0) {
    result.daysOfTheWeek = rule.daysOfTheWeek.map(({ day, weekNumber }) =>
      weekNumber === 0 || !positional
        ? { day: DAYS[day - 1] ?? 'sunday' }
        : { day: DAYS[day - 1] ?? 'sunday', weekNumber }
    );
  }
  if (rule.daysOfTheMonth.length > 0) {
    result.daysOfTheMonth = [...rule.daysOfTheMonth];
  }
  if (rule.monthsOfTheYear.length > 0) {
    result.monthsOfTheYear = [...rule.monthsOfTheYear];
  }
  if (rule.weeksOfTheYear.length > 0) {
    result.weeksOfTheYear = [...rule.weeksOfTheYear];
  }
  if (rule.daysOfTheYear.length > 0) {
    result.daysOfTheYear = [...rule.daysOfTheYear];
  }
  if (rule.setPositions.length > 0) {
    result.setPositions = [...rule.setPositions];
  }
  return result;
}

function alarmsToNative(alarms: readonly Alarm[] | undefined): NativeAlarm[] {
  return (alarms ?? []).map((alarm) => {
    if (!Number.isFinite(alarm.relativeOffset)) {
      throw new RangeError('relativeOffset must be a number of minutes');
    }
    if (alarm.method !== undefined && !ALARM_METHODS.includes(alarm.method)) {
      throw new RangeError(`method must be one of ${ALARM_METHODS.join(', ')}`);
    }
    return {
      relativeOffset: Math.round(alarm.relativeOffset),
      method: alarm.method ?? null,
    };
  });
}

export function eventToNative(event: EventDetails): NativeEventInput {
  if (!isDate(event.startDate) || !isDate(event.endDate)) {
    throw new RangeError('startDate and endDate must be valid dates');
  }
  if (event.endDate < event.startDate) {
    throw new RangeError('endDate must not be before startDate');
  }
  if (
    event.availability !== undefined &&
    !AVAILABILITIES.includes(event.availability)
  ) {
    throw new RangeError(
      `availability must be one of ${AVAILABILITIES.join(', ')}`
    );
  }
  const allDay = event.allDay === true;
  const range = allDay
    ? allDayRange(event.startDate, event.endDate)
    : { start: event.startDate, end: event.endDate };
  return {
    calendarId: event.calendarId,
    title: event.title,
    startDate: range.start.getTime(),
    endDate: range.end.getTime(),
    allDay,
    location: event.location ?? null,
    notes: event.notes ?? null,
    url: event.url ?? null,
    timeZone: allDay ? null : (event.timeZone ?? null),
    availability: event.availability ?? null,
    alarms: alarmsToNative(event.alarms),
    recurrenceRule: event.recurrenceRule
      ? ruleToNative(event.recurrenceRule)
      : null,
  };
}

/** The event as it is to be after the changes: what the changes leave out stays as it is */
export function mergeChanges(
  event: CalendarEvent,
  changes: EventChanges
): EventDetails {
  const merged: EventDetails = {
    calendarId: event.calendarId,
    title: changes.title ?? event.title,
    startDate: changes.startDate ?? event.startDate,
    endDate: changes.endDate ?? event.endDate,
    allDay: changes.allDay ?? event.allDay,
    availability: changes.availability ?? event.availability,
    alarms: changes.alarms ?? event.alarms,
  };
  const optional = <T>(
    change: T | null | undefined,
    current: T | undefined
  ): T | undefined => (change === undefined ? current : (change ?? undefined));
  const location = optional(changes.location, event.location);
  const notes = optional(changes.notes, event.notes);
  const url = optional(changes.url, event.url);
  const timeZone = optional(changes.timeZone, event.timeZone);
  const recurrenceRule = optional(changes.recurrenceRule, event.recurrenceRule);
  if (location !== undefined) merged.location = location;
  if (notes !== undefined) merged.notes = notes;
  if (url !== undefined) merged.url = url;
  if (timeZone !== undefined) merged.timeZone = timeZone;
  if (recurrenceRule !== undefined) merged.recurrenceRule = recurrenceRule;
  return merged;
}

export function attendeeFromNative(attendee: NativeAttendee): Attendee {
  const result: Attendee = {
    role: attendee.role as AttendeeRole,
    status: attendee.status as AttendeeStatus,
    type: attendee.type as AttendeeType,
    isCurrentUser: attendee.isCurrentUser,
  };
  if (attendee.name) result.name = attendee.name;
  if (attendee.email) result.email = attendee.email;
  return result;
}

export function calendarFromNative(calendar: NativeCalendar): Calendar {
  const result: Calendar = {
    id: calendar.id,
    title: calendar.title,
    color: calendar.color,
    sourceId: calendar.sourceId,
    source: calendar.source,
    sourceType: calendar.sourceType,
    isPrimary: calendar.isPrimary,
    allowsModifications: calendar.allowsModifications,
    isVisible: calendar.isVisible,
    allowedAvailabilities: calendar.allowedAvailabilities.map((value) =>
      oneOf(value, AVAILABILITIES, 'notSupported')
    ),
  };
  if (calendar.timeZone) result.timeZone = calendar.timeZone;
  return result;
}

export function eventFromNative(event: NativeEvent): CalendarEvent {
  const result: CalendarEvent = {
    id: event.id,
    calendarId: event.calendarId,
    title: event.title,
    startDate: new Date(event.startDate),
    endDate: new Date(event.endDate),
    allDay: event.allDay,
    availability: oneOf(event.availability, AVAILABILITIES, 'notSupported'),
    status: oneOf<EventStatus>(
      event.status,
      ['none', 'confirmed', 'tentative', 'canceled'],
      'none'
    ),
    alarms: event.alarms.map((alarm) =>
      alarm.method === null
        ? { relativeOffset: alarm.relativeOffset }
        : {
            relativeOffset: alarm.relativeOffset,
            method: oneOf(alarm.method, ALARM_METHODS, 'default'),
          }
    ),
    isDetached: event.isDetached,
  };
  if (event.location) result.location = event.location;
  if (event.notes) result.notes = event.notes;
  if (event.url) result.url = event.url;
  if (event.timeZone) result.timeZone = event.timeZone;
  if (event.recurrenceRule) {
    result.recurrenceRule = ruleFromNative(event.recurrenceRule);
  }
  if (event.originalStartDate !== null) {
    result.originalStartDate = new Date(event.originalStartDate);
  }
  if (event.organizer) result.organizer = attendeeFromNative(event.organizer);
  if (event.creationDate !== null) {
    result.creationDate = new Date(event.creationDate);
  }
  if (event.lastModifiedDate !== null) {
    result.lastModifiedDate = new Date(event.lastModifiedDate);
  }
  return result;
}
