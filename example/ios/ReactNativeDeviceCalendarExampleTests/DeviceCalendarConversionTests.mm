#import <XCTest/XCTest.h>

#import "DeviceCalendarConversion.h"
#import "TestSupport.h"

using namespace devicecalendar;
using JS::NativeReactNativeDeviceCalendar::NativeEventInput;

/** Answers for a participant what the mapping asks; EventKit offers no way to make one, nor to subclass its objects */
@interface FakeParticipant : NSObject
@property (nonatomic, copy) NSURL *fakeURL;
@property (nonatomic, copy) NSString *fakeName;
@property (nonatomic) EKParticipantRole fakeRole;
@property (nonatomic) EKParticipantStatus fakeStatus;
@property (nonatomic) EKParticipantType fakeType;
@property (nonatomic) BOOL fakeCurrentUser;
@end

@implementation FakeParticipant
- (NSURL *)URL { return self.fakeURL; }
- (NSString *)name { return self.fakeName; }
- (EKParticipantRole)participantRole { return self.fakeRole; }
- (EKParticipantStatus)participantStatus { return self.fakeStatus; }
- (EKParticipantType)participantType { return self.fakeType; }
- (BOOL)isCurrentUser { return self.fakeCurrentUser; }
@end

/**
 * The mapping between EventKit and the spec without the calendar database: events live in memory only. The tests run in Berlin
 * time, whose summer time ends on 25 October 2026.
 */
@interface DeviceCalendarConversionTests : XCTestCase
@end

@implementation DeviceCalendarConversionTests {
  EKEventStore *_store;
  NSTimeZone *_zone;
  NSTimeZone *_previousZone;
}

- (void)setUp
{
  // one store for all tests: EventKit hands a process only so many stores
  static EKEventStore *store = [EKEventStore new];
  _store = store;
  _zone = [NSTimeZone timeZoneWithName:@"Europe/Berlin"];
  _previousZone = NSTimeZone.defaultTimeZone;
  NSTimeZone.defaultTimeZone = _zone;
}

- (void)tearDown
{
  NSTimeZone.defaultTimeZone = _previousZone;
}

- (EKEvent *)eventWithInput:(NSDictionary *)input
{
  EKEvent *event = [EKEvent eventWithEventStore:_store];
  XCTAssertNil(ApplyInput(NativeEventInput(input), event, YES));
  return event;
}

- (void)testPermissionNames
{
  XCTAssertEqualObjects(PermissionName(EKAuthorizationStatusNotDetermined), @"undetermined");
  XCTAssertEqualObjects(PermissionName(EKAuthorizationStatusRestricted), @"restricted");
  XCTAssertEqualObjects(PermissionName(EKAuthorizationStatusDenied), @"denied");
  XCTAssertEqualObjects(PermissionName(EKAuthorizationStatusWriteOnly), @"writeOnly");
  XCTAssertEqualObjects(PermissionName(EKAuthorizationStatusFullAccess), @"granted");
}

- (void)testACalendarKeepsItsColourAndKnowsWhetherItIsPrimary
{
  EKCalendar *calendar = [EKCalendar calendarForEntityType:EKEntityTypeEvent eventStore:_store];
  calendar.title = @"Shifts";
  calendar.CGColor = ColorOf(@"#1565C0").CGColor;
  NSDictionary *mapped = CalendarOf(calendar, nil);
  XCTAssertEqualObjects(mapped[@"title"], @"Shifts");
  XCTAssertEqualObjects(mapped[@"color"], @"#1565C0");
  XCTAssertEqualObjects(mapped[@"isPrimary"], @NO);
  XCTAssertEqualObjects(mapped[@"timeZone"], [NSNull null]);
  XCTAssertEqualObjects(CalendarOf(calendar, calendar.calendarIdentifier)[@"isPrimary"], @YES);
}

- (void)testAnAllDayInputEndsWithinItsLastDayAndIsReadBackAsLocalDays
{
  double start = MillisAt(_zone, 2026, 8, 18);
  double end = MillisAt(_zone, 2026, 8, 20);
  EKEvent *event = [self eventWithInput:EventInput(@"", @"Day off", start, end, @{@"allDay": @YES, @"timeZone": @"Asia/Tokyo"})];
  XCTAssertTrue(event.allDay);
  XCTAssertNil(event.timeZone, @"an all-day event floats");
  XCTAssertEqualObjects(event.startDate, DateAt(_zone, 2026, 8, 18));
  XCTAssertEqualObjects(event.endDate, [DateAt(_zone, 2026, 8, 20) dateByAddingTimeInterval:-1]);

  NSDictionary *mapped = EventOf(event);
  XCTAssertEqualObjects(mapped[@"startDate"], @(start));
  XCTAssertEqualObjects(mapped[@"endDate"], @(end));
  XCTAssertEqualObjects(mapped[@"allDay"], @YES);
  XCTAssertEqualObjects(mapped[@"timeZone"], [NSNull null]);
}

- (void)testAnAllDayEventKeepsItsDaysAcrossTheEndOfSummerTime
{
  // 24 to 26 October, a 25-hour day in the middle
  double start = MillisAt(_zone, 2026, 10, 24);
  double end = MillisAt(_zone, 2026, 10, 27);
  NSDictionary *mapped = EventOf([self eventWithInput:EventInput(@"", @"Trip", start, end, @{@"allDay": @YES})]);
  XCTAssertEqualObjects(mapped[@"startDate"], @(start));
  XCTAssertEqualObjects(mapped[@"endDate"], @(end));
}

- (void)testAnAllDayEventThatEndsAtItsStartLastsOneDay
{
  // other apps store a one-day event with the end at its start
  EKEvent *event = [EKEvent eventWithEventStore:_store];
  event.allDay = YES;
  event.startDate = DateAt(_zone, 2026, 8, 18);
  event.endDate = DateAt(_zone, 2026, 8, 18);
  NSDictionary *mapped = EventOf(event);
  XCTAssertEqualObjects(mapped[@"startDate"], @(MillisAt(_zone, 2026, 8, 18)));
  XCTAssertEqualObjects(mapped[@"endDate"], @(MillisAt(_zone, 2026, 8, 19)));
}

- (void)testAnAllDayOccurrenceStartsAtItsLocalMidnight
{
  EKEvent *event = [EKEvent eventWithEventStore:_store];
  event.allDay = YES;
  event.startDate = DateAt(_zone, 2026, 8, 18, 12);
  event.endDate = DateAt(_zone, 2026, 8, 18, 13);
  XCTAssertEqual(OriginalStartOf(event), MillisAt(_zone, 2026, 8, 18));
}

- (void)testATimedInputKeepsItsMomentsAndTimeZone
{
  double start = MillisAt(_zone, 2026, 8, 18, 14);
  double end = MillisAt(_zone, 2026, 8, 18, 22);
  NSDictionary *input = EventInput(@"", @"Late shift", start, end, @{
    @"timeZone": @"America/New_York",
    @"location": @"Gate 3",
    @"notes": @"Bring the scanner",
    @"url": @"https://example.com/shift/1",
  });
  EKEvent *event = [self eventWithInput:input];
  XCTAssertEqualObjects(event.timeZone.name, @"America/New_York");

  NSDictionary *mapped = EventOf(event);
  XCTAssertEqualObjects(mapped[@"title"], @"Late shift");
  XCTAssertEqualObjects(mapped[@"startDate"], @(start));
  XCTAssertEqualObjects(mapped[@"endDate"], @(end));
  XCTAssertEqualObjects(mapped[@"timeZone"], @"America/New_York");
  XCTAssertEqualObjects(mapped[@"location"], @"Gate 3");
  XCTAssertEqualObjects(mapped[@"notes"], @"Bring the scanner");
  XCTAssertEqualObjects(mapped[@"url"], @"https://example.com/shift/1");
  XCTAssertEqualObjects(mapped[@"recurrenceRule"], [NSNull null]);
  XCTAssertEqualObjects(mapped[@"originalStartDate"], [NSNull null]);
}

- (void)testATimedInputWithoutTimeZoneUsesTheDevicesZone
{
  EKEvent *event = [self eventWithInput:EventInput(@"", @"Meeting", MillisAt(_zone, 2026, 8, 18, 9), MillisAt(_zone, 2026, 8, 18, 10))];
  XCTAssertEqualObjects(event.timeZone.name, NSTimeZone.localTimeZone.name);
}

- (void)testEmptyTextsAreReadBackAsNull
{
  EKEvent *event = [self eventWithInput:EventInput(@"", @"Meeting", MillisAt(_zone, 2026, 8, 18, 9), MillisAt(_zone, 2026, 8, 18, 10))];
  event.location = @"";
  NSDictionary *mapped = EventOf(event);
  XCTAssertEqualObjects(mapped[@"location"], [NSNull null]);
  XCTAssertEqualObjects(mapped[@"notes"], [NSNull null]);
  XCTAssertEqualObjects(mapped[@"url"], [NSNull null]);
}

- (void)testAnUnknownTimeZoneIsRefusedAndLeavesTheEventAsItWas
{
  EKEvent *event = [EKEvent eventWithEventStore:_store];
  event.title = @"Before";
  NSDictionary *input = EventInput(@"", @"After", MillisAt(_zone, 2026, 8, 18, 9), MillisAt(_zone, 2026, 8, 18, 10), @{@"timeZone": @"Mars/Olympus"});
  NSString *problem = ApplyInput(NativeEventInput(input), event, YES);
  XCTAssertTrue([problem containsString:@"Mars/Olympus"]);
  XCTAssertEqualObjects(event.title, @"Before");
}

- (void)testAlarmsCountInMinutesFromTheStart
{
  NSDictionary *input = EventInput(@"", @"Meeting", MillisAt(_zone, 2026, 8, 18, 9), MillisAt(_zone, 2026, 8, 18, 10), @{
    @"alarms": @[@{@"relativeOffset": @-15}, @{@"relativeOffset": @-60}],
  });
  EKEvent *event = [self eventWithInput:input];
  NSArray *offsets = [[event.alarms valueForKey:@"relativeOffset"] sortedArrayUsingSelector:@selector(compare:)];
  XCTAssertEqualObjects(offsets, (@[@-3600, @-900]));

  // an alarm at a fixed time counts from the start too; EventKit keeps alarms in an order of its own
  [event addAlarm:[EKAlarm alarmWithAbsoluteDate:DateAt(_zone, 2026, 8, 18, 8, 30)]];
  NSArray *alarms = [EventOf(event)[@"alarms"] valueForKey:@"relativeOffset"];
  XCTAssertEqualObjects([alarms sortedArrayUsingSelector:@selector(compare:)], (@[@-60, @-30, @-15]));
}

- (void)testAnAvailabilityTheCalendarDoesNotOfferIsLeftOut
{
  // an event without calendar offers none, as a local calendar
  EKEvent *event = [EKEvent eventWithEventStore:_store];
  NSDictionary *input = EventInput(@"", @"Meeting", MillisAt(_zone, 2026, 8, 18, 9), MillisAt(_zone, 2026, 8, 18, 10), @{@"availability": @"free"});
  XCTAssertNil(ApplyInput(NativeEventInput(input), event, YES));
  XCTAssertEqualObjects(EventOf(event)[@"availability"], @"notSupported");
}

- (void)testAWeeklyRuleWithDaysIsWrittenAndReadBack
{
  NSDictionary *rule = RuleInput(@"weekly", @{
    @"interval": @2,
    @"occurrence": @6,
    @"daysOfTheWeek": @[@{@"day": @(EKWeekdayMonday), @"weekNumber": @0}, @{@"day": @(EKWeekdayWednesday), @"weekNumber": @0}],
  });
  EKEvent *event = [self eventWithInput:EventInput(@"", @"Training", MillisAt(_zone, 2026, 8, 17, 7), MillisAt(_zone, 2026, 8, 17, 8), @{@"recurrenceRule": rule})];
  EKRecurrenceRule *written = event.recurrenceRules.firstObject;
  XCTAssertEqual(written.frequency, EKRecurrenceFrequencyWeekly);
  XCTAssertEqual(written.interval, 2);
  XCTAssertEqual(written.recurrenceEnd.occurrenceCount, 6u);
  XCTAssertEqualObjects([written.daysOfTheWeek valueForKey:@"dayOfTheWeek"], (@[@(EKWeekdayMonday), @(EKWeekdayWednesday)]));

  NSDictionary *read = EventOf(event)[@"recurrenceRule"];
  XCTAssertEqualObjects(read[@"frequency"], @"weekly");
  XCTAssertEqualObjects(read[@"interval"], @2);
  XCTAssertEqualObjects(read[@"occurrence"], @6);
  XCTAssertEqualObjects(read[@"endDate"], [NSNull null]);
  XCTAssertEqualObjects(read[@"daysOfTheWeek"], (@[@{@"day": @2, @"weekNumber": @0}, @{@"day": @4, @"weekNumber": @0}]));
  XCTAssertEqualObjects(read[@"setPositions"], @[]);
}

- (void)testAMonthlyRuleOnTheLastFridayEndsAtItsDate
{
  double until = MillisAt(_zone, 2026, 12, 31);
  NSDictionary *rule = RuleInput(@"monthly", @{
    @"endDate": @(until),
    @"daysOfTheWeek": @[@{@"day": @(EKWeekdayFriday), @"weekNumber": @0}],
    @"setPositions": @[@-1],
  });
  EKEvent *event = [self eventWithInput:EventInput(@"", @"Inventory", MillisAt(_zone, 2026, 8, 28, 7), MillisAt(_zone, 2026, 8, 28, 9), @{@"recurrenceRule": rule})];
  EKRecurrenceRule *written = event.recurrenceRules.firstObject;
  XCTAssertEqual(written.frequency, EKRecurrenceFrequencyMonthly);
  XCTAssertEqualObjects(written.setPositions, @[@-1]);
  XCTAssertEqualObjects(written.recurrenceEnd.endDate, DateAt(_zone, 2026, 12, 31));

  NSDictionary *read = EventOf(event)[@"recurrenceRule"];
  XCTAssertEqualObjects(read[@"frequency"], @"monthly");
  XCTAssertEqualObjects(read[@"endDate"], @(until));
  XCTAssertEqualObjects(read[@"occurrence"], [NSNull null]);
  XCTAssertEqualObjects(read[@"setPositions"], @[@-1]);
}

- (void)testAYearlyRuleKeepsItsMonthsAndDays
{
  NSDictionary *rule = RuleInput(@"yearly", @{@"monthsOfTheYear": @[@3, @9], @"daysOfTheMonth": @[@1]});
  EKEvent *event = [self eventWithInput:EventInput(@"", @"Audit", MillisAt(_zone, 2026, 9, 1, 8), MillisAt(_zone, 2026, 9, 1, 9), @{@"recurrenceRule": rule})];
  NSDictionary *read = EventOf(event)[@"recurrenceRule"];
  XCTAssertEqualObjects(read[@"frequency"], @"yearly");
  XCTAssertEqualObjects(read[@"monthsOfTheYear"], (@[@3, @9]));
  XCTAssertEqualObjects(read[@"daysOfTheMonth"], @[@1]);
}

- (void)testAnUnchangedRuleStaysAndAChangedOneIsReplaced
{
  NSDictionary *input = EventInput(@"", @"Stand-up", MillisAt(_zone, 2026, 8, 17, 8), MillisAt(_zone, 2026, 8, 17, 8, 15), @{
    @"recurrenceRule": RuleInput(@"daily", @{@"occurrence": @5}),
  });
  EKEvent *event = [self eventWithInput:input];
  EKRecurrenceRule *first = event.recurrenceRules.firstObject;
  XCTAssertNil(ApplyInput(NativeEventInput(input), event, YES));
  XCTAssertEqual(event.recurrenceRules.firstObject, first, @"the same rule is not written anew");

  NSMutableDictionary *changed = [input mutableCopy];
  changed[@"recurrenceRule"] = RuleInput(@"daily", @{@"occurrence": @3});
  XCTAssertNil(ApplyInput(NativeEventInput(changed), event, YES));
  XCTAssertEqual(event.recurrenceRules.firstObject.recurrenceEnd.occurrenceCount, 3u);

  // a single occurrence keeps the rule of its series
  NSMutableDictionary *none = [input mutableCopy];
  [none removeObjectForKey:@"recurrenceRule"];
  XCTAssertNil(ApplyInput(NativeEventInput(none), event, NO));
  XCTAssertEqual(event.recurrenceRules.count, 1u);
  XCTAssertNil(ApplyInput(NativeEventInput(none), event, YES));
  XCTAssertEqual(event.recurrenceRules.count, 0u, @"without rule the event no longer repeats");
}

- (void)testAttendeesAreMappedWithTheirAddressRoleStatusAndType
{
  FakeParticipant *participant = [FakeParticipant new];
  participant.fakeURL = [NSURL URLWithString:@"mailto:mara.lindqvist%40example.com"];
  participant.fakeName = @"Mara Lindqvist";
  participant.fakeRole = EKParticipantRoleChair;
  participant.fakeStatus = EKParticipantStatusTentative;
  participant.fakeType = EKParticipantTypePerson;
  participant.fakeCurrentUser = YES;
  XCTAssertEqualObjects(AttendeeOf((EKParticipant *)participant), (@{
    @"name": @"Mara Lindqvist",
    @"email": @"mara.lindqvist@example.com",
    @"role": @"chair",
    @"status": @"tentative",
    @"type": @"person",
    @"isCurrentUser": @YES,
  }));

  FakeParticipant *room = [FakeParticipant new];
  room.fakeURL = [NSURL URLWithString:@"urn:uuid:4F1C"];
  room.fakeRole = EKParticipantRoleNonParticipant;
  room.fakeStatus = EKParticipantStatusInProcess;
  room.fakeType = EKParticipantTypeRoom;
  NSDictionary *mapped = AttendeeOf((EKParticipant *)room);
  XCTAssertEqualObjects(mapped[@"name"], [NSNull null]);
  XCTAssertEqualObjects(mapped[@"email"], [NSNull null], @"only a mailto address is an email");
  XCTAssertEqualObjects(mapped[@"role"], @"nonParticipant");
  XCTAssertEqualObjects(mapped[@"status"], @"inProcess");
  XCTAssertEqualObjects(mapped[@"type"], @"room");
}

@end
