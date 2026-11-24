#import "ReactNativeDeviceCalendar.h"

#import "DeviceCalendarConversion.h"
#import "ReactNativeDeviceCalendarDialog.h"

#import <EventKit/EventKit.h>
#import <EventKitUI/EventKitUI.h>
#import <React/RCTUtils.h>

#include <cmath>

using namespace devicecalendar;

static NSString *CurrentPermission()
{
  return PermissionName([EKEventStore authorizationStatusForEntityType:EKEntityTypeEvent]);
}

/** Whether the system's event editor runs outside the app, where it needs no access to the calendars (iOS 17 and later) */
static BOOL EditorRunsOutsideApp()
{
  if (@available(iOS 17.0, *)) return YES;
  return NO;
}

static void RejectMissingEvent(RCTPromiseRejectBlock reject, NSString *eventId)
{
  reject(@"E_NOT_FOUND", [NSString stringWithFormat:@"There is no event %@", eventId], nil);
}

static NSDictionary *DialogResult(NSString *action, NSString *eventId)
{
  return @{@"action": action, @"id": eventId ?: (id)[NSNull null]};
}

/** The device calendars through EventKit */
@implementation ReactNativeDeviceCalendar {
  EKEventStore *_store;
  // the open dialog, kept until the person closes it; used on the main queue only
  ReactNativeDeviceCalendarDialog *_dialog;
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
  if ([CurrentPermission() isEqualToString:@"granted"]) return YES;
  reject(@"E_PERMISSION", @"The app has no full access to the calendars", nil);
  return NO;
}

/** Whether the app may add events, with full or write-only access; rejects the call when it may not */
- (BOOL)canWrite:(RCTPromiseRejectBlock)reject
{
  NSString *permission = CurrentPermission();
  if ([permission isEqualToString:@"granted"] || [permission isEqualToString:@"writeOnly"]) return YES;
  reject(@"E_PERMISSION", @"The app has no access to the calendars", nil);
  return NO;
}

#pragma mark - Permission

- (void)getPermissionStatus:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  resolve(CurrentPermission());
}

- (void)requestPermission:(BOOL)writeOnly resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  NSString *current = CurrentPermission();
  // the system asks only once, and once more to raise write-only access to full access
  BOOL ask = [current isEqualToString:@"undetermined"] || (!writeOnly && [current isEqualToString:@"writeOnly"]);
  if (!ask) {
    resolve(current);
    return;
  }
  EKEventStore *store = _store;
  EKEventStoreRequestAccessCompletionHandler done = ^(BOOL granted, NSError *error) {
    // the store reads the calendars it may now see
    if (granted) [store reset];
    resolve(CurrentPermission());
  };
  if (@available(iOS 17.0, *)) {
    if (writeOnly) {
      [store requestWriteOnlyAccessToEventsWithCompletion:done];
    } else {
      [store requestFullAccessToEventsWithCompletion:done];
    }
  } else {
    [store requestAccessToEntityType:EKEntityTypeEvent completion:done];
  }
}

#pragma mark - Sources and calendars

- (void)getSources:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  NSMutableArray *sources = [NSMutableArray new];
  for (EKSource *source in _store.sources) [sources addObject:SourceOf(source)];
  resolve(sources);
}

- (void)getCalendars:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  NSString *primary = _store.defaultCalendarForNewEvents.calendarIdentifier;
  NSMutableArray *calendars = [NSMutableArray new];
  for (EKCalendar *calendar in [_store calendarsForEntityType:EKEntityTypeEvent]) [calendars addObject:CalendarOf(calendar, primary)];
  resolve(calendars);
}

- (void)getDefaultCalendar:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  // with write-only access EventKit hands out a virtual calendar for new events
  if (![self canWrite:reject]) return;
  EKCalendar *calendar = _store.defaultCalendarForNewEvents;
  if (calendar == nil) {
    resolve([NSNull null]);
    return;
  }
  resolve(CalendarOf(calendar, calendar.calendarIdentifier));
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
  // accountName and timeZone are Android's
  NSString *sourceId = calendar.sourceId();
  EKSource *source = sourceId != nil ? [_store sourceWithIdentifier:sourceId] : [self sourceForNewCalendar];
  if (source == nil) {
    if (sourceId != nil) {
      reject(@"E_NOT_FOUND", [NSString stringWithFormat:@"There is no source %@", sourceId], nil);
    } else {
      reject(@"E_CALENDAR", @"There is no account to create the calendar in", nil);
    }
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

- (void)updateCalendar:(NSString *)calendarId
               changes:(JS::NativeReactNativeDeviceCalendar::NativeCalendarChanges &)changes
               resolve:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  EKCalendar *calendar = [_store calendarWithIdentifier:calendarId];
  if (calendar == nil) {
    reject(@"E_NOT_FOUND", [NSString stringWithFormat:@"There is no calendar %@", calendarId], nil);
    return;
  }
  NSString *title = changes.title();
  NSString *color = changes.color();
  if (title != nil) calendar.title = title;
  if (color != nil) calendar.CGColor = ColorOf(color).CGColor;
  NSError *error = nil;
  if (![_store saveCalendar:calendar commit:YES error:&error]) {
    reject(@"E_CALENDAR", error.localizedDescription ?: @"The calendar was not changed", error);
    return;
  }
  resolve(nil);
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

#pragma mark - Events

/**
 * The event, or with an occurrence the occurrence of the series whose original start (all-day: its local midnight) it is; nil when
 * there is none. Without an occurrence a series answers with its first occurrence. EventKit fetches occurrences only by period, so
 * the occurrence is looked for in the series' calendar within a week either side of its original start: an occurrence moved further
 * away from it is not found.
 */
- (EKEvent *)eventWithId:(NSString *)eventId occurrence:(NSNumber *)occurrence
{
  EKEvent *event = [_store eventWithIdentifier:eventId];
  if (event == nil || occurrence == nil) return event;
  NSDate *original = DateOf(occurrence.doubleValue);
  NSTimeInterval week = 7 * 24 * 60 * 60;
  NSPredicate *predicate = [_store predicateForEventsWithStartDate:[original dateByAddingTimeInterval:-week]
                                                           endDate:[original dateByAddingTimeInterval:week]
                                                         calendars:@[event.calendar]];
  for (EKEvent *candidate in [_store eventsMatchingPredicate:predicate]) {
    // within a second: EventKit keeps whole seconds, JavaScript milliseconds
    if ([candidate.eventIdentifier isEqualToString:eventId] && std::fabs(OriginalStartOf(candidate) - occurrence.doubleValue) < 1000) {
      return candidate;
    }
  }
  return nil;
}

/**
 * The event a change with the span applies to, and the EventKit span to save or remove it with: the whole series through its first
 * occurrence and the following ones, one occurrence alone, or one occurrence and the following ones.
 */
- (EKEvent *)eventWithId:(NSString *)eventId span:(NSString *)span occurrence:(NSNumber *)occurrence eventKitSpan:(EKSpan *)eventKitSpan
{
  EKEvent *event = [self eventWithId:eventId occurrence:[span isEqualToString:@"all"] ? nil : occurrence];
  BOOL recurring = event.hasRecurrenceRules || event.isDetached;
  *eventKitSpan = recurring && ![span isEqualToString:@"this"] ? EKSpanFutureEvents : EKSpanThisEvent;
  return event;
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

- (void)getEvent:(NSString *)eventId
      occurrence:(NSNumber *)occurrence
         resolve:(RCTPromiseResolveBlock)resolve
          reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  EKEvent *event = [self eventWithId:eventId occurrence:occurrence];
  if (event == nil) {
    resolve([NSNull null]);
    return;
  }
  resolve(EventOf(event));
}

- (void)createEvent:(JS::NativeReactNativeDeviceCalendar::NativeEventInput &)event
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject
{
  if (![self canWrite:reject]) return;
  EKCalendar *calendar = [_store calendarWithIdentifier:event.calendarId()];
  // with write-only access the app sees no calendars, only the one EventKit offers for new events
  if (calendar == nil && [CurrentPermission() isEqualToString:@"writeOnly"]) calendar = _store.defaultCalendarForNewEvents;
  if (calendar == nil) {
    reject(@"E_NOT_FOUND", [NSString stringWithFormat:@"There is no calendar %@", event.calendarId()], nil);
    return;
  }
  EKEvent *created = [EKEvent eventWithEventStore:_store];
  created.calendar = calendar;
  NSString *problem = ApplyInput(event, created, YES);
  if (problem != nil) {
    reject(@"E_CALENDAR", problem, nil);
    return;
  }
  NSError *error = nil;
  if (![_store saveEvent:created span:EKSpanThisEvent commit:YES error:&error]) {
    reject(@"E_CALENDAR", error.localizedDescription ?: @"The event was not created", error);
    return;
  }
  resolve(created.eventIdentifier);
}

- (void)updateEvent:(NSString *)eventId
              event:(JS::NativeReactNativeDeviceCalendar::NativeEventInput &)event
               span:(NSString *)span
         occurrence:(NSNumber *)occurrence
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  EKSpan eventKitSpan = EKSpanThisEvent;
  EKEvent *existing = [self eventWithId:eventId span:span occurrence:occurrence eventKitSpan:&eventKitSpan];
  if (existing == nil) {
    RejectMissingEvent(reject, eventId);
    return;
  }
  if (![existing.calendar.calendarIdentifier isEqualToString:event.calendarId()]) {
    reject(@"E_CALENDAR_CHANGE", @"An event cannot move to another calendar: delete it and create it there", nil);
    return;
  }
  // a single occurrence keeps the rule of its series
  NSString *problem = ApplyInput(event, existing, ![span isEqualToString:@"this"]);
  if (problem != nil) {
    reject(@"E_CALENDAR", problem, nil);
    return;
  }
  NSError *error = nil;
  if (![_store saveEvent:existing span:eventKitSpan commit:YES error:&error]) {
    reject(@"E_CALENDAR", error.localizedDescription ?: @"The event was not changed", error);
    return;
  }
  // changing the future occurrences splits the series, and those occurrences may get an identifier of their own
  resolve(existing.eventIdentifier);
}

- (void)deleteEvent:(NSString *)eventId
               span:(NSString *)span
         occurrence:(NSNumber *)occurrence
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  EKSpan eventKitSpan = EKSpanThisEvent;
  EKEvent *existing = [self eventWithId:eventId span:span occurrence:occurrence eventKitSpan:&eventKitSpan];
  if (existing == nil) {
    resolve(@NO);
    return;
  }
  NSError *error = nil;
  if (![_store removeEvent:existing span:eventKitSpan commit:YES error:&error]) {
    reject(@"E_CALENDAR", error.localizedDescription ?: @"The event was not deleted", error);
    return;
  }
  resolve(@YES);
}

- (void)getAttendees:(NSString *)eventId
          occurrence:(NSNumber *)occurrence
             resolve:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  EKEvent *event = [self eventWithId:eventId occurrence:occurrence];
  if (event == nil) {
    RejectMissingEvent(reject, eventId);
    return;
  }
  NSMutableArray *attendees = [NSMutableArray new];
  for (EKParticipant *participant in event.attendees) [attendees addObject:AttendeeOf(participant)];
  resolve(attendees);
}

#pragma mark - Dialogs

/**
 * Shows the controller `make` builds for the dialog over the app, one dialog at a time; runs on the main queue. The module keeps the
 * dialog, the controller's delegate, until the person closes the controller.
 */
- (void)presentDialog:(UIViewController * (^)(ReactNativeDeviceCalendarDialog *dialog))make
        dismissAction:(NSString *)dismissAction
           completion:(void (^)(NSString *action, NSString *eventId))completion
               reject:(RCTPromiseRejectBlock)reject
{
  if (_dialog != nil) {
    reject(@"E_DIALOG", @"Another calendar dialog is open", nil);
    return;
  }
  UIViewController *presenter = RCTPresentedViewController();
  if (presenter == nil) {
    reject(@"E_DIALOG", @"There is no screen to show the dialog on", nil);
    return;
  }
  ReactNativeDeviceCalendarDialog *dialog = [[ReactNativeDeviceCalendarDialog alloc] initWithDismissAction:dismissAction
                                                                                               completion:^(NSString *action, NSString *eventId) {
                                                                                                 self->_dialog = nil;
                                                                                                 completion(action, eventId);
                                                                                               }];
  UIViewController *controller = make(dialog);
  // a swipe closes the sheet without the controller telling its delegate
  controller.presentationController.delegate = dialog;
  _dialog = dialog;
  [presenter presentViewController:controller animated:YES completion:nil];
}

/** The system's event editor for a new or an existing event */
- (void)presentEditorFor:(EKEvent *)event resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  EKEventStore *store = _store;
  dispatch_async(dispatch_get_main_queue(), ^{
    [self
        presentDialog:^UIViewController *(ReactNativeDeviceCalendarDialog *dialog) {
          EKEventEditViewController *controller = [EKEventEditViewController new];
          controller.eventStore = store;
          controller.event = event;
          controller.editViewDelegate = dialog;
          return controller;
        }
        dismissAction:@"canceled"
           completion:^(NSString *action, NSString *savedId) {
             resolve(DialogResult(action, savedId));
           }
               reject:reject];
  });
}

- (void)openEvent:(NSString *)eventId
       occurrence:(NSNumber *)occurrence
    allowsEditing:(BOOL)allowsEditing
          resolve:(RCTPromiseResolveBlock)resolve
           reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  EKEvent *event = [self eventWithId:eventId occurrence:occurrence];
  if (event == nil) {
    RejectMissingEvent(reject, eventId);
    return;
  }
  // the system's view stays empty for a series itself and ignores its Done button: it gets the first occurrence
  if (occurrence == nil && event.hasRecurrenceRules) {
    event = [self eventWithId:eventId occurrence:@(OriginalStartOf(event))] ?: event;
  }
  dispatch_async(dispatch_get_main_queue(), ^{
    [self
        presentDialog:^UIViewController *(ReactNativeDeviceCalendarDialog *dialog) {
          EKEventViewController *controller = [EKEventViewController new];
          controller.event = event;
          controller.allowsEditing = allowsEditing;
          controller.allowsCalendarPreview = NO;
          controller.delegate = dialog;
          // presented in a navigation controller, the view shows its bar with the Done button
          return [[UINavigationController alloc] initWithRootViewController:controller];
        }
        dismissAction:@"done"
           completion:^(NSString *action, NSString *savedId) {
             resolve(action);
           }
               reject:reject];
  });
}

- (void)createEventWithDialog:(JS::NativeReactNativeDeviceCalendar::NativeDialogEvent &)event
                      resolve:(RCTPromiseResolveBlock)resolve
                       reject:(RCTPromiseRejectBlock)reject
{
  // before iOS 17 the editor runs in the app and needs full access
  if (!EditorRunsOutsideApp() && ![self hasAccess:reject]) return;
  EKEvent *created = [EKEvent eventWithEventStore:_store];
  NSString *calendarId = event.calendarId();
  EKCalendar *calendar = calendarId != nil ? [_store calendarWithIdentifier:calendarId] : nil;
  if (calendar != nil) created.calendar = calendar;
  if (event.title() != nil) created.title = event.title();
  created.location = event.location();
  created.notes = event.notes();
  NSString *url = event.url();
  if (url != nil) created.URL = [NSURL URLWithString:url];
  std::optional<bool> allDay = event.allDay();
  if (allDay.has_value()) created.allDay = *allDay;
  std::optional<double> start = event.startDate();
  std::optional<double> end = event.endDate();
  if (start.has_value()) created.startDate = DateOf(*start);
  if (end.has_value()) {
    // the end is exclusive as everywhere in the library; EventKit ends an all-day event within its last day
    BOOL withinLastDay = created.allDay && start.has_value() && *end > *start;
    created.endDate = DateOf(withinLastDay ? *end - 1000 : *end);
  } else if (start.has_value()) {
    // the editor needs an end; it gives a new event an hour
    created.endDate = [created.startDate dateByAddingTimeInterval:60 * 60];
  }
  [self presentEditorFor:created resolve:resolve reject:reject];
}

- (void)editEventWithDialog:(NSString *)eventId
                 occurrence:(NSNumber *)occurrence
                    resolve:(RCTPromiseResolveBlock)resolve
                     reject:(RCTPromiseRejectBlock)reject
{
  if (![self hasAccess:reject]) return;
  EKEvent *event = [self eventWithId:eventId occurrence:occurrence];
  if (event == nil) {
    RejectMissingEvent(reject, eventId);
    return;
  }
  [self presentEditorFor:event resolve:resolve reject:reject];
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
