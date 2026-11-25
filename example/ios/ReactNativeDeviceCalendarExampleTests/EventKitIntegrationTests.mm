#import <EventKit/EventKit.h>
#import <XCTest/XCTest.h>

#import "ReactNativeDeviceCalendar.h"
#import "TestSupport.h"

namespace spec = JS::NativeReactNativeDeviceCalendar;

/**
 * The module against the calendar database of the simulator: what EventKit stores and what it expands into occurrences. The tests
 * need full access for the example app, which example/ios/grant-calendar-access.sh gives a simulator, and skip without it. They
 * work in a local calendar of their own, created before and deleted after each test, in the week of 15 to 22 March 2027 in the
 * device's time zone.
 */
@interface EventKitIntegrationTests : XCTestCase
@end

// one module and one store for all tests: EventKit hands a process only so many stores
static ReactNativeDeviceCalendar *SharedModule;
static EKEventStore *SharedStore;

@implementation EventKitIntegrationTests {
  ReactNativeDeviceCalendar *_module;
  NSTimeZone *_zone;
  NSString *_calendarId;
}

/** Runs a call of the module and returns what it resolved; fails the test when it rejected */
- (id)call:(void (^)(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject))body
{
  XCTestExpectation *done = [self expectationWithDescription:@"the call ends"];
  __block id result = nil;
  body(
      ^(id value) {
        result = value;
        [done fulfill];
      },
      ^(NSString *code, NSString *message, NSError *error) {
        XCTFail(@"%@: %@", code, message);
        [done fulfill];
      });
  [self waitForExpectations:@[done] timeout:10];
  return result;
}

- (void)setUp
{
  if ([EKEventStore authorizationStatusForEntityType:EKEntityTypeEvent] != EKAuthorizationStatusFullAccess) {
    XCTSkip(@"The example app has no full access to the calendars of this simulator");
  }
  if (SharedStore == nil) {
    // a granted access reaches EventKit only once the app asks for it, which the system then answers without a dialog
    SharedStore = [EKEventStore new];
    XCTestExpectation *asked = [self expectationWithDescription:@"access is asked for"];
    [SharedStore requestFullAccessToEventsWithCompletion:^(BOOL granted, NSError *error) {
      [asked fulfill];
    }];
    [self waitForExpectations:@[asked] timeout:10];
    SharedModule = [ReactNativeDeviceCalendar new];
  }
  _module = SharedModule;
  _zone = NSTimeZone.localTimeZone;
  __block spec::NativeCalendarInput calendar(@{@"title": @"Device Calendar Tests", @"color": @"#2E7D32"});
  _calendarId = [self call:^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [self->_module createCalendar:calendar resolve:resolve reject:reject];
  }];
  XCTAssertNotNil(_calendarId);
}

- (void)tearDown
{
  if (_calendarId == nil) return;
  [self call:^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [self->_module deleteCalendar:self->_calendarId resolve:resolve reject:reject];
  }];
}

- (double)at:(NSInteger)day hour:(NSInteger)hour minute:(NSInteger)minute
{
  return MillisAt(_zone, 2027, 3, day, hour, minute);
}

- (NSString *)create:(NSDictionary *)input
{
  __block spec::NativeEventInput event(input);
  return [self call:^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [self->_module createEvent:event resolve:resolve reject:reject];
  }];
}

/** The events of the week, ordered by start */
- (NSArray<NSDictionary *> *)week
{
  return [self call:^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [self->_module getEvents:[self at:15 hour:0 minute:0] endDate:[self at:22 hour:0 minute:0] calendarIds:@[self->_calendarId] resolve:resolve reject:reject];
  }];
}

- (NSString *)update:(NSString *)eventId input:(NSDictionary *)input span:(NSString *)span occurrence:(NSNumber *)occurrence
{
  __block spec::NativeEventInput event(input);
  return [self call:^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [self->_module updateEvent:eventId event:event span:span occurrence:occurrence resolve:resolve reject:reject];
  }];
}

- (NSNumber *)remove:(NSString *)eventId span:(NSString *)span occurrence:(NSNumber *)occurrence
{
  return [self call:^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [self->_module deleteEvent:eventId span:span occurrence:occurrence resolve:resolve reject:reject];
  }];
}

/** A daily stand-up from Monday 15 March, five times */
- (NSDictionary *)standUp
{
  return EventInput(_calendarId, @"Stand-up", [self at:15 hour:8 minute:0], [self at:15 hour:8 minute:15], @{
    @"recurrenceRule": RuleInput(@"daily", @{@"occurrence": @5}),
  });
}

- (NSArray *)titlesAndDays:(NSArray<NSDictionary *> *)events
{
  NSMutableArray *result = [NSMutableArray new];
  NSCalendar *calendar = NSCalendar.currentCalendar;
  for (NSDictionary *event in events) {
    NSDate *start = [NSDate dateWithTimeIntervalSince1970:[event[@"startDate"] doubleValue] / 1000.0];
    [result addObject:[NSString stringWithFormat:@"%@ %ld", event[@"title"], (long)[calendar component:NSCalendarUnitDay fromDate:start]]];
  }
  return result;
}

- (void)testATimedEventKeepsItsFieldsAndAlarms
{
  NSString *eventId = [self create:EventInput(_calendarId, @"Planning", [self at:16 hour:9 minute:0], [self at:16 hour:10 minute:0], @{
    @"location": @"Room 2",
    @"alarms": @[@{@"relativeOffset": @-15}],
  })];
  NSDictionary *event = [self call:^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [self->_module getEvent:eventId occurrence:nil resolve:resolve reject:reject];
  }];
  XCTAssertEqualObjects(event[@"id"], eventId);
  XCTAssertEqualObjects(event[@"calendarId"], _calendarId);
  XCTAssertEqualObjects(event[@"title"], @"Planning");
  XCTAssertEqualObjects(event[@"location"], @"Room 2");
  XCTAssertEqualObjects(event[@"startDate"], @([self at:16 hour:9 minute:0]));
  XCTAssertEqualObjects(event[@"endDate"], @([self at:16 hour:10 minute:0]));
  XCTAssertEqualObjects([event[@"alarms"] valueForKey:@"relativeOffset"], @[@-15]);
  XCTAssertEqualObjects(event[@"timeZone"], _zone.name);
}

- (void)testAnAllDayEventIsStoredWithinItsDaysAndReadBackAsLocalDays
{
  double start = [self at:17 hour:0 minute:0];
  double end = [self at:19 hour:0 minute:0];
  NSString *eventId = [self create:EventInput(_calendarId, @"Day off", start, end, @{@"allDay": @YES})];

  // the test's store, emptied, reads what the database holds
  [SharedStore reset];
  EKEvent *stored = [SharedStore eventWithIdentifier:eventId];
  XCTAssertTrue(stored.allDay);
  NSCalendar *calendar = NSCalendar.currentCalendar;
  XCTAssertEqual([calendar component:NSCalendarUnitDay fromDate:stored.startDate], 17);
  XCTAssertEqual([calendar component:NSCalendarUnitDay fromDate:stored.endDate], 18);

  NSArray<NSDictionary *> *week = [self week];
  XCTAssertEqual(week.count, 1u);
  XCTAssertEqualObjects(week[0][@"allDay"], @YES);
  XCTAssertEqualObjects(week[0][@"startDate"], @(start));
  XCTAssertEqualObjects(week[0][@"endDate"], @(end));
}

- (void)testARuleExpandsIntoItsOccurrences
{
  [self create:EventInput(_calendarId, @"Training", [self at:15 hour:7 minute:0], [self at:15 hour:8 minute:0], @{
    @"recurrenceRule": RuleInput(@"weekly", @{
      @"occurrence": @4,
      @"daysOfTheWeek": @[@{@"day": @(EKWeekdayMonday), @"weekNumber": @0}, @{@"day": @(EKWeekdayThursday), @"weekNumber": @0}],
    }),
  })];
  NSArray<NSDictionary *> *week = [self week];
  XCTAssertEqualObjects([self titlesAndDays:week], (@[@"Training 15", @"Training 18"]));
  XCTAssertEqualObjects(week[1][@"originalStartDate"], @([self at:18 hour:7 minute:0]));
  XCTAssertEqualObjects(week[1][@"recurrenceRule"][@"frequency"], @"weekly");
}

- (void)testAnOccurrenceIsFoundByItsOriginalStart
{
  NSString *eventId = [self create:[self standUp]];
  NSDictionary *third = [self call:^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [self->_module getEvent:eventId occurrence:@([self at:17 hour:8 minute:0]) resolve:resolve reject:reject];
  }];
  XCTAssertEqualObjects(third[@"startDate"], @([self at:17 hour:8 minute:0]));
  XCTAssertEqualObjects(third[@"originalStartDate"], @([self at:17 hour:8 minute:0]));
}

- (void)testChangingOneOccurrenceKeepsTheOtherOccurrences
{
  NSString *eventId = [self create:[self standUp]];
  NSDictionary *later = EventInput(_calendarId, @"Stand-up (later)", [self at:16 hour:9 minute:0], [self at:16 hour:9 minute:15]);
  [self update:eventId input:later span:@"this" occurrence:@([self at:16 hour:8 minute:0])];

  NSArray<NSDictionary *> *week = [self week];
  XCTAssertEqualObjects([self titlesAndDays:week], (@[@"Stand-up 15", @"Stand-up (later) 16", @"Stand-up 17", @"Stand-up 18", @"Stand-up 19"]));
  XCTAssertEqualObjects(week[1][@"isDetached"], @YES);
  XCTAssertEqualObjects(week[1][@"startDate"], @([self at:16 hour:9 minute:0]));
  XCTAssertEqualObjects(week[1][@"originalStartDate"], @([self at:16 hour:8 minute:0]));
}

- (void)testChangingTheFutureOccurrencesSplitsTheSeriesAndKeepsItsCount
{
  NSString *eventId = [self create:[self standUp]];
  NSMutableDictionary *renamed = [[self standUp] mutableCopy];
  renamed[@"title"] = @"Daily sync";
  renamed[@"startDate"] = @([self at:17 hour:8 minute:0]);
  renamed[@"endDate"] = @([self at:17 hour:8 minute:15]);
  NSString *newId = [self update:eventId input:renamed span:@"future" occurrence:@([self at:17 hour:8 minute:0])];
  XCTAssertNotNil(newId);
  XCTAssertNotEqualObjects(newId, eventId, @"the future occurrences are a series of their own");

  NSArray<NSDictionary *> *week = [self week];
  XCTAssertEqualObjects([self titlesAndDays:week], (@[@"Stand-up 15", @"Stand-up 16", @"Daily sync 17", @"Daily sync 18", @"Daily sync 19"]));
  XCTAssertEqualObjects(week[2][@"id"], newId);
  XCTAssertEqualObjects(week[0][@"id"], eventId);
}

- (void)testChangingTheWholeSeriesRenamesEveryOccurrence
{
  NSString *eventId = [self create:[self standUp]];
  NSMutableDictionary *renamed = [[self standUp] mutableCopy];
  renamed[@"title"] = @"Daily sync";
  XCTAssertEqualObjects([self update:eventId input:renamed span:@"all" occurrence:nil], eventId);
  NSArray *titles = [[self week] valueForKey:@"title"];
  XCTAssertEqualObjects(titles, (@[@"Daily sync", @"Daily sync", @"Daily sync", @"Daily sync", @"Daily sync"]));
}

- (void)testDeletingOneOccurrenceAndTheFutureOnesLeavesTheRest
{
  NSString *eventId = [self create:[self standUp]];
  XCTAssertEqualObjects([self remove:eventId span:@"this" occurrence:@([self at:16 hour:8 minute:0])], @YES);
  XCTAssertEqualObjects([self titlesAndDays:[self week]], (@[@"Stand-up 15", @"Stand-up 17", @"Stand-up 18", @"Stand-up 19"]));

  XCTAssertEqualObjects([self remove:eventId span:@"future" occurrence:@([self at:18 hour:8 minute:0])], @YES);
  XCTAssertEqualObjects([self titlesAndDays:[self week]], (@[@"Stand-up 15", @"Stand-up 17"]));
}

- (void)testAnEventCannotMoveToAnotherCalendar
{
  NSString *eventId = [self create:EventInput(_calendarId, @"Planning", [self at:16 hour:9 minute:0], [self at:16 hour:10 minute:0])];
  spec::NativeEventInput moved(EventInput(@"another", @"Planning", [self at:16 hour:9 minute:0], [self at:16 hour:10 minute:0]));
  XCTestExpectation *rejected = [self expectationWithDescription:@"the call is rejected"];
  [_module updateEvent:eventId
                 event:moved
                  span:@"all"
            occurrence:nil
               resolve:^(id value) {
                 XCTFail(@"the event moved");
               }
                reject:^(NSString *code, NSString *message, NSError *error) {
                  XCTAssertEqualObjects(code, @"E_CALENDAR_CHANGE");
                  [rejected fulfill];
                }];
  [self waitForExpectations:@[rejected] timeout:10];
}

- (void)testDeletingTheCalendarTakesItsEventsWith
{
  NSString *eventId = [self create:EventInput(_calendarId, @"Planning", [self at:16 hour:9 minute:0], [self at:16 hour:10 minute:0])];
  XCTAssertEqualObjects([self call:^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [self->_module deleteCalendar:self->_calendarId resolve:resolve reject:reject];
  }], @YES);
  _calendarId = nil;
  [SharedStore reset];
  XCTAssertNil([SharedStore eventWithIdentifier:eventId]);
}

@end
