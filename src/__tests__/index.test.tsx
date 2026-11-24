import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import type {
  NativeEvent,
  NativeEventInput,
} from '../NativeReactNativeDeviceCalendar';
import Native from '../NativeReactNativeDeviceCalendar';

jest.mock('../NativeReactNativeDeviceCalendar', () => ({
  __esModule: true,
  default: {
    getPermissionStatus: jest.fn(),
    requestPermission: jest.fn(),
    getCalendars: jest.fn(),
    createCalendar: jest.fn(),
    deleteCalendar: jest.fn(),
    getEvents: jest.fn(),
    createEvent: jest.fn(),
    updateEvent: jest.fn(),
    deleteEvent: jest.fn(),
  },
}));
import {
  allDayRange,
  createCalendar,
  createEvent,
  getEvents,
  getPermissionStatus,
  updateEvent,
} from '../index';

// the tests run in Europe/Berlin (see jest.config in package.json), where summer time ends on 25 October 2026
const local = (text: string) => new Date(text);

const mockNative = jest.mocked(Native);

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

  it('count in the day of an end that is not a midnight', () => {
    expect(
      allDayRange(local('2026-08-19T10:00:00'), local('2026-08-20T09:00:00'))
    ).toEqual({
      start: local('2026-08-19T00:00:00'),
      end: local('2026-08-21T00:00:00'),
    });
  });

  it('last at least one day', () => {
    const day = local('2026-08-19T00:00:00');
    expect(allDayRange(day, day)).toEqual({
      start: day,
      end: local('2026-08-20T00:00:00'),
    });
  });

  it('keep their days over the end of summer time, a day of 25 hours', () => {
    expect(
      allDayRange(local('2026-10-25T00:00:00'), local('2026-10-25T00:00:00'))
    ).toEqual({
      start: local('2026-10-25T00:00:00'),
      end: local('2026-10-26T00:00:00'),
    });
    expect(
      local('2026-10-26T00:00:00').getTime() -
        local('2026-10-25T00:00:00').getTime()
    ).toBe(25 * 60 * 60 * 1000);
  });

  it('go to the native side as local midnights', async () => {
    mockNative.createEvent.mockResolvedValue('7');
    await expect(
      createEvent({
        calendarId: '3',
        title: 'Holiday',
        startDate: local('2026-08-19T15:00:00'),
        endDate: local('2026-08-20T00:00:00'),
        allDay: true,
      })
    ).resolves.toBe('7');

    const expected: NativeEventInput = {
      calendarId: '3',
      title: 'Holiday',
      startDate: local('2026-08-19T00:00:00').getTime(),
      endDate: local('2026-08-20T00:00:00').getTime(),
      allDay: true,
      location: null,
      notes: null,
    };
    expect(mockNative.createEvent).toHaveBeenCalledWith(expected);
  });
});

describe('events', () => {
  it('go to the native side as milliseconds', async () => {
    mockNative.updateEvent.mockResolvedValue();
    await updateEvent('7', {
      calendarId: '3',
      title: 'Early shift',
      startDate: local('2026-08-19T06:00:00'),
      endDate: local('2026-08-19T14:00:00'),
      location: 'Berlin',
      notes: 'Reception',
    });

    expect(mockNative.updateEvent).toHaveBeenCalledWith('7', {
      calendarId: '3',
      title: 'Early shift',
      startDate: local('2026-08-19T06:00:00').getTime(),
      endDate: local('2026-08-19T14:00:00').getTime(),
      allDay: false,
      location: 'Berlin',
      notes: 'Reception',
    });
  });

  it('refuse an end before the start', async () => {
    await expect(
      createEvent({
        calendarId: '3',
        title: 'Backwards',
        startDate: local('2026-08-19T14:00:00'),
        endDate: local('2026-08-19T06:00:00'),
      })
    ).rejects.toThrow(RangeError);
    expect(mockNative.createEvent).not.toHaveBeenCalled();
  });

  it('come back with dates, and without empty optional fields', async () => {
    const native: NativeEvent[] = [
      {
        id: '7',
        calendarId: '3',
        title: 'Early shift',
        startDate: local('2026-08-19T06:00:00').getTime(),
        endDate: local('2026-08-19T14:00:00').getTime(),
        allDay: false,
        location: 'Berlin',
        notes: null,
      },
    ];
    mockNative.getEvents.mockResolvedValue(native);

    const events = await getEvents({
      startDate: local('2026-08-19T00:00:00'),
      endDate: local('2026-08-20T00:00:00'),
      calendarIds: ['3'],
    });

    expect(mockNative.getEvents).toHaveBeenCalledWith(
      local('2026-08-19T00:00:00').getTime(),
      local('2026-08-20T00:00:00').getTime(),
      ['3']
    );
    expect(events).toEqual([
      {
        id: '7',
        calendarId: '3',
        title: 'Early shift',
        startDate: local('2026-08-19T06:00:00'),
        endDate: local('2026-08-19T14:00:00'),
        allDay: false,
        location: 'Berlin',
      },
    ]);
  });

  it('are read from every calendar when no calendar is named', async () => {
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
  });

  it('are not read for an empty period', async () => {
    const day = local('2026-08-19T00:00:00');
    await expect(getEvents({ startDate: day, endDate: day })).rejects.toThrow(
      RangeError
    );
  });
});

describe('calendars and permission', () => {
  it('create a calendar with a colour of the form #RRGGBB only', async () => {
    mockNative.createCalendar.mockResolvedValue('9');
    await expect(
      createCalendar({ title: 'Shifts', color: '#1565C0' })
    ).resolves.toBe('9');
    expect(mockNative.createCalendar).toHaveBeenCalledWith({
      title: 'Shifts',
      color: '#1565C0',
      accountName: null,
    });

    await expect(
      createCalendar({ title: 'Shifts', color: 'blue' })
    ).rejects.toThrow(RangeError);
  });

  it('pass the status of the platform through', async () => {
    mockNative.getPermissionStatus.mockResolvedValue('restricted');
    await expect(getPermissionStatus()).resolves.toBe('restricted');
  });
});
