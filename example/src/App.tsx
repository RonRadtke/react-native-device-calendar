import { useState } from 'react';
import {
  Button,
  Platform,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {
  createCalendar,
  createEvent,
  createEventWithDialog,
  deleteCalendar,
  deleteEvent,
  getCalendars,
  getEvents,
  getPermissionStatus,
  getSources,
  openEvent,
  requestPermission,
  updateEvent,
  type CalendarEvent,
} from '@ronradtke/react-native-device-calendar';

const DEMO_TITLE = 'Device Calendar Example';

function describe(event: CalendarEvent): string {
  const when = event.allDay
    ? `${event.startDate.toDateString()} – ${event.endDate.toDateString()} (all day)`
    : `${event.startDate.toLocaleString()} – ${event.endDate.toLocaleTimeString()}`;
  const repeats = event.recurrenceRule
    ? `, every ${event.recurrenceRule.interval ?? 1} ${event.recurrenceRule.frequency}`
    : '';
  const alarms = event.alarms.length
    ? `, alarms ${event.alarms.map((alarm) => alarm.relativeOffset).join('/')} min`
    : '';
  return `${event.title}: ${when}${repeats}${alarms}${event.isDetached ? ', changed' : ''}`;
}

/** Runs the calls of the library against the calendars of this device */
export default function App() {
  const [lines, setLines] = useState<string[]>([]);
  const [calendarId, setCalendarId] = useState<string | null>(null);
  const [events, setEvents] = useState<CalendarEvent[]>([]);

  const log = (line: string) => setLines((previous) => [line, ...previous]);
  const run = (action: () => Promise<void>) => () => {
    action().catch((error: Error & { code?: string }) =>
      log(`Error ${error.code ?? ''}: ${error.message}`)
    );
  };

  const today = new Date();
  const at = (days: number, hours: number) =>
    new Date(
      today.getFullYear(),
      today.getMonth(),
      today.getDate() + days,
      hours
    );
  const standups = events.filter((event) => event.recurrenceRule);

  const read = async () => {
    const found = await getEvents({
      startDate: at(-1, 0),
      endDate: at(14, 0),
      calendarIds: [calendarId!],
    });
    setEvents(found);
    log(`${found.length} events`);
    found.forEach((event) => log(describe(event)));
  };

  return (
    <View style={styles.screen}>
      <View style={styles.buttons}>
        <Button
          title="Permission"
          onPress={run(async () =>
            log(`Status: ${await getPermissionStatus()}`)
          )}
        />
        <Button
          title="Ask"
          onPress={run(async () =>
            log(`Request: ${await requestPermission()}`)
          )}
        />
        <Button
          title="Sources"
          onPress={run(async () => {
            for (const source of await getSources()) {
              log(`Source ${source.name} (${source.type})`);
            }
          })}
        />
        <Button
          title="Calendars"
          onPress={run(async () => {
            for (const calendar of await getCalendars()) {
              log(
                `${calendar.title} (${calendar.source}, ${calendar.sourceType})` +
                  `${calendar.isPrimary ? ', primary' : ''}${calendar.allowsModifications ? '' : ', read-only'}`
              );
            }
          })}
        />
        <Button
          title="Create calendar"
          onPress={run(async () => {
            const id = await createCalendar({
              title: DEMO_TITLE,
              color: '#1565C0',
            });
            setCalendarId(id);
            log(`Calendar ${id} created`);
          })}
        />
        <Button
          title="Add events"
          disabled={calendarId === null}
          onPress={run(async () => {
            await createEvent({
              calendarId: calendarId!,
              title: 'Planning meeting',
              startDate: at(0, 9),
              endDate: at(0, 10),
              location: 'Room 2',
              alarms: [{ relativeOffset: -15 }],
            });
            await createEvent({
              calendarId: calendarId!,
              title: 'Day off',
              startDate: at(1, 0),
              endDate: at(3, 0),
              allDay: true,
            });
            await createEvent({
              calendarId: calendarId!,
              title: 'Stand-up',
              startDate: at(0, 8),
              endDate: at(0, 8.25),
              recurrenceRule: { frequency: 'daily', occurrence: 5 },
            });
            log('Three events created');
          })}
        />
        <Button
          title="Events"
          disabled={calendarId === null}
          onPress={run(read)}
        />
        <Button
          title="Move 2nd stand-up"
          disabled={standups.length < 2}
          onPress={run(async () => {
            const second = standups[1]!;
            const id = await updateEvent(
              second.id,
              {
                title: 'Stand-up (later)',
                startDate: at(1, 9),
                endDate: at(1, 9.25),
              },
              { occurrence: second.originalStartDate! }
            );
            log(`Occurrence changed as event ${id}`);
          })}
        />
        <Button
          title="End stand-ups"
          disabled={standups.length < 4}
          onPress={run(async () => {
            const fourth = standups[3]!;
            await deleteEvent(fourth.id, {
              occurrence: fourth.originalStartDate!,
              futureEvents: true,
            });
            log('Stand-ups from the 4th on deleted');
          })}
        />
        <Button
          title="Open first"
          disabled={events.length === 0}
          onPress={run(async () =>
            log(
              `View closed: ${await openEvent(events[0]!.id, { allowsEditing: true })}`
            )
          )}
        />
        <Button
          title="Editor"
          onPress={run(async () => {
            const result = await createEventWithDialog({
              title: 'Dentist',
              startDate: at(2, 14),
              endDate: at(2, 15),
            });
            log(`Editor: ${result.action}${result.id ? ` ${result.id}` : ''}`);
          })}
        />
        <Button
          title="Delete calendar"
          disabled={calendarId === null}
          onPress={run(async () => {
            log(`Deleted: ${await deleteCalendar(calendarId!)}`);
            setCalendarId(null);
            setEvents([]);
          })}
        />
      </View>
      <ScrollView style={styles.log} testID="log">
        {lines.map((line, index) => (
          <Text key={index} style={styles.line}>
            {line}
          </Text>
        ))}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  // below the status bar, which the app draws under
  screen: {
    flex: 1,
    paddingTop: Platform.OS === 'android' ? StatusBar.currentHeight : 60,
  },
  buttons: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    padding: 12,
  },
  log: { flex: 1, paddingHorizontal: 12 },
  line: { paddingVertical: 4 },
});
