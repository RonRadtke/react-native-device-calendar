import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import type {
  NativeEvent,
  NativeEventInput,
  NativeRecurrenceRule,
} from '../NativeReactNativeDeviceCalendar';
import Native from '../NativeReactNativeDeviceCalendar';

jest.mock('../NativeReactNativeDeviceCalendar', () => ({
  __esModule: true,
  default: {
    getPermissionStatus: jest.fn(),
    requestPermission: jest.fn(),
    getSources: jest.fn(),
    getCalendars: jest.fn(),
    getDefaultCalendar: jest.fn(),
    createCalendar: jest.fn(),
    updateCalendar: jest.fn(),
    deleteCalendar: jest.fn(),
    getEvents: jest.fn(),
    getEvent: jest.fn(),
    createEvent: jest.fn(),
    updateEvent: jest.fn(),
    deleteEvent: jest.fn(),
    getAttendees: jest.fn(),
    openEvent: jest.fn(),
    createEventWithDialog: jest.fn(),
    editEventWithDialog: jest.fn(),
  },
}));

import {
  allDayRange,
  createCalendar,
  createEvent,
  createEventWithDialog,
  deleteEvent,
  getAttendees,
  getDefaultCalendar,
  getEvent,
  getEvents,
  requestPermission,
  updateCalendar,
  updateEvent,
  type CalendarEvent,
} from '../index';
import { mergeChanges, ruleFromNative, ruleToNative } from '../convert';

// the tests run in Europe/Berlin (jest.global-setup.js), where summer time ends on 25 October 2026
const local = (text: string) => new Date(text);
const ms = (text: string) => local(text).getTime();

const mockNative = jest.mocked(Native);

/** A timed event as the platforms report it, with the given fields changed */
function nativeEvent(changes: Partial<NativeEvent> = {}): NativeEvent {
  return {
    id: '7',
    calendarId: '3',
    title: 'Early shift',
    startDate: ms('2026-08-19T06:00:00'),
    endDate: ms('2026-08-19T14:00:00'),
    allDay: false,
    location: 'Berlin',
    notes: null,
    url: null,
    timeZone: 'Europe/Berlin',
    availability: 'busy',
    status: 'confirmed',
    alarms: [{ relativeOffset: -15, method: null }],
    recurrenceRule: null,
    originalStartDate: null,
    isDetached: false,
    organizer: null,
    creationDate: null,
    lastModifiedDate: null,
    ...changes,
  };
}

const weekly: NativeRecurrenceRule = {
  frequency: 'weekly',
  interval: 2,
  endDate: null,
  occurrence: 10,
  daysOfTheWeek: [
    { day: 2, weekNumber: 0 },
    { day: 4, weekNumber: 0 },
  ],
  daysOfTheMonth: [],
  monthsOfTheYear: [],
  weeksOfTheYear: [],
  daysOfTheYear: [],
  setPositions: [],
};

beforeEach(() => {
  jest.clearAllMocks();
});

describe('all-day events', () => {
  it('cover the local days from the start up to the exclusive end', () => {
    expect(
      allDayRange(local('2026-08-19T00:00:00'), local('2026-08-21T00:00:00'))
    ).toEqual({
      start: local('2026-08-19T00:00:00'),
      end: local('2026-08-21T00:00:00'),
    });
  });

  it('count in the day of an end that is not a midnight, and last at least one day', () => {
    expect(
      allDayRange(local('2026-08-19T10:00:00'), local('2026-08-20T09:00:00'))
    ).toEqual({
      start: local('2026-08-19T00:00:00'),
      end: local('2026-08-21T00:00:00'),
    });
    const day = local('2026-08-19T00:00:00');
    expect(allDayRange(day, day).end).toEqual(local('2026-08-20T00:00:00'));
  });

  it('keep their days over the end of summer time, a day of 25 hours', () => {
    const range = allDayRange(
      local('2026-10-25T00:00:00'),
      local('2026-10-25T00:00:00')
    );
    expect(range.end).toEqual(local('2026-10-26T00:00:00'));
    expect(range.end.getTime() - range.start.getTime()).toBe(25 * 3600 * 1000);
  });

  it('go to the platform as local midnights, without a time zone', async () => {
    mockNative.createEvent.mockResolvedValue('8');
    await createEvent({
      calendarId: '3',
      title: 'Holiday',
      startDate: local('2026-08-19T15:00:00'),
      endDate: local('2026-08-20T00:00:00'),
      allDay: true,
      timeZone: 'Europe/Berlin',
    });

    const expected: NativeEventInput = {
      calendarId: '3',
      title: 'Holiday',
      startDate: ms('2026-08-19T00:00:00'),
      endDate: ms('2026-08-20T00:00:00'),
      allDay: true,
      location: null,
      notes: null,
      url: null,
      timeZone: null,
      availability: null,
      alarms: [],
      recurrenceRule: null,
    };
    expect(mockNative.createEvent).toHaveBeenCalledWith(expected);
  });
});

describe('creating events', () => {
  it('sends every field', async () => {
    mockNative.createEvent.mockResolvedValue('9');
    await expect(
      createEvent({
        calendarId: '3',
        title: 'Planning',
        startDate: local('2026-08-19T09:00:00'),
        endDate: local('2026-08-19T10:00:00'),
        location: 'Room 2',
        notes: 'Agenda',
        url: 'https://example.com',
        timeZone: 'America/New_York',
        availability: 'tentative',
        alarms: [{ relativeOffset: -30, method: 'email' }],
        recurrenceRule: {
          frequency: 'weekly',
          interval: 2,
          occurrence: 10,
          daysOfTheWeek: [{ day: 'monday' }, { day: 'wednesday' }],
        },
      })
    ).resolves.toBe('9');

    expect(mockNative.createEvent).toHaveBeenCalledWith({
      calendarId: '3',
      title: 'Planning',
      startDate: ms('2026-08-19T09:00:00'),
      endDate: ms('2026-08-19T10:00:00'),
      allDay: false,
      location: 'Room 2',
      notes: 'Agenda',
      url: 'https://example.com',
      timeZone: 'America/New_York',
      availability: 'tentative',
      alarms: [{ relativeOffset: -30, method: 'email' }],
      recurrenceRule: weekly,
    });
  });

  it('refuses what the platforms cannot store, before calling them', async () => {
    const base = {
      calendarId: '3',
      title: 'Backwards',
      startDate: local('2026-08-19T14:00:00'),
      endDate: local('2026-08-19T16:00:00'),
    };
    await expect(
      createEvent({ ...base, endDate: local('2026-08-19T06:00:00') })
    ).rejects.toThrow(RangeError);
    await expect(
      createEvent({ ...base, startDate: new Date(Number.NaN) })
    ).rejects.toThrow(RangeError);
    await expect(
      createEvent({
        ...base,
        alarms: [{ relativeOffset: -5, method: 'pager' as 'sms' }],
      })
    ).rejects.toThrow(RangeError);
    expect(mockNative.createEvent).not.toHaveBeenCalled();
  });
});

describe('recurrence rules', () => {
  it('turn days into the platforms numbers and back', () => {
    const rule = {
      frequency: 'monthly' as const,
      daysOfTheWeek: [{ day: 'friday' as const, weekNumber: -1 }],
      monthsOfTheYear: [3, 6],
      endDate: local('2027-12-31T23:59:59'),
    };
    const native = ruleToNative(rule);
    expect(native).toMatchObject({
      frequency: 'monthly',
      interval: 1,
      endDate: ms('2027-12-31T23:59:59'),
      occurrence: null,
      daysOfTheWeek: [{ day: 6, weekNumber: -1 }],
      monthsOfTheYear: [3, 6],
    });
    expect(ruleFromNative(native)).toEqual({ ...rule, interval: 1 });
  });

  it('leave out what the platform reports empty', () => {
    expect(ruleFromNative(weekly)).toEqual({
      frequency: 'weekly',
      interval: 2,
      occurrence: 10,
      daysOfTheWeek: [{ day: 'monday' }, { day: 'wednesday' }],
    });
  });

  it('refuse contradictions and values out of range', () => {
    expect(() =>
      ruleToNative({
        frequency: 'daily',
        occurrence: 3,
        endDate: local('2027-01-01T00:00:00'),
      })
    ).toThrow(RangeError);
    expect(() =>
      ruleToNative({
        frequency: 'weekly',
        daysOfTheWeek: [{ day: 'monday', weekNumber: 1 }],
      })
    ).toThrow(/monthly or yearly/);
    expect(() => ruleToNative({ frequency: 'daily', interval: 0 })).toThrow(
      RangeError
    );
    expect(() =>
      ruleToNative({ frequency: 'monthly', daysOfTheMonth: [32] })
    ).toThrow(RangeError);
    expect(() =>
      ruleToNative({ frequency: 'yearly', monthsOfTheYear: [13] })
    ).toThrow(RangeError);
  });
});

describe('reading events', () => {
  it('come back with dates and without empty optional fields', async () => {
    mockNative.getEvents.mockResolvedValue([
      nativeEvent({
        recurrenceRule: weekly,
        originalStartDate: ms('2026-08-19T06:00:00'),
        organizer: {
          name: null,
          email: 'lead@example.com',
          role: 'chair',
          status: 'accepted',
          type: 'person',
          isCurrentUser: false,
        },
      }),
    ]);

    const events = await getEvents({
      startDate: local('2026-08-19T00:00:00'),
      endDate: local('2026-08-20T00:00:00'),
      calendarIds: ['3'],
    });

    expect(mockNative.getEvents).toHaveBeenCalledWith(
      ms('2026-08-19T00:00:00'),
      ms('2026-08-20T00:00:00'),
      ['3']
    );
    const expected: CalendarEvent[] = [
      {
        id: '7',
        calendarId: '3',
        title: 'Early shift',
        startDate: local('2026-08-19T06:00:00'),
        endDate: local('2026-08-19T14:00:00'),
        allDay: false,
        location: 'Berlin',
        timeZone: 'Europe/Berlin',
        availability: 'busy',
        status: 'confirmed',
        alarms: [{ relativeOffset: -15 }],
        recurrenceRule: {
          frequency: 'weekly',
          interval: 2,
          occurrence: 10,
          daysOfTheWeek: [{ day: 'monday' }, { day: 'wednesday' }],
        },
        originalStartDate: local('2026-08-19T06:00:00'),
        isDetached: false,
        organizer: {
          email: 'lead@example.com',
          role: 'chair',
          status: 'accepted',
          type: 'person',
          isCurrentUser: false,
        },
      },
    ];
    expect(events).toEqual(expected);
  });

  it('read every calendar when none is named, and never an empty period', async () => {
    mockNative.getEvents.mockResolvedValue([]);
    await getEvents({
      startDate: local('2026-08-19T00:00:00'),
      endDate: local('2026-08-20T00:00:00'),
    });
    expect(mockNative.getEvents).toHaveBeenCalledWith(
      expect.any(Number),
      expect.any(Number),
      []
    );

    const day = local('2026-08-19T00:00:00');
    await expect(getEvents({ startDate: day, endDate: day })).rejects.toThrow(
      RangeError
    );
  });

  it('find one occurrence of a recurring event, or none', async () => {
    mockNative.getEvent.mockResolvedValue(null);
    await expect(
      getEvent('7', { occurrence: local('2026-08-26T06:00:00') })
    ).resolves.toBeNull();
    expect(mockNative.getEvent).toHaveBeenCalledWith(
      '7',
      ms('2026-08-26T06:00:00')
    );
  });
});

describe('changing events', () => {
  it('keeps what the changes leave out and removes what they set to null', () => {
    const current: CalendarEvent = {
      id: '7',
      calendarId: '3',
      title: 'Early shift',
      startDate: local('2026-08-19T06:00:00'),
      endDate: local('2026-08-19T14:00:00'),
      allDay: false,
      location: 'Berlin',
      notes: 'Reception',
      timeZone: 'Europe/Berlin',
      availability: 'busy',
      status: 'confirmed',
      alarms: [{ relativeOffset: -15 }],
      recurrenceRule: { frequency: 'daily' },
      isDetached: false,
    };

    expect(
      mergeChanges(current, {
        title: 'Late shift',
        notes: null,
        recurrenceRule: null,
      })
    ).toEqual({
      calendarId: '3',
      title: 'Late shift',
      startDate: local('2026-08-19T06:00:00'),
      endDate: local('2026-08-19T14:00:00'),
      allDay: false,
      location: 'Berlin',
      timeZone: 'Europe/Berlin',
      availability: 'busy',
      alarms: [{ relativeOffset: -15 }],
    });
  });

  it('reads the event, merges the changes and sends the whole event', async () => {
    mockNative.getEvent.mockResolvedValue(nativeEvent());
    mockNative.updateEvent.mockResolvedValue('7');

    await expect(
      updateEvent('7', { endDate: local('2026-08-19T15:00:00') })
    ).resolves.toBe('7');

    expect(mockNative.getEvent).toHaveBeenCalledWith('7', null);
    expect(mockNative.updateEvent).toHaveBeenCalledWith(
      '7',
      expect.objectContaining({
        calendarId: '3',
        title: 'Early shift',
        startDate: ms('2026-08-19T06:00:00'),
        endDate: ms('2026-08-19T15:00:00'),
        location: 'Berlin',
        alarms: [{ relativeOffset: -15, method: null }],
      }),
      'all',
      null
    );
  });

  it('names the span of a recurring event', async () => {
    mockNative.getEvent.mockResolvedValue(
      nativeEvent({ recurrenceRule: weekly })
    );
    mockNative.updateEvent.mockResolvedValue('12');
    const occurrence = local('2026-08-26T06:00:00');

    await expect(
      updateEvent('7', { title: 'Moved' }, { occurrence })
    ).resolves.toBe('12');
    expect(mockNative.updateEvent).toHaveBeenLastCalledWith(
      '7',
      expect.objectContaining({ title: 'Moved', recurrenceRule: null }),
      'this',
      occurrence.getTime()
    );

    await updateEvent(
      '7',
      { title: 'From now on' },
      { occurrence, futureEvents: true }
    );
    expect(mockNative.updateEvent).toHaveBeenLastCalledWith(
      '7',
      expect.objectContaining({ recurrenceRule: weekly }),
      'future',
      occurrence.getTime()
    );
  });

  it('refuses a rule for one occurrence and future events without their start', async () => {
    await expect(
      updateEvent(
        '7',
        { recurrenceRule: { frequency: 'daily' } },
        { occurrence: local('2026-08-26T06:00:00') }
      )
    ).rejects.toThrow(RangeError);
    await expect(deleteEvent('7', { futureEvents: true })).rejects.toThrow(
      RangeError
    );
    expect(mockNative.getEvent).not.toHaveBeenCalled();
  });

  it('rejects with E_NOT_FOUND when the event is gone', async () => {
    mockNative.getEvent.mockResolvedValue(null);
    await expect(updateEvent('7', { title: 'x' })).rejects.toMatchObject({
      code: 'E_NOT_FOUND',
    });
    expect(mockNative.updateEvent).not.toHaveBeenCalled();
  });

  it('deletes the whole event, one occurrence or the future ones', async () => {
    mockNative.deleteEvent.mockResolvedValue(true);
    const occurrence = local('2026-08-26T06:00:00');
    await deleteEvent('7');
    await deleteEvent('7', { occurrence });
    await deleteEvent('7', { occurrence, futureEvents: true });
    expect(mockNative.deleteEvent.mock.calls).toEqual([
      ['7', 'all', null],
      ['7', 'this', occurrence.getTime()],
      ['7', 'future', occurrence.getTime()],
    ]);
  });
});

describe('calendars, attendees, permission and dialogs', () => {
  it('create and change calendars with a colour of the form #RRGGBB only', async () => {
    mockNative.createCalendar.mockResolvedValue('9');
    await expect(
      createCalendar({ title: 'Shifts', color: '#1565C0' })
    ).resolves.toBe('9');
    expect(mockNative.createCalendar).toHaveBeenCalledWith({
      title: 'Shifts',
      color: '#1565C0',
      sourceId: null,
      accountName: null,
      timeZone: null,
    });
    await expect(
      createCalendar({ title: 'Shifts', color: 'blue' })
    ).rejects.toThrow(RangeError);

    mockNative.updateCalendar.mockResolvedValue();
    await updateCalendar('9', { color: '#2E7D32' });
    expect(mockNative.updateCalendar).toHaveBeenCalledWith('9', {
      title: null,
      color: '#2E7D32',
    });
  });

  it('answer null where the platform has no default calendar', async () => {
    mockNative.getDefaultCalendar.mockResolvedValue(null);
    await expect(getDefaultCalendar()).resolves.toBeNull();
  });

  it('read attendees without empty fields', async () => {
    mockNative.getAttendees.mockResolvedValue([
      {
        name: 'Anna',
        email: null,
        role: 'required',
        status: 'tentative',
        type: 'person',
        isCurrentUser: true,
      },
    ]);
    await expect(getAttendees('7')).resolves.toEqual([
      {
        name: 'Anna',
        role: 'required',
        status: 'tentative',
        type: 'person',
        isCurrentUser: true,
      },
    ]);
  });

  it('ask for write-only access only when told', async () => {
    mockNative.requestPermission.mockResolvedValue('writeOnly');
    await expect(requestPermission({ writeOnly: true })).resolves.toBe(
      'writeOnly'
    );
    await requestPermission();
    expect(mockNative.requestPermission.mock.calls).toEqual([[true], [false]]);
  });

  it('start the system editor with the given fields and pass its outcome on', async () => {
    mockNative.createEventWithDialog.mockResolvedValue({
      action: 'canceled',
      id: null,
    });
    await expect(
      createEventWithDialog({
        title: 'Dentist',
        startDate: local('2026-08-19T09:00:00'),
      })
    ).resolves.toEqual({ action: 'canceled' });
    expect(mockNative.createEventWithDialog).toHaveBeenCalledWith({
      calendarId: null,
      title: 'Dentist',
      startDate: ms('2026-08-19T09:00:00'),
      endDate: null,
      allDay: null,
      location: null,
      notes: null,
      url: null,
    });
  });
});
