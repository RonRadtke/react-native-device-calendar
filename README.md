# @ronradtke/react-native-device-calendar

Read and write the calendars of the device from React Native: the iPhone's calendar through EventKit and Android's calendar
provider through CalendarContract, so events appear in the calendar apps and in the accounts (iCloud, Google, Exchange)
they sync to.

- Calendars, events, recurring events with single and future occurrences, alarms, availability, time zones, attendees
- The system's own screens to show, create and edit an event, which need no permission
- All-day events that keep their days in every time zone and across the change to and from summer time
- A typed API that checks input before it reaches the platform
- A Turbo Module for the New Architecture, with no dependencies besides React Native

## Requirements

| | |
| --- | --- |
| React Native | New Architecture; developed and tested with 0.88 |
| iOS | the minimum of your React Native version; write-only access needs iOS 17 |
| Android | API 24 or later |

## Installation

```sh
npm install @ronradtke/react-native-device-calendar
```

### iOS

Add the reasons the permission dialog shows to your `Info.plist`, then run `pod install`:

```xml
<!-- iOS 17 and later, full access -->
<key>NSCalendarsFullAccessUsageDescription</key>
<string>Your shifts appear in your calendar.</string>
<!-- iOS 17 and later, write-only access (only when you ask for it) -->
<key>NSCalendarsWriteOnlyAccessUsageDescription</key>
<string>Your shifts are added to your calendar.</string>
<!-- iOS 16 and earlier -->
<key>NSCalendarsUsageDescription</key>
<string>Your shifts appear in your calendar.</string>
```

iOS ends an app that asks for access without the matching key.

### Android

Nothing to do: the library declares `READ_CALENDAR` and `WRITE_CALENDAR`, which merge into your manifest.

## Usage

```ts
import {
  createCalendar,
  createEvent,
  getEvents,
  requestPermission,
  updateEvent,
} from '@ronradtke/react-native-device-calendar';

if ((await requestPermission()) === 'granted') {
  const calendarId = await createCalendar({ title: 'Shifts', color: '#1565C0' });

  const id = await createEvent({
    calendarId,
    title: 'Early shift',
    startDate: new Date('2026-08-17T06:00:00'),
    endDate: new Date('2026-08-17T14:00:00'),
    alarms: [{ relativeOffset: -30 }],
    recurrenceRule: {
      frequency: 'weekly',
      daysOfTheWeek: [{ day: 'monday' }, { day: 'wednesday' }],
      occurrence: 10,
    },
  });

  // 19 and 20 August: the end is exclusive
  await createEvent({
    calendarId,
    title: 'Holiday',
    startDate: new Date('2026-08-19T00:00:00'),
    endDate: new Date('2026-08-21T00:00:00'),
    allDay: true,
  });

  const events = await getEvents({
    startDate: new Date('2026-08-01T00:00:00'),
    endDate: new Date('2026-09-01T00:00:00'),
    calendarIds: [calendarId],
  });

  // the second shift only, one hour later
  const second = events.filter((event) => event.id === id)[1];
  await updateEvent(
    id,
    { startDate: new Date('2026-08-19T07:00:00'), endDate: new Date('2026-08-19T15:00:00') },
    { occurrence: second.originalStartDate }
  );
}
```

The [example app](example/src/App.tsx) runs these calls against the device's calendars.

## API

### Permission

| Function | Returns |
| --- | --- |
| `getPermissionStatus()` | `Promise<PermissionStatus>`, without asking |
| `requestPermission({ writeOnly? })` | `Promise<PermissionStatus>`, after asking the person when the system still allows it |

`PermissionStatus`:

- `granted`: full access.
- `writeOnly` (iOS 17 and later): the app may add events, but not read calendars or events. Ask for it with
  `requestPermission({ writeOnly: true })` when the app only adds events.
- `undetermined`: the app may ask.
- `denied`: refused. The system does not ask again; lead the person to the app's settings. On Android this shows after the
  person refused twice, or chose "Don't allow" with "Don't ask again".
- `restricted` (iOS): blocked by parental controls or a device management profile.

The calls that need access reject with `E_PERMISSION` without it. With write-only access, `getDefaultCalendar`,
`createEvent` and the dialogs work.

### Sources and calendars

| Function | Returns |
| --- | --- |
| `getSources()` | `Promise<Source[]>`: the accounts that hold calendars |
| `getCalendars()` | `Promise<Calendar[]>`: every calendar that holds events |
| `getDefaultCalendar()` | `Promise<Calendar \| null>`: iOS, the calendar for new events; Android has none and answers `null` |
| `createCalendar({ title, color, sourceId?, accountName?, timeZone? })` | `Promise<string>`: the id of the new calendar |
| `updateCalendar(id, { title?, color? })` | `Promise<void>` |
| `deleteCalendar(id)` | `Promise<boolean>`: `false` when there is no such calendar or the app may not delete it |

```ts
type Calendar = {
  id: string;
  title: string;
  color: string; // #RRGGBB
  sourceId: string;
  source: string; // the account: an e-mail address, "iCloud", the name of a local account
  sourceType: string; // Android: com.google, LOCAL, …; iOS: local, exchange, caldav, mobileme, subscribed, birthdays
  isPrimary: boolean; // Android: primary calendar of its account; iOS: default calendar for new events
  allowsModifications: boolean;
  isVisible: boolean; // Android: shown in the calendar app; iOS: always true
  timeZone?: string; // Android only
  allowedAvailabilities: Availability[];
};
```

Where a new calendar goes:

- **iOS** creates it in the source you name, by default in iCloud when the person uses iCloud calendars, else in the
  account of the default calendar, else on the device.
- **Android** lets apps create calendars only in a local account (`accountName`, by default the app's name), so the
  calendar stays on the device, and only such calendars can be deleted by the app. For events on all of a person's
  devices, write them into a calendar of their Google or Exchange account instead.

### Events

| Function | Returns |
| --- | --- |
| `getEvents({ startDate, endDate, calendarIds? })` | `Promise<CalendarEvent[]>`: the events overlapping the period, of every calendar when `calendarIds` is left out; a recurring event once per occurrence |
| `getEvent(id, { occurrence? })` | `Promise<CalendarEvent \| null>` |
| `createEvent(details)` | `Promise<string>`: the id of the new event |
| `updateEvent(id, changes, { occurrence?, futureEvents? })` | `Promise<string>`: the id of the changed event |
| `deleteEvent(id, { occurrence?, futureEvents? })` | `Promise<boolean>`: `false` when there is no such event |
| `getAttendees(id, { occurrence? })` | `Promise<Attendee[]>` |

```ts
type EventDetails = {
  calendarId: string;
  title: string;
  startDate: Date;
  endDate: Date; // exclusive
  allDay?: boolean;
  location?: string;
  notes?: string;
  url?: string; // iOS only
  timeZone?: string; // IANA name, by default the device's
  availability?: 'busy' | 'free' | 'tentative' | 'unavailable' | 'notSupported';
  alarms?: { relativeOffset: number; method?: 'default' | 'alert' | 'email' | 'sms' }[];
  recurrenceRule?: RecurrenceRule;
};

type CalendarEvent = EventDetails & {
  id: string; // the same for every occurrence of a recurring event
  status: 'none' | 'confirmed' | 'tentative' | 'canceled';
  originalStartDate?: Date; // the start an occurrence has by its rule
  isDetached: boolean; // an occurrence changed apart from its series
  organizer?: Attendee;
  creationDate?: Date; // iOS only
  lastModifiedDate?: Date; // iOS only
};
```

- **All-day events** cover the local days from `startDate` up to `endDate`, the end exclusive: 19 August 00:00 to 21
  August 00:00 are the 19th and the 20th. An end that is not a midnight counts its day in, and an event lasts at least
  one day. Read back, an all-day event starts at local midnight of its first day and ends at the local midnight after its
  last. Android stores such events in UTC and iOS in local time; the library hides the difference.
- **Alarms** count minutes from the start, negative before it: `-30` rings half an hour before. Android rings only before
  the start and rejects a positive offset with `E_UNSUPPORTED`; `method` is Android's way of reminding.
- **Changes** name only what changes: what `changes` leaves out stays, `null` removes an optional field. An event cannot
  change its calendar; delete it and create it in the other calendar.

### Recurring events

```ts
type RecurrenceRule = {
  frequency: 'daily' | 'weekly' | 'monthly' | 'yearly';
  interval?: number; // every nth day, week, month or year
  endDate?: Date; // the last day an occurrence may start on
  occurrence?: number; // or the number of occurrences
  daysOfTheWeek?: { day: 'sunday' | 'monday' | …; weekNumber?: number }[]; // weekNumber: monthly and yearly rules, 1 the first, -1 the last
  daysOfTheMonth?: number[]; // 1 to 31, -1 the last
  monthsOfTheYear?: number[]; // 1 to 12
  weeksOfTheYear?: number[]; // yearly rules
  daysOfTheYear?: number[]; // yearly rules
  setPositions?: number[]; // which of the selected days count: 1 the first, -1 the last
};
```

`getEvents` returns every occurrence with the series' `id` and its `originalStartDate`. Calls on a recurring event take
the occurrence they mean:

| Options | Meaning |
| --- | --- |
| none | the whole series |
| `{ occurrence }` | that occurrence only |
| `{ occurrence, futureEvents: true }` | that occurrence and every one after it |

`occurrence` is the occurrence's `originalStartDate`. Changing one occurrence detaches it from its series
(`isDetached`); on Android it becomes an event of its own, whose id `updateEvent` returns. A single occurrence has no
rule of its own, so `recurrenceRule` in its changes is refused. Changing the future occurrences ends the series before
them and starts a new one; iOS and Android both do that, and `updateEvent` returns the id of the new series where it
differs.

On iOS, an occurrence is searched within a week of its `originalStartDate`, since EventKit finds events only by period: an
occurrence that was moved further away is found by `getEvents`, but not by its original start.

### The system's screens

| Function | Returns |
| --- | --- |
| `openEvent(id, { occurrence?, allowsEditing? })` | `Promise<DialogAction>`: shows the event in the system's view |
| `createEventWithDialog(details?)` | `Promise<DialogResult>`: the system's editor for a new event, started with `details` |
| `editEventWithDialog(id, { occurrence? })` | `Promise<DialogResult>`: the system's editor for an event |

`DialogResult` is `{ action: 'done' | 'saved' | 'canceled' | 'deleted' | 'responded'; id?: string }`.

- **iOS** shows the screens over the app and resolves when the person closes them, with the outcome and, after saving,
  the event's id. `createEventWithDialog` needs no permission on iOS 17 and later.
- **Android** hands the event to the calendar app and resolves with `done` at once: it cannot tell what the person did
  there. `createEventWithDialog` needs no permission.

### Attendees

`getAttendees` returns `{ name?, email?, role, status, type, isCurrentUser }` for everyone invited. EventKit lets no app
invite people, so the library only reads them.

### Errors

The promises reject with an `Error` whose `code` says what went wrong:

| Code | Meaning |
| --- | --- |
| `E_PERMISSION` | the app lacks the access the call needs |
| `E_NOT_FOUND` | no such calendar, source or event |
| `E_CALENDAR_CHANGE` | `updateEvent` cannot move an event to another calendar |
| `E_UNSUPPORTED` | the platform cannot do it, such as an alarm after the start on Android |
| `E_NO_ACTIVITY` | Android: no activity to show the permission dialog or a screen from |
| `E_DIALOG` | iOS: another of the library's screens is open |
| `E_CALENDAR` | the platform refused the change; the message says why |

Invalid input, such as an end before the start, a colour that is not `#RRGGBB` or a rule that ends both by date and by
count, rejects with a `RangeError` before it reaches the platform.

## Not covered

- iOS Reminders (`EKReminder`), which Android has no counterpart for
- Inviting people and changing attendees, which EventKit does not allow apps to do
- Alarms at a fixed time or place, and Android's extended properties of events

Issues and pull requests for these are welcome.

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)
- [Security policy](SECURITY.md)

## License

[MIT](LICENSE)

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
