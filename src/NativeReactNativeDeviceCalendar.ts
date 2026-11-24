import { TurboModuleRegistry, type TurboModule } from 'react-native';

/*
 * The contract between JavaScript and the platforms. Dates cross as milliseconds since the epoch. The JavaScript side (index.tsx)
 * checks input and turns these shapes into the public types; the platforms only map them onto EventKit and CalendarContract.
 *
 * Strings that stand for a choice carry these values:
 * - permission: granted | writeOnly | denied | undetermined | restricted
 * - availability: busy | free | tentative | unavailable | notSupported
 * - event status: none | confirmed | tentative | canceled
 * - alarm method: default | alert | email | sms
 * - frequency: daily | weekly | monthly | yearly
 * - attendee role: unknown | required | optional | chair | nonParticipant
 * - attendee status: unknown | pending | accepted | declined | tentative | delegated | completed | inProcess
 * - attendee type: unknown | person | room | resource | group
 * - span: all (the whole event, every occurrence of a recurring one) | this (one occurrence) | future (one occurrence and the
 *   ones after it)
 * - dialog action: done | saved | canceled | deleted | responded
 */

export type NativeSource = {
  /** iOS: sourceIdentifier; Android: account type and name, joined by a line feed */
  id: string;
  name: string;
  /** Android: the account type such as com.google or LOCAL; iOS: local | exchange | caldav | mobileme | subscribed | birthdays */
  type: string;
  isLocal: boolean;
};

export type NativeCalendar = {
  id: string;
  title: string;
  /** #RRGGBB */
  color: string;
  sourceId: string;
  source: string;
  sourceType: string;
  isPrimary: boolean;
  allowsModifications: boolean;
  isVisible: boolean;
  /** Android: the calendar's time zone; iOS: null */
  timeZone: string | null;
  allowedAvailabilities: string[];
};

export type NativeCalendarInput = {
  title: string;
  color: string;
  /** iOS: the source to create the calendar in; null picks iCloud, else the default calendar's source, else the device */
  sourceId: string | null;
  /** Android: the local account that holds the calendar; null is the app's name */
  accountName: string | null;
  /** Android: the calendar's time zone; null is the device's */
  timeZone: string | null;
};

export type NativeCalendarChanges = {
  /** null keeps it */
  title: string | null;
  /** null keeps it */
  color: string | null;
};

export type NativeAlarm = {
  /** Minutes relative to the start; negative is before it */
  relativeOffset: number;
  /** Android: the method; iOS: null */
  method: string | null;
};

export type NativeWeekday = {
  /** 1 Sunday to 7 Saturday */
  day: number;
  /** 0 every such day; 1 to 53 or -1 to -53 the nth such day of the month or year (monthly and yearly rules only) */
  weekNumber: number;
};

export type NativeRecurrenceRule = {
  frequency: string;
  /** 1 or more */
  interval: number;
  /** The last moment an occurrence may start, inclusive; null when the rule ends by count or never */
  endDate: number | null;
  /** The number of occurrences; null when the rule ends by date or never */
  occurrence: number | null;
  daysOfTheWeek: NativeWeekday[];
  /** 1 to 31 or -1 to -31 */
  daysOfTheMonth: number[];
  /** 1 to 12 */
  monthsOfTheYear: number[];
  /** 1 to 53 or -1 to -53 */
  weeksOfTheYear: number[];
  /** 1 to 366 or -1 to -366 */
  daysOfTheYear: number[];
  /** 1 to 366 or -1 to -366 */
  setPositions: number[];
};

export type NativeAttendee = {
  name: string | null;
  email: string | null;
  role: string;
  status: string;
  type: string;
  isCurrentUser: boolean;
};

/**
 * An event, or one occurrence of a recurring event. All-day events run from local midnight of their first day to the local
 * midnight after their last day.
 */
export type NativeEvent = {
  id: string;
  calendarId: string;
  title: string;
  startDate: number;
  endDate: number;
  allDay: boolean;
  location: string | null;
  notes: string | null;
  /** iOS only */
  url: string | null;
  /** The IANA time zone of a timed event; null for all-day and floating events */
  timeZone: string | null;
  availability: string;
  status: string;
  alarms: NativeAlarm[];
  recurrenceRule: NativeRecurrenceRule | null;
  /** The start this occurrence has by the rule, before any change to it; null for an event that does not recur */
  originalStartDate: number | null;
  /** Whether this occurrence was changed apart from its series */
  isDetached: boolean;
  organizer: NativeAttendee | null;
  /** iOS only */
  creationDate: number | null;
  /** iOS only */
  lastModifiedDate: number | null;
};

/** What the app writes; all-day events as local midnights with the end exclusive. Updates carry the whole event, merged on the JavaScript side */
export type NativeEventInput = {
  calendarId: string;
  title: string;
  startDate: number;
  endDate: number;
  allDay: boolean;
  location: string | null;
  notes: string | null;
  /** iOS only; ignored on Android */
  url: string | null;
  /** null is the device's time zone; ignored for all-day events */
  timeZone: string | null;
  /** null is busy */
  availability: string | null;
  alarms: NativeAlarm[];
  recurrenceRule: NativeRecurrenceRule | null;
};

/** The fields the system's event editor starts with; null leaves a field to the editor */
export type NativeDialogEvent = {
  calendarId: string | null;
  title: string | null;
  startDate: number | null;
  endDate: number | null;
  allDay: boolean | null;
  location: string | null;
  notes: string | null;
  url: string | null;
};

export type NativeDialogResult = {
  action: string;
  id: string | null;
};

export interface Spec extends TurboModule {
  getPermissionStatus(): Promise<string>;
  /** writeOnly: iOS 17 and later asks for write-only access; Android ignores it */
  requestPermission(writeOnly: boolean): Promise<string>;

  getSources(): Promise<NativeSource[]>;
  getCalendars(): Promise<NativeCalendar[]>;
  /** iOS: the calendar for new events; Android: null */
  getDefaultCalendar(): Promise<NativeCalendar | null>;
  createCalendar(calendar: NativeCalendarInput): Promise<string>;
  updateCalendar(
    calendarId: string,
    changes: NativeCalendarChanges
  ): Promise<void>;
  deleteCalendar(calendarId: string): Promise<boolean>;

  getEvents(
    startDate: number,
    endDate: number,
    calendarIds: string[]
  ): Promise<NativeEvent[]>;
  /** The event, or with `occurrence` the occurrence of a recurring event that starts then by its rule; null when there is none */
  getEvent(
    eventId: string,
    occurrence: number | null
  ): Promise<NativeEvent | null>;
  createEvent(event: NativeEventInput): Promise<string>;
  /** Returns the id of the changed event; changing one occurrence on Android creates an exception with an id of its own */
  updateEvent(
    eventId: string,
    event: NativeEventInput,
    span: string,
    occurrence: number | null
  ): Promise<string>;
  deleteEvent(
    eventId: string,
    span: string,
    occurrence: number | null
  ): Promise<boolean>;
  getAttendees(
    eventId: string,
    occurrence: number | null
  ): Promise<NativeAttendee[]>;

  /** Shows an event in the system's event view; resolves with the dialog action (Android: done once the calendar app opened) */
  openEvent(
    eventId: string,
    occurrence: number | null,
    allowsEditing: boolean
  ): Promise<string>;
  /** The system's editor for a new event; Android cannot tell the outcome and resolves with done and a null id */
  createEventWithDialog(event: NativeDialogEvent): Promise<NativeDialogResult>;
  /** The system's editor for an existing event */
  editEventWithDialog(
    eventId: string,
    occurrence: number | null
  ): Promise<NativeDialogResult>;
}

export default TurboModuleRegistry.getEnforcing<Spec>(
  'ReactNativeDeviceCalendar'
);
