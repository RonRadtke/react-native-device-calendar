import NativeDeviceCalendar from './NativeReactNativeDeviceCalendar';
import {
  attendeeFromNative,
  calendarFromNative,
  checkColor,
  eventFromNative,
  eventToNative,
  mergeChanges,
} from './convert';
import type {
  Attendee,
  Calendar,
  CalendarChanges,
  CalendarEvent,
  CalendarOptions,
  DialogAction,
  DialogEventDetails,
  DialogResult,
  EventChanges,
  EventDetails,
  EventsQuery,
  OpenEventOptions,
  PermissionOptions,
  PermissionStatus,
  RecurringEventOptions,
  Source,
} from './types';

export * from './types';
export { allDayRange } from './convert';

/** An error of the platform; `code` names what went wrong (see the README) */
export type CalendarError = Error & { code: string };

function rejection(code: string, message: string): CalendarError {
  return Object.assign(new Error(message), { code });
}

/** The span and occurrence of a call on a recurring event */
function recurring(options: RecurringEventOptions | undefined): {
  span: string;
  occurrence: number | null;
} {
  if (options?.occurrence === undefined) {
    if (options?.futureEvents) {
      throw new RangeError('futureEvents needs the occurrence they start at');
    }
    return { span: 'all', occurrence: null };
  }
  if (Number.isNaN(options.occurrence.getTime())) {
    throw new RangeError('occurrence must be a valid date');
  }
  return {
    span: options.futureEvents ? 'future' : 'this',
    occurrence: options.occurrence.getTime(),
  };
}

// Permission

/** Whether the app may use the calendars, without asking */
export async function getPermissionStatus(): Promise<PermissionStatus> {
  return (await NativeDeviceCalendar.getPermissionStatus()) as PermissionStatus;
}

/** Asks the person for access to the calendars, unless the app has it or may no longer ask */
export async function requestPermission(
  options?: PermissionOptions
): Promise<PermissionStatus> {
  return (await NativeDeviceCalendar.requestPermission(
    options?.writeOnly === true
  )) as PermissionStatus;
}

// Sources and calendars

/** The accounts that hold calendars */
export function getSources(): Promise<Source[]> {
  return NativeDeviceCalendar.getSources();
}

/** Every calendar of the device that holds events */
export async function getCalendars(): Promise<Calendar[]> {
  return (await NativeDeviceCalendar.getCalendars()).map(calendarFromNative);
}

/** iOS: the calendar new events go into by default. Android has no such calendar and answers null; see {@link Calendar.isPrimary} */
export async function getDefaultCalendar(): Promise<Calendar | null> {
  const calendar = await NativeDeviceCalendar.getDefaultCalendar();
  return calendar ? calendarFromNative(calendar) : null;
}

/** Creates a calendar of the app's own and returns its id */
export async function createCalendar(
  options: CalendarOptions
): Promise<string> {
  checkColor(options.color);
  return NativeDeviceCalendar.createCalendar({
    title: options.title,
    color: options.color,
    sourceId: options.sourceId ?? null,
    accountName: options.accountName ?? null,
    timeZone: options.timeZone ?? null,
  });
}

/** Renames or recolours a calendar */
export async function updateCalendar(
  id: string,
  changes: CalendarChanges
): Promise<void> {
  if (changes.color !== undefined) checkColor(changes.color);
  return NativeDeviceCalendar.updateCalendar(id, {
    title: changes.title ?? null,
    color: changes.color ?? null,
  });
}

/**
 * Deletes a calendar with all its events. Returns false when there is no such calendar, or on Android when it is not one of a local
 * account: a calendar of a Google or Exchange account belongs to that account.
 */
export function deleteCalendar(id: string): Promise<boolean> {
  return NativeDeviceCalendar.deleteCalendar(id);
}

// Events

/** The events that overlap the period, a recurring event once per occurrence */
export async function getEvents(query: EventsQuery): Promise<CalendarEvent[]> {
  if (!(query.endDate > query.startDate)) {
    throw new RangeError('endDate must be after startDate');
  }
  const events = await NativeDeviceCalendar.getEvents(
    query.startDate.getTime(),
    query.endDate.getTime(),
    query.calendarIds ?? []
  );
  return events.map(eventFromNative);
}

/** An event, or one occurrence of a recurring event; null when there is none */
export async function getEvent(
  id: string,
  options?: Pick<RecurringEventOptions, 'occurrence'>
): Promise<CalendarEvent | null> {
  const { occurrence } = recurring({ occurrence: options?.occurrence });
  const event = await NativeDeviceCalendar.getEvent(id, occurrence);
  return event ? eventFromNative(event) : null;
}

/** Adds an event and returns its id */
export async function createEvent(event: EventDetails): Promise<string> {
  return NativeDeviceCalendar.createEvent(eventToNative(event));
}

/**
 * Changes an event: what `changes` leaves out stays. Returns the id of the changed event, which differs from `id` when one occurrence of
 * a recurring event on Android becomes an event of its own. The calendar stays: to move an event, delete it and create it in the other
 * calendar.
 */
export async function updateEvent(
  id: string,
  changes: EventChanges,
  options?: RecurringEventOptions
): Promise<string> {
  const { span, occurrence } = recurring(options);
  if (span === 'this' && changes.recurrenceRule !== undefined) {
    throw new RangeError(
      'a single occurrence has no rule of its own: change the whole event or the future events'
    );
  }
  const current = await NativeDeviceCalendar.getEvent(id, occurrence);
  if (!current) {
    throw rejection('E_NOT_FOUND', `There is no event ${id}`);
  }
  const merged = mergeChanges(eventFromNative(current), changes);
  if (span === 'this') delete merged.recurrenceRule;
  return NativeDeviceCalendar.updateEvent(
    id,
    eventToNative(merged),
    span,
    occurrence
  );
}

/** Deletes an event, by default with every occurrence. Returns false when there is no such event */
export async function deleteEvent(
  id: string,
  options?: RecurringEventOptions
): Promise<boolean> {
  const { span, occurrence } = recurring(options);
  return NativeDeviceCalendar.deleteEvent(id, span, occurrence);
}

/** The people invited to an event. Apps cannot invite people through EventKit, so the library only reads them */
export async function getAttendees(
  id: string,
  options?: Pick<RecurringEventOptions, 'occurrence'>
): Promise<Attendee[]> {
  const { occurrence } = recurring({ occurrence: options?.occurrence });
  return (await NativeDeviceCalendar.getAttendees(id, occurrence)).map(
    attendeeFromNative
  );
}

// The system's calendar screens

/**
 * Shows an event in the system's event view. iOS resolves when the person closes it; Android opens the calendar app and resolves
 * with `done` at once.
 */
export async function openEvent(
  id: string,
  options?: OpenEventOptions
): Promise<DialogAction> {
  const { occurrence } = recurring({ occurrence: options?.occurrence });
  return (await NativeDeviceCalendar.openEvent(
    id,
    occurrence,
    options?.allowsEditing === true
  )) as DialogAction;
}

/**
 * Lets the person create an event in the system's editor, starting with `details`. Needs no permission on Android, and on iOS 17 and
 * later none either. iOS tells whether the event was saved and its id; Android only that the editor opened.
 */
export async function createEventWithDialog(
  details: DialogEventDetails = {}
): Promise<DialogResult> {
  const result = await NativeDeviceCalendar.createEventWithDialog({
    calendarId: details.calendarId ?? null,
    title: details.title ?? null,
    startDate: details.startDate ? details.startDate.getTime() : null,
    endDate: details.endDate ? details.endDate.getTime() : null,
    allDay: details.allDay ?? null,
    location: details.location ?? null,
    notes: details.notes ?? null,
    url: details.url ?? null,
  });
  return result.id
    ? { action: result.action as DialogAction, id: result.id }
    : { action: result.action as DialogAction };
}

/** Lets the person change an event in the system's editor */
export async function editEventWithDialog(
  id: string,
  options?: Pick<RecurringEventOptions, 'occurrence'>
): Promise<DialogResult> {
  const { occurrence } = recurring({ occurrence: options?.occurrence });
  const result = await NativeDeviceCalendar.editEventWithDialog(id, occurrence);
  return result.id
    ? { action: result.action as DialogAction, id: result.id }
    : { action: result.action as DialogAction };
}
