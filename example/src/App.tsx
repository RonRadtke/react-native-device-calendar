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
  deleteCalendar,
  getCalendars,
  getEvents,
  getPermissionStatus,
  requestPermission,
  updateEvent,
  type CalendarEvent,
} from '@ronradtke/react-native-device-calendar';

const DEMO_TITLE = 'Device Calendar Example';

function describe(event: CalendarEvent): string {
  const when = event.allDay
    ? `${event.startDate.toDateString()} – ${event.endDate.toDateString()} (all day)`
    : `${event.startDate.toLocaleString()} – ${event.endDate.toLocaleTimeString()}`;
  return `${event.title}: ${when}`;
}

/** Runs every call of the library against the calendars of this device */
export default function App() {
  const [lines, setLines] = useState<string[]>([]);
  const [calendarId, setCalendarId] = useState<string | null>(null);
  const [eventId, setEventId] = useState<string | null>(null);

  const log = (line: string) => setLines((previous) => [line, ...previous]);
  const run = (action: () => Promise<void>) => () => {
    action().catch((error: Error) => log(`Error: ${error.message}`));
  };

  const today = new Date();
  const at = (days: number, hours: number) =>
    new Date(
      today.getFullYear(),
      today.getMonth(),
      today.getDate() + days,
      hours
    );

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
            const meeting = await createEvent({
              calendarId: calendarId!,
              title: 'Planning meeting',
              startDate: at(0, 9),
              endDate: at(0, 10),
              location: 'Room 2',
              notes: 'Written by the example app',
            });
            setEventId(meeting);
            const holiday = await createEvent({
              calendarId: calendarId!,
              title: 'Day off',
              startDate: at(1, 0),
              endDate: at(3, 0),
              allDay: true,
            });
            log(`Events ${meeting} and ${holiday} created`);
          })}
        />
        <Button
          title="Move meeting"
          disabled={eventId === null}
          onPress={run(async () => {
            await updateEvent(eventId!, {
              calendarId: calendarId!,
              title: 'Planning meeting (moved)',
              startDate: at(0, 11),
              endDate: at(0, 12),
            });
            log(`Event ${eventId} moved`);
          })}
        />
        <Button
          title="Events"
          disabled={calendarId === null}
          onPress={run(async () => {
            const events = await getEvents({
              startDate: at(-1, 0),
              endDate: at(7, 0),
              calendarIds: [calendarId!],
            });
            log(`${events.length} events`);
            events.forEach((event) => log(describe(event)));
          })}
        />
        <Button
          title="Delete calendar"
          disabled={calendarId === null}
          onPress={run(async () => {
            log(`Deleted: ${await deleteCalendar(calendarId!)}`);
            setCalendarId(null);
            setEventId(null);
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
