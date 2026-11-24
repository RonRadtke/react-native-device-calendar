# @ronradtke/react-native-device-calendar

Read and write the calendars of the device from React Native: the iPhone's calendar through EventKit and Android's calendar
provider through CalendarContract, so events appear in the calendar apps and in the accounts (iCloud, Google, Exchange)
they sync to.

- A small, typed API: permission, calendars, events. Nothing else.
- A Turbo Module for the New Architecture.
- All-day events that keep their days in every time zone and across the change to and from summer time.
- No dependencies besides React Native.

## Requirements

| | |
| --- | --- |
| React Native | New Architecture; developed and tested with 0.88 |
| iOS | the minimum of your React Native version; full access on iOS 17 and later |
| Android | API 24 or later |

## Installation

```sh
npm install @ronradtke/react-native-device-calendar
```

### iOS

Add the reasons the permission dialog shows to your `Info.plist`, then run `pod install`:

```xml
<!-- iOS 17 and later -->
<key>NSCalendarsFullAccessUsageDescription</key>
<string>Your shifts appear in your calendar.</string>
<!-- iOS 16 and earlier -->
<key>NSCalendarsUsageDescription</key>
<string>Your shifts appear in your calendar.</string>
```

Without `NSCalendarsFullAccessUsageDescription`, iOS 17 ends the app when it asks for access.

### Android

Nothing to do: the library declares `READ_CALENDAR` and `WRITE_CALENDAR`, which merge into your manifest.

## Usage

```ts
import {
  createCalendar,
  createEvent,
  getEvents,
  requestPermission,
} from '@ronradtke/react-native-device-calendar';

if ((await requestPermission()) === 'granted') {
  const calendarId = await createCalendar({ title: 'Shifts', color: '#1565C0' });

  await createEvent({
    calendarId,
    title: 'Early shift',
    startDate: new Date('2026-08-19T06:00:00'),
    endDate: new Date('2026-08-19T14:00:00'),
    location: 'Reception',
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
}
```

## API

### Permission

| Function | Returns |
| --- | --- |
| `getPermissionStatus()` | `Promise<PermissionStatus>`, without asking |
| `requestPermission()` | `Promise<PermissionStatus>`, after asking the person unless the app has access |

`PermissionStatus` is one of:

- `granted`: full access.
- `undetermined`: not asked yet. On Android also after a refusal, which Android tells apart only by asking: a request then
  answers `denied` at once when the person chose "Don't allow" twice, and the app should lead them to its settings.
- `denied`: refused. On iOS 17 and later also write-only access, which cannot read events; `requestPermission()` asks
  for full access then.
- `restricted` (iOS): blocked by parental controls or a device management profile.

### Calendars

| Function | Returns |
| --- | --- |
| `getCalendars()` | `Promise<Calendar[]>`: every calendar that holds events |
| `createCalendar({ title, color, accountName? })` | `Promise<string>`: the id of the new calendar |
| `deleteCalendar(id)` | `Promise<boolean>`: `false` when there is no such calendar or the app may not delete it |

```ts
type Calendar = {
  id: string;
  title: string;
  color: string; // #RRGGBB
  source: string; // the account: an e-mail address, "iCloud", the name of a local account
  sourceType: string; // Android: account type such as com.google or LOCAL; iOS: local, exchange, caldav, mobileme, subscribed, birthdays
  isPrimary: boolean; // Android: primary calendar of its account; iOS: default calendar for new events
  allowsModifications: boolean;
};
```

Where a new calendar goes:

- **Android** lets an app create calendars only in a local account (`accountName`, by default the app's name), so the
  calendar stays on the device, and only such calendars can be deleted by the app. For events on all of a person's
  devices, write them into a calendar of their Google or Exchange account instead.
- **iOS** creates it in iCloud when the person uses iCloud calendars, else in the account of the default calendar,
  else on the device.

### Events

| Function | Returns |
| --- | --- |
| `getEvents({ startDate, endDate, calendarIds? })` | `Promise<CalendarEvent[]>`: the events overlapping the period, of all calendars when `calendarIds` is left out |
| `createEvent(event)` | `Promise<string>`: the id of the new event |
| `updateEvent(id, event)` | `Promise<void>` |
| `deleteEvent(id)` | `Promise<boolean>`: `false` when there is no such event |

```ts
type EventInput = {
  calendarId: string;
  title: string;
  startDate: Date;
  endDate: Date; // exclusive
  allDay?: boolean;
  location?: string;
  notes?: string;
};

type CalendarEvent = EventInput & { id: string; allDay: boolean };
```

- **All-day events** cover the local days from `startDate` up to `endDate`, the end exclusive: 19 August 00:00 to 21
  August 00:00 are the 19th and the 20th. An end that is not a midnight counts its day in, and an event lasts at least
  one day. Read back, an all-day event starts at local midnight of its first day and ends at the local midnight after its
  last. Android stores such events in UTC and iOS in local time; the library hides the difference.
- **Recurring events** come back once per occurrence in the period, every occurrence with the event's id.
  `deleteEvent` deletes the whole series; `updateEvent` refuses a recurring event. The library does not write
  recurrence rules.
- **Moving an event** to another calendar is not an update: delete it and create it in the other calendar.

### Errors

The promises reject with a `code`:

| Code | Meaning |
| --- | --- |
| `E_PERMISSION` | the app has no full access to the calendars |
| `E_NOT_FOUND` | no such calendar or event |
| `E_CALENDAR_CHANGE` | `updateEvent` with another `calendarId` than the event's |
| `E_RECURRING` | `updateEvent` of a recurring event |
| `E_NO_ACTIVITY` | Android: no activity to show the permission dialog |
| `E_CALENDAR` | the platform refused the change; the message says why |

Invalid input, such as an end before the start or a colour that is not `#RRGGBB`, rejects with a `RangeError` before it
reaches the platform.

## Example app

The [example app](example/src/App.tsx) runs every call against the calendars of the device:

```sh
yarn
yarn example android
yarn example ios
```

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)
- [Security policy](SECURITY.md)

## License

[MIT](LICENSE)

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
