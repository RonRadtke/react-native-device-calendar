import { TurboModuleRegistry, type TurboModule } from 'react-native';

/** A calendar as the platform reports it; dates cross the bridge as milliseconds since the epoch */
export type NativeCalendar = {
  id: string;
  title: string;
  color: string;
  source: string;
  sourceType: string;
  isPrimary: boolean;
  allowsModifications: boolean;
};

/** An event, or the occurrence of a recurring event, in local time; an all-day event runs from local midnight to the local midnight after its last day */
export type NativeEvent = {
  id: string;
  calendarId: string;
  title: string;
  startDate: number;
  endDate: number;
  allDay: boolean;
  location: string | null;
  notes: string | null;
};

/** What the app writes; for an all-day event, `startDate` and `endDate` are local midnights with the end exclusive */
export type NativeEventInput = {
  calendarId: string;
  title: string;
  startDate: number;
  endDate: number;
  allDay: boolean;
  location: string | null;
  notes: string | null;
};

export type NativeCalendarInput = {
  title: string;
  color: string;
  accountName: string | null;
};

export interface Spec extends TurboModule {
  getPermissionStatus(): Promise<string>;
  requestPermission(): Promise<string>;
  getCalendars(): Promise<NativeCalendar[]>;
  createCalendar(calendar: NativeCalendarInput): Promise<string>;
  deleteCalendar(calendarId: string): Promise<boolean>;
  getEvents(
    startDate: number,
    endDate: number,
    calendarIds: string[]
  ): Promise<NativeEvent[]>;
  createEvent(event: NativeEventInput): Promise<string>;
  updateEvent(eventId: string, event: NativeEventInput): Promise<void>;
  deleteEvent(eventId: string): Promise<boolean>;
}

export default TurboModuleRegistry.getEnforcing<Spec>(
  'ReactNativeDeviceCalendar'
);
