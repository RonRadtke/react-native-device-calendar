#import "ReactNativeDeviceCalendar.h"

#import <EventKit/EventKit.h>
#import <UIKit/UIKit.h>

#include <cmath>

/** The permission as the library names it; write-only access cannot read events and counts as refused */
static NSString *PermissionName(EKAuthorizationStatus status)
{
  if (status == EKAuthorizationStatusNotDetermined) return @"undetermined";
  if (status == EKAuthorizationStatusRestricted) return @"restricted";
  if (status == EKAuthorizationStatusDenied) return @"denied";
  if (@available(iOS 17.0, *)) {
    return status == EKAuthorizationStatusFullAccess ? @"granted" : @"denied";
  }
  // before iOS 17 the remaining status is "authorized", which is full access
  return @"granted";
}

static NSString *SourceTypeName(EKSourceType type)
{
  switch (type) {
    case EKSourceTypeLocal: return @"local";
    case EKSourceTypeExchange: return @"exchange";
    case EKSourceTypeCalDAV: return @"caldav";
    case EKSourceTypeMobileMe: return @"mobileme";
    case EKSourceTypeSubscribed: return @"subscribed";
    case EKSourceTypeBirthdays: return @"birthdays";
  }
  return @"other";
}

static NSString *HexOf(CGColorRef color)
{
  CGFloat red = 0, green = 0, blue = 0, alpha = 0;
  if (color == NULL || ![[UIColor colorWithCGColor:color] getRed:&red green:&green blue:&blue alpha:&alpha]) return @"#000000";
  auto byte = [](CGFloat value) { return (int)std::lround(std::fmin(std::fmax(value, 0.0), 1.0) * 255.0); };
  return [NSString stringWithFormat:@"#%02X%02X%02X", byte(red), byte(green), byte(blue)];
}

/** A colour of the form #RRGGBB, which the JavaScript side has checked */
static UIColor *ColorOf(NSString *hex)
{
  unsigned int rgb = 0;
  [[NSScanner scannerWithString:[hex substringFromIndex:1]] scanHexInt:&rgb];
  return [UIColor colorWithRed:((rgb >> 16) & 0xFF) / 255.0 green:((rgb >> 8) & 0xFF) / 255.0 blue:(rgb & 0xFF) / 255.0 alpha:1.0];
}

static NSDate *DateOf(double millis)
{
  return [NSDate dateWithTimeIntervalSince1970:millis / 1000.0];
}

static NSNumber *MillisOf(NSDate *date)
{
  return @(std::floor(date.timeIntervalSince1970 * 1000.0));
}

/** An event in local time; an all-day event from local midnight of its first day to the local midnight after its last */
static NSDictionary *EventOf(EKEvent *event)
{
  NSDate *start = event.startDate;
  NSDate *end = event.endDate;
  if (event.allDay) {
    NSCalendar *calendar = NSCalendar.currentCalendar;
    start = [calendar startOfDayForDate:event.startDate];
    // EventKit ends an all-day event within its last day; one second back also finds that day when it ends at the next midnight
    NSDate *last = [event.endDate compare:event.startDate] == NSOrderedDescending ? [event.endDate dateByAddingTimeInterval:-1] : event.startDate;
    end = [calendar dateByAddingUnit:NSCalendarUnitDay value:1 toDate:[calendar startOfDayForDate:last] options:0];
  }
  return @{
    @"id": event.eventIdentifier ?: @"",
    @"calendarId": event.calendar.calendarIdentifier ?: @"",
    @"title": event.title ?: @"",
    @"startDate": MillisOf(start),
    @"endDate": MillisOf(end),
    @"allDay": @(event.allDay),
    @"location": event.location.length > 0 ? event.location : (id)[NSNull null],
    @"notes": event.notes.length > 0 ? event.notes : (id)[NSNull null],
  };
}

/** The device calendars through EventKit */
@implementation ReactNativeDeviceCalendar {
  EKEventStore *_store;
}

- (instancetype)init
{
  if (self = [super init]) {
    _store = [EKEventStore new];
  }
  return self;
}

+ (BOOL)requiresMainQueueSetup
{
  return NO;
}

/** Whether the app has full access; rejects the call when it has not */
- (BOOL)hasAccess:(RCTPromiseRejectBlock)reject
{
  if ([PermissionName([EKEventStore authorizationStatusForEntityType:EKEntityTypeEvent]) isEqualToString:@"granted"]) return YES;
  reject(@"E_PERMISSION", @"The app has no access to the calendars", nil);
  return NO;
}

- (void)getPermissionStatus:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  resolve(PermissionName([EKEventStore authorizationStatusForEntityType:EKEntityTypeEvent]));
}

- (void)requestPermission:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  EKAuthorizationStatus status = [EKEventStore authorizationStatusForEntityType:EKEntityTypeEvent];
  BOOL askable = status == EKAuthorizationStatusNotDetermined;
  if (@available(iOS 17.0, *)) {
    // write-only access can still be raised to full access
    askable = askable || status == EKAuthorizationStatusWriteOnly;
  }
  if (!askable) {
    resolve(PermissionName(status));
    return;
  }
  EKEventStore *store = _store;
  void (^done)(BOOL, NSError *) = ^(BOOL granted, NSError *error) {
    // the store reads the calendars it may now see
    [store reset];
    resolve(PermissionName([EKEventStore authorizationStatusForEntityType:EKEntityTypeEvent]));
  };
  if (@available(iOS 17.0, *)) {
    [_store requestFullAccessToEventsWithCompletion:done];
  } else {
    [_store requestAccessToEntityType:EKEntityTypeEvent completion:done];
  }
}

- (void)getCalendars:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  NSString *primary = _store.defaultCalendarForNewEvents.calendarIdentifier;
  NSMutableArray *calendars = [NSMutableArray new];
  for (EKCalendar *calendar in [_store calendarsForEntityType:EKEntityTypeEvent]) {
    [calendars addObject:@{
      @"id": calendar.calendarIdentifier,
      @"title": calendar.title ?: @"",
      @"color": HexOf(calendar.CGColor),
      @"source": calendar.source.title ?: @"",
      @"sourceType": SourceTypeName(calendar.source.sourceType),
      @"isPrimary": @([calendar.calendarIdentifier isEqualToString:primary]),
      @"allowsModifications": @(calendar.allowsContentModifications),
    }];
  }
  resolve(calendars);
}

/** Where a calendar of the app goes: iCloud when the person uses it, else the account of the default calendar, else the device */
- (EKSource *)sourceForNewCalendar
{
  NSArray<EKSource *> *sources = _store.sources;
  for (EKSource *source in sources) {
    if (source.sourceType == EKSourceTypeCalDAV && [source.title isEqualToString:@"iCloud"]) return source;
  }
  EKSource *standard = _store.defaultCalendarForNewEvents.source;
  if (standard.sourceType == EKSourceTypeLocal || standard.sourceType == EKSourceTypeCalDAV) return standard;
  for (EKSource *source in sources) {
    if (source.sourceType == EKSourceTypeLocal) return source;
  }
  return standard;
}

- (void)createCalendar:(JS::NativeReactNativeDeviceCalendar::NativeCalendarInput &)calendar
               resolve:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  EKSource *source = [self sourceForNewCalendar];
  if (source == nil) {
    reject(@"E_CALENDAR", @"There is no account to create the calendar in", nil);
    return;
  }
  EKCalendar *created = [EKCalendar calendarForEntityType:EKEntityTypeEvent eventStore:_store];
  created.title = calendar.title();
  created.CGColor = ColorOf(calendar.color()).CGColor;
  created.source = source;
  NSError *error = nil;
  if (![_store saveCalendar:created commit:YES error:&error]) {
    reject(@"E_CALENDAR", error.localizedDescription ?: @"The calendar was not created", error);
    return;
  }
  resolve(created.calendarIdentifier);
}

- (void)deleteCalendar:(NSString *)calendarId resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  EKCalendar *calendar = [_store calendarWithIdentifier:calendarId];
  if (calendar == nil) {
    resolve(@NO);
    return;
  }
  NSError *error = nil;
  if (![_store removeCalendar:calendar commit:YES error:&error]) {
    reject(@"E_CALENDAR", error.localizedDescription ?: @"The calendar was not deleted", error);
    return;
  }
  resolve(@YES);
}

- (void)getEvents:(double)startDate
          endDate:(double)endDate
      calendarIds:(NSArray *)calendarIds
          resolve:(RCTPromiseResolveBlock)resolve
           reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  NSMutableArray<EKCalendar *> *calendars = nil;
  if (calendarIds.count > 0) {
    calendars = [NSMutableArray new];
    for (NSString *identifier in calendarIds) {
      EKCalendar *calendar = [_store calendarWithIdentifier:identifier];
      if (calendar != nil) [calendars addObject:calendar];
    }
    // EventKit reads every calendar for an empty list, the caller asked for none of them
    if (calendars.count == 0) {
      resolve(@[]);
      return;
    }
  }
  NSPredicate *predicate = [_store predicateForEventsWithStartDate:DateOf(startDate) endDate:DateOf(endDate) calendars:calendars];
  NSArray<EKEvent *> *events = [[_store eventsMatchingPredicate:predicate] sortedArrayUsingSelector:@selector(compareStartDateWithEvent:)];
  NSMutableArray *result = [NSMutableArray arrayWithCapacity:events.count];
  for (EKEvent *event in events) [result addObject:EventOf(event)];
  resolve(result);
}

/** Writes the fields of the JavaScript side into an event; an all-day event ends within its last day, as EventKit keeps it */
- (void)apply:(JS::NativeReactNativeDeviceCalendar::NativeEventInput &)input to:(EKEvent *)event
{
  event.title = input.title();
  event.location = input.location();
  event.notes = input.notes();
  event.allDay = input.allDay();
  event.startDate = DateOf(input.startDate());
  if (input.allDay()) {
    event.timeZone = nil;
    event.endDate = DateOf(input.endDate() - 1000);
  } else {
    event.timeZone = NSTimeZone.localTimeZone;
    event.endDate = DateOf(input.endDate());
  }
}

- (void)createEvent:(JS::NativeReactNativeDeviceCalendar::NativeEventInput &)event
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  EKCalendar *calendar = [_store calendarWithIdentifier:event.calendarId()];
  if (calendar == nil) {
    reject(@"E_NOT_FOUND", [NSString stringWithFormat:@"There is no calendar %@", event.calendarId()], nil);
    return;
  }
  EKEvent *created = [EKEvent eventWithEventStore:_store];
  created.calendar = calendar;
  [self apply:event to:created];
  NSError *error = nil;
  if (![_store saveEvent:created span:EKSpanThisEvent commit:YES error:&error]) {
    reject(@"E_CALENDAR", error.localizedDescription ?: @"The event was not created", error);
    return;
  }
  resolve(created.eventIdentifier);
}

- (void)updateEvent:(NSString *)eventId
              event:(JS::NativeReactNativeDeviceCalendar::NativeEventInput &)event
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  EKEvent *existing = [_store eventWithIdentifier:eventId];
  if (existing == nil) {
    reject(@"E_NOT_FOUND", [NSString stringWithFormat:@"There is no event %@", eventId], nil);
    return;
  }
  if (![existing.calendar.calendarIdentifier isEqualToString:event.calendarId()]) {
    reject(@"E_CALENDAR_CHANGE", @"An event cannot move to another calendar: delete it and create it there", nil);
    return;
  }
  if (existing.hasRecurrenceRules) {
    reject(@"E_RECURRING", @"A recurring event cannot be changed", nil);
    return;
  }
  [self apply:event to:existing];
  NSError *error = nil;
  if (![_store saveEvent:existing span:EKSpanThisEvent commit:YES error:&error]) {
    reject(@"E_CALENDAR", error.localizedDescription ?: @"The event was not changed", error);
    return;
  }
  resolve(nil);
}

- (void)deleteEvent:(NSString *)eventId resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  // for a recurring event this is its first occurrence, so the following ones go with it
  EKEvent *existing = [_store eventWithIdentifier:eventId];
  if (existing == nil) {
    resolve(@NO);
    return;
  }
  NSError *error = nil;
  if (![_store removeEvent:existing span:EKSpanFutureEvents commit:YES error:&error]) {
    reject(@"E_CALENDAR", error.localizedDescription ?: @"The event was not deleted", error);
    return;
  }
  resolve(@YES);
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:(const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeReactNativeDeviceCalendarSpecJSI>(params);
}

+ (NSString *)moduleName
{
  return @"ReactNativeDeviceCalendar";
}

@end
