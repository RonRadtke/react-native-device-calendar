/**
 * Whether the app may use the calendars.
 *
 * - `granted`: full access
 * - `writeOnly`: iOS 17 and later, the app may add events but not read them
 * - `undetermined`: the app may ask
 * - `denied`: refused; the system does not ask again, the person has to change it in the settings
 * - `restricted`: iOS, blocked by parental controls or a device management profile
 */
export type PermissionStatus =
  'granted' | 'writeOnly' | 'denied' | 'undetermined' | 'restricted';

export type PermissionOptions = {
  /** iOS 17 and later: ask only for the right to add events, without reading the calendars. Ignored on Android */
  writeOnly?: boolean;
};

/** An account that holds calendars */
export type Source = {
  id: string;
  name: string;
  /** Android: the account type such as `com.google` or `LOCAL`; iOS: `local`, `exchange`, `caldav`, `mobileme`, `subscribed` or `birthdays` */
  type: string;
  /** Whether its calendars stay on the device */
  isLocal: boolean;
};

export type Availability =
  'busy' | 'free' | 'tentative' | 'unavailable' | 'notSupported';

export type Calendar = {
  id: string;
  title: string;
  /** `#RRGGBB` */
  color: string;
  sourceId: string;
  /** The name of the account: an e-mail address, "iCloud", the name of a local account */
  source: string;
  /** The type of the account, as in {@link Source} */
  sourceType: string;
  /** Android: the primary calendar of its account; iOS: the default calendar for new events */
  isPrimary: boolean;
  /** Whether the app may add, change and delete events in it */
  allowsModifications: boolean;
  /** Android: whether the calendar app shows it; iOS: always true */
  isVisible: boolean;
  /** Android only: the calendar's time zone */
  timeZone?: string;
  /** The availabilities its events may have */
  allowedAvailabilities: Availability[];
};

export type CalendarOptions = {
  title: string;
  /** `#RRGGBB` */
  color: string;
  /**
   * iOS: the source to create the calendar in, from {@link getSources}. By default iCloud when the person uses it, else the source of
   * the default calendar, else the device.
   */
  sourceId?: string;
  /**
   * Android: the local account that holds the calendar, by default the app's name. Android lets apps create calendars only in local
   * accounts, so the calendar stays on the device.
   */
  accountName?: string;
  /** Android: the calendar's time zone, by default the device's */
  timeZone?: string;
};

export type CalendarChanges = {
  title?: string;
  /** `#RRGGBB` */
  color?: string;
};

export type AlarmMethod = 'default' | 'alert' | 'email' | 'sms';

export type Alarm = {
  /** Minutes relative to the start of the event; negative is before it: -15 is a quarter of an hour before */
  relativeOffset: number;
  /** Android only, `default` when left out */
  method?: AlarmMethod;
};

export type Frequency = 'daily' | 'weekly' | 'monthly' | 'yearly';

export type DayOfTheWeek =
  | 'sunday'
  | 'monday'
  | 'tuesday'
  | 'wednesday'
  | 'thursday'
  | 'friday'
  | 'saturday';

export type RecurrenceDay = {
  day: DayOfTheWeek;
  /**
   * Monthly and yearly rules: the nth such day of the month or year, 1 the first, -1 the last. Left out: every such day.
   */
  weekNumber?: number;
};

/** A repetition as RFC 5545 defines it */
export type RecurrenceRule = {
  frequency: Frequency;
  /** Every nth day, week, month or year; 1 when left out */
  interval?: number;
  /** The last day an occurrence may start on; leave out `endDate` and `occurrence` for a rule without end */
  endDate?: Date;
  /** The number of occurrences, instead of an end date */
  occurrence?: number;
  /** Weekly rules: the days of the week; monthly and yearly rules: also the nth day of the month or year */
  daysOfTheWeek?: RecurrenceDay[];
  /** 1 to 31, or -1 to -31 counted from the end of the month */
  daysOfTheMonth?: number[];
  /** 1 January to 12 December */
  monthsOfTheYear?: number[];
  /** Yearly rules: 1 to 53, or -1 to -53 */
  weeksOfTheYear?: number[];
  /** Yearly rules: 1 to 366, or -1 to -366 */
  daysOfTheYear?: number[];
  /** Which of the occurrences of each period the other parts select: 1 the first, -1 the last */
  setPositions?: number[];
};

export type EventStatus = 'none' | 'confirmed' | 'tentative' | 'canceled';

export type AttendeeRole =
  'unknown' | 'required' | 'optional' | 'chair' | 'nonParticipant';

export type AttendeeStatus =
  | 'unknown'
  | 'pending'
  | 'accepted'
  | 'declined'
  | 'tentative'
  | 'delegated'
  | 'completed'
  | 'inProcess';

export type AttendeeType = 'unknown' | 'person' | 'room' | 'resource' | 'group';

export type Attendee = {
  name?: string;
  email?: string;
  role: AttendeeRole;
  status: AttendeeStatus;
  type: AttendeeType;
  /** Whether it is the person using the device */
  isCurrentUser: boolean;
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
  /** iOS only */
  url?: string;
  /** The IANA time zone of a timed event; missing for all-day and floating events */
  timeZone?: string;
  availability: Availability;
  status: EventStatus;
  alarms: Alarm[];
  recurrenceRule?: RecurrenceRule;
  /** For an occurrence of a recurring event: the start it has by the rule, which identifies it in {@link RecurringEventOptions} */
  originalStartDate?: Date;
  /** Whether this occurrence was changed apart from its series */
  isDetached: boolean;
  organizer?: Attendee;
  /** iOS only */
  creationDate?: Date;
  /** iOS only */
  lastModifiedDate?: Date;
};

export type EventDetails = {
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
  /** iOS only, ignored on Android */
  url?: string;
  /** The IANA time zone of a timed event, by default the device's */
  timeZone?: string;
  /** `busy` when left out */
  availability?: Availability;
  alarms?: Alarm[];
  recurrenceRule?: RecurrenceRule;
};

/** The fields to change; null removes an optional field, what is left out stays */
export type EventChanges = {
  title?: string;
  startDate?: Date;
  endDate?: Date;
  allDay?: boolean;
  location?: string | null;
  notes?: string | null;
  url?: string | null;
  timeZone?: string | null;
  availability?: Availability;
  alarms?: Alarm[];
  recurrenceRule?: RecurrenceRule | null;
};

/**
 * Which part of a recurring event a call means. Left out: the whole event with every occurrence. With `occurrence`: that occurrence
 * only, or with `futureEvents` that occurrence and every one after it.
 */
export type RecurringEventOptions = {
  /** The occurrence's {@link CalendarEvent.originalStartDate}, or its start when it was not changed */
  occurrence?: Date;
  futureEvents?: boolean;
};

export type EventsQuery = {
  startDate: Date;
  endDate: Date;
  /** Only events of these calendars; all calendars when left out */
  calendarIds?: string[];
};

export type DialogAction =
  'done' | 'saved' | 'canceled' | 'deleted' | 'responded';

export type DialogResult = {
  action: DialogAction;
  /** The id of the saved event, where the platform tells it (iOS) */
  id?: string;
};

/** What the system's editor starts with; everything can still be changed there */
export type DialogEventDetails = {
  calendarId?: string;
  title?: string;
  startDate?: Date;
  endDate?: Date;
  allDay?: boolean;
  location?: string;
  notes?: string;
  /** iOS only */
  url?: string;
};

export type OpenEventOptions = RecurringEventOptions & {
  /** iOS: whether the view offers editing; Android leaves it to the calendar app */
  allowsEditing?: boolean;
};
