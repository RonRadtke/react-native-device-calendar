import NativeDeviceCalendar, {
  type NativeEvent,
  type NativeEventInput,
} from './NativeReactNativeDeviceCalendar';

/**
 * Whether the app may read and write the calendars.
 *
 * - `granted`: full access
 * - `undetermined`: the person has not been asked yet; on Android also after a refusal, since Android tells the two apart only by asking
 * - `denied`: refused, or on iOS 17 and later write-only access, which cannot read events
 * - `restricted`: iOS only, blocked by parental controls or a device management profile
 */
export type PermissionStatus =
  'granted' | 'denied' | 'undetermined' | 'restricted';

export type Calendar = {
  id: string;
  title: string;
  /** `#RRGGBB` */
  color: string;
  /** The account the calendar belongs to, such as an e-mail address, "iCloud" or the name of a local account */
  source: string;
  /** Android: the account type, such as `com.google` or `LOCAL`; iOS: `local`, `exchange`, `caldav`, `mobileme`, `subscribed` or `birthdays` */
  sourceType: string;
  /** Android: the primary calendar of its account; iOS: the default calendar for new events */
  isPrimary: boolean;
  /** Whether the app may add, change and delete events in it */
  allowsModifications: boolean;
};

export type CalendarOptions = {
  title: string;
  /** `#RRGGBB` */
  color: string;
  /**
   * Android only: the name of the local account that holds the calendar. Android lets an app create calendars only in a local account, so
   * the calendar stays on the device. Defaults to the app's name. iOS puts the calendar into iCloud when the person uses it, else onto the
   * device.
   */
  accountName?: string;
};

export type CalendarEvent = {
  /** The same for every occurrence of a recurring event */
  id: string;
  calendarId: string;
  title: string;
  /** All-day events start at local midnight of their first day */
  startDate: Date;
  /** All-day events end at local midnight after their last day */
  endDate: Date;
  allDay: boolean;
  location?: string;
  notes?: string;
};

export type EventInput = {
  calendarId: string;
  title: string;
  startDate: Date;
  /**
   * The end, exclusive. An all-day event covers every local day from the day of `startDate` up to `endDate`: from 19 August 00:00 to 21
   * August 00:00 are the 19th and the 20th. An end that is not a midnight counts its day in.
   */
  endDate: Date;
  allDay?: boolean;
  location?: string;
  notes?: string;
};

export type EventsQuery = {
  startDate: Date;
  endDate: Date;
  /** Only events of these calendars; all calendars when left out */
  calendarIds?: string[];
};

function midnight(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

/** The local midnights an all-day event runs between, the end exclusive and at least one day after the start */
export function allDayRange(
  startDate: Date,
  endDate: Date
): { start: Date; end: Date } {
  const start = midnight(startDate);
  let end = midnight(endDate);
  if (end < endDate)
    end = new Date(end.getFullYear(), end.getMonth(), end.getDate() + 1);
  if (end <= start)
    end = new Date(start.getFullYear(), start.getMonth(), start.getDate() + 1);
  return { start, end };
}

function toNative(event: EventInput): NativeEventInput {
  if (!(event.endDate >= event.startDate)) {
    throw new RangeError('endDate must not be before startDate');
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
  };
}

function fromNative(event: NativeEvent): CalendarEvent {
  const result: CalendarEvent = {
    id: event.id,
    calendarId: event.calendarId,
    title: event.title,
    startDate: new Date(event.startDate),
    endDate: new Date(event.endDate),
    allDay: event.allDay,
  };
  if (event.location) result.location = event.location;
  if (event.notes) result.notes = event.notes;
  return result;
}

/** Whether the app may read and write the calendars, without asking */
export async function getPermissionStatus(): Promise<PermissionStatus> {
  return (await NativeDeviceCalendar.getPermissionStatus()) as PermissionStatus;
}

/** Asks the person for full access to the calendars, unless the app has it */
export async function requestPermission(): Promise<PermissionStatus> {
  return (await NativeDeviceCalendar.requestPermission()) as PermissionStatus;
}

/** The calendars of the device that hold events */
export function getCalendars(): Promise<Calendar[]> {
  return NativeDeviceCalendar.getCalendars();
}

/** Creates a calendar of the app's own and returns its id */
export async function createCalendar(
  options: CalendarOptions
): Promise<string> {
  if (!/^#[0-9a-fA-F]{6}$/.test(options.color)) {
    throw new RangeError('color must be #RRGGBB');
  }
  return NativeDeviceCalendar.createCalendar({
    title: options.title,
    color: options.color,
    accountName: options.accountName ?? null,
  });
}

/**
 * Deletes a calendar with all its events. Returns false when there is no such calendar, or on Android when it is not one of a local account
 * (a calendar of a Google or Exchange account belongs to that account).
 */
export function deleteCalendar(id: string): Promise<boolean> {
  return NativeDeviceCalendar.deleteCalendar(id);
}

/** The events that overlap the period; a recurring event once per occurrence */
export async function getEvents(query: EventsQuery): Promise<CalendarEvent[]> {
  if (!(query.endDate > query.startDate)) {
    throw new RangeError('endDate must be after startDate');
  }
  const events = await NativeDeviceCalendar.getEvents(
    query.startDate.getTime(),
    query.endDate.getTime(),
    query.calendarIds ?? []
  );
  return events.map(fromNative);
}

/** Adds an event and returns its id */
export async function createEvent(event: EventInput): Promise<string> {
  return NativeDeviceCalendar.createEvent(toNative(event));
}

/**
 * Changes an event. Its calendar stays: to move an event, delete it and create it in the other calendar. A recurring event cannot be
 * changed (`E_RECURRING`).
 */
export async function updateEvent(
  id: string,
  event: EventInput
): Promise<void> {
  return NativeDeviceCalendar.updateEvent(id, toNative(event));
}

/** Deletes an event, with every occurrence when it recurs. Returns false when there is no such event */
export function deleteEvent(id: string): Promise<boolean> {
  return NativeDeviceCalendar.deleteEvent(id);
}
