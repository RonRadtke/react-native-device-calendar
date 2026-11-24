#import "DeviceCalendarConversion.h"

#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <optional>

namespace devicecalendar {

namespace spec = JS::NativeReactNativeDeviceCalendar;

NSString *PermissionName(EKAuthorizationStatus status)
{
  if (status == EKAuthorizationStatusNotDetermined) return @"undetermined";
  if (status == EKAuthorizationStatusRestricted) return @"restricted";
  if (status == EKAuthorizationStatusDenied) return @"denied";
  if (@available(iOS 17.0, *)) {
    if (status == EKAuthorizationStatusWriteOnly) return @"writeOnly";
  }
  // full access; before iOS 17 the remaining status is "authorized", which is full access
  return @"granted";
}

NSDate *DateOf(double millis)
{
  return [NSDate dateWithTimeIntervalSince1970:millis / 1000.0];
}

static double MillisValue(NSDate *date)
{
  return std::floor(date.timeIntervalSince1970 * 1000.0);
}

static NSNumber *MillisOf(NSDate *date)
{
  return @(MillisValue(date));
}

static id MillisOrNull(NSDate *date)
{
  if (date == nil) return [NSNull null];
  return MillisOf(date);
}

/** The text, or null for none or an empty one */
static id TextOrNull(NSString *text)
{
  if (text.length == 0) return [NSNull null];
  return text;
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

UIColor *ColorOf(NSString *hex)
{
  unsigned long rgb = std::strtoul([hex substringFromIndex:1].UTF8String, nullptr, 16);
  return [UIColor colorWithRed:((rgb >> 16) & 0xFF) / 255.0 green:((rgb >> 8) & 0xFF) / 255.0 blue:(rgb & 0xFF) / 255.0 alpha:1.0];
}

NSDictionary *SourceOf(EKSource *source)
{
  return @{
    @"id": source.sourceIdentifier,
    @"name": source.title ?: @"",
    @"type": SourceTypeName(source.sourceType),
    @"isLocal": @(source.sourceType == EKSourceTypeLocal),
  };
}

static NSArray<NSString *> *AvailabilitiesOf(EKCalendarEventAvailabilityMask mask)
{
  NSMutableArray<NSString *> *names = [NSMutableArray new];
  if (mask & EKCalendarEventAvailabilityBusy) [names addObject:@"busy"];
  if (mask & EKCalendarEventAvailabilityFree) [names addObject:@"free"];
  if (mask & EKCalendarEventAvailabilityTentative) [names addObject:@"tentative"];
  if (mask & EKCalendarEventAvailabilityUnavailable) [names addObject:@"unavailable"];
  if (names.count == 0) [names addObject:@"notSupported"];
  return names;
}

NSDictionary *CalendarOf(EKCalendar *calendar, NSString *primaryId)
{
  return @{
    @"id": calendar.calendarIdentifier ?: @"",
    @"title": calendar.title ?: @"",
    @"color": HexOf(calendar.CGColor),
    @"sourceId": calendar.source.sourceIdentifier ?: @"",
    @"source": calendar.source.title ?: @"",
    @"sourceType": SourceTypeName(calendar.source.sourceType),
    @"isPrimary": @(primaryId != nil && [calendar.calendarIdentifier isEqualToString:primaryId]),
    @"allowsModifications": @(calendar.allowsContentModifications),
    @"isVisible": @YES,
    // EventKit keeps no time zone per calendar
    @"timeZone": [NSNull null],
    @"allowedAvailabilities": AvailabilitiesOf(calendar.supportedEventAvailabilities),
  };
}

static NSString *AvailabilityName(EKEventAvailability availability)
{
  switch (availability) {
    case EKEventAvailabilityNotSupported: return @"notSupported";
    case EKEventAvailabilityBusy: return @"busy";
    case EKEventAvailabilityFree: return @"free";
    case EKEventAvailabilityTentative: return @"tentative";
    case EKEventAvailabilityUnavailable: return @"unavailable";
  }
  return @"notSupported";
}

static EKEventAvailability AvailabilityOf(NSString *name)
{
  if ([name isEqualToString:@"busy"]) return EKEventAvailabilityBusy;
  if ([name isEqualToString:@"free"]) return EKEventAvailabilityFree;
  if ([name isEqualToString:@"tentative"]) return EKEventAvailabilityTentative;
  if ([name isEqualToString:@"unavailable"]) return EKEventAvailabilityUnavailable;
  return EKEventAvailabilityNotSupported;
}

/** The flag of an availability in a calendar's supported availabilities */
static EKCalendarEventAvailabilityMask MaskOf(EKEventAvailability availability)
{
  switch (availability) {
    case EKEventAvailabilityNotSupported: return EKCalendarEventAvailabilityNone;
    case EKEventAvailabilityBusy: return EKCalendarEventAvailabilityBusy;
    case EKEventAvailabilityFree: return EKCalendarEventAvailabilityFree;
    case EKEventAvailabilityTentative: return EKCalendarEventAvailabilityTentative;
    case EKEventAvailabilityUnavailable: return EKCalendarEventAvailabilityUnavailable;
  }
  return EKCalendarEventAvailabilityNone;
}

static NSString *EventStatusName(EKEventStatus status)
{
  switch (status) {
    case EKEventStatusNone: return @"none";
    case EKEventStatusConfirmed: return @"confirmed";
    case EKEventStatusTentative: return @"tentative";
    case EKEventStatusCanceled: return @"canceled";
  }
  return @"none";
}

static NSString *RoleName(EKParticipantRole role)
{
  switch (role) {
    case EKParticipantRoleUnknown: return @"unknown";
    case EKParticipantRoleRequired: return @"required";
    case EKParticipantRoleOptional: return @"optional";
    case EKParticipantRoleChair: return @"chair";
    case EKParticipantRoleNonParticipant: return @"nonParticipant";
  }
  return @"unknown";
}

static NSString *ParticipantStatusName(EKParticipantStatus status)
{
  switch (status) {
    case EKParticipantStatusUnknown: return @"unknown";
    case EKParticipantStatusPending: return @"pending";
    case EKParticipantStatusAccepted: return @"accepted";
    case EKParticipantStatusDeclined: return @"declined";
    case EKParticipantStatusTentative: return @"tentative";
    case EKParticipantStatusDelegated: return @"delegated";
    case EKParticipantStatusCompleted: return @"completed";
    case EKParticipantStatusInProcess: return @"inProcess";
  }
  return @"unknown";
}

static NSString *ParticipantTypeName(EKParticipantType type)
{
  switch (type) {
    case EKParticipantTypeUnknown: return @"unknown";
    case EKParticipantTypePerson: return @"person";
    case EKParticipantTypeRoom: return @"room";
    case EKParticipantTypeResource: return @"resource";
    case EKParticipantTypeGroup: return @"group";
  }
  return @"unknown";
}

NSDictionary *AttendeeOf(EKParticipant *participant)
{
  NSURL *url = participant.URL;
  NSString *email = nil;
  if ([url.scheme.lowercaseString isEqualToString:@"mailto"]) {
    NSString *address = url.resourceSpecifier;
    email = address.stringByRemovingPercentEncoding ?: address;
  }
  return @{
    @"name": TextOrNull(participant.name),
    @"email": TextOrNull(email),
    @"role": RoleName(participant.participantRole),
    @"status": ParticipantStatusName(participant.participantStatus),
    @"type": ParticipantTypeName(participant.participantType),
    @"isCurrentUser": @([participant isCurrentUser]),
  };
}

static NSString *FrequencyName(EKRecurrenceFrequency frequency)
{
  switch (frequency) {
    case EKRecurrenceFrequencyDaily: return @"daily";
    case EKRecurrenceFrequencyWeekly: return @"weekly";
    case EKRecurrenceFrequencyMonthly: return @"monthly";
    case EKRecurrenceFrequencyYearly: return @"yearly";
  }
  return @"daily";
}

static EKRecurrenceFrequency FrequencyOf(NSString *name)
{
  if ([name isEqualToString:@"weekly"]) return EKRecurrenceFrequencyWeekly;
  if ([name isEqualToString:@"monthly"]) return EKRecurrenceFrequencyMonthly;
  if ([name isEqualToString:@"yearly"]) return EKRecurrenceFrequencyYearly;
  return EKRecurrenceFrequencyDaily;
}

static NSDictionary *RuleDictionaryOf(EKRecurrenceRule *rule)
{
  NSMutableArray *days = [NSMutableArray new];
  for (EKRecurrenceDayOfWeek *day in rule.daysOfTheWeek) {
    [days addObject:@{@"day": @(day.dayOfTheWeek), @"weekNumber": @(day.weekNumber)}];
  }
  EKRecurrenceEnd *end = rule.recurrenceEnd;
  id occurrence = [NSNull null];
  if (end != nil && end.endDate == nil) occurrence = @(end.occurrenceCount);
  return @{
    @"frequency": FrequencyName(rule.frequency),
    @"interval": @(rule.interval),
    @"endDate": MillisOrNull(end.endDate),
    @"occurrence": occurrence,
    @"daysOfTheWeek": days,
    @"daysOfTheMonth": rule.daysOfTheMonth ?: @[],
    @"monthsOfTheYear": rule.monthsOfTheYear ?: @[],
    @"weeksOfTheYear": rule.weeksOfTheYear ?: @[],
    @"daysOfTheYear": rule.daysOfTheYear ?: @[],
    @"setPositions": rule.setPositions ?: @[],
  };
}

/** The alarms in minutes from the start */
static NSArray *AlarmsOf(EKEvent *event)
{
  NSMutableArray *alarms = [NSMutableArray new];
  for (EKAlarm *alarm in event.alarms) {
    // an alarm at a fixed time counts from the start like the others
    NSTimeInterval offset = alarm.absoluteDate != nil ? [alarm.absoluteDate timeIntervalSinceDate:event.startDate] : alarm.relativeOffset;
    [alarms addObject:@{@"relativeOffset": @(std::lround(offset / 60.0)), @"method": [NSNull null]}];
  }
  return alarms;
}

double OriginalStartOf(EKEvent *event)
{
  NSDate *original = event.occurrenceDate ?: event.startDate;
  if (event.allDay) original = [NSCalendar.currentCalendar startOfDayForDate:original];
  return MillisValue(original);
}

NSDictionary *EventOf(EKEvent *event)
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
  // all-day and floating events have no time zone
  id timeZone = [NSNull null];
  if (!event.allDay && event.timeZone != nil) timeZone = event.timeZone.name;
  EKRecurrenceRule *rule = event.recurrenceRules.firstObject;
  id recurrenceRule = [NSNull null];
  if (rule != nil) recurrenceRule = RuleDictionaryOf(rule);
  // an occurrence of a series, also one that was changed apart from it
  id originalStart = [NSNull null];
  if (event.hasRecurrenceRules || event.isDetached) originalStart = @(OriginalStartOf(event));
  id organizer = [NSNull null];
  if (event.organizer != nil) organizer = AttendeeOf(event.organizer);
  return @{
    @"id": event.eventIdentifier ?: @"",
    @"calendarId": event.calendar.calendarIdentifier ?: @"",
    @"title": event.title ?: @"",
    @"startDate": MillisOf(start),
    @"endDate": MillisOf(end),
    @"allDay": @(event.allDay),
    @"location": TextOrNull(event.location),
    @"notes": TextOrNull(event.notes),
    @"url": TextOrNull(event.URL.absoluteString),
    @"timeZone": timeZone,
    @"availability": AvailabilityName(event.availability),
    @"status": EventStatusName(event.status),
    @"alarms": AlarmsOf(event),
    @"recurrenceRule": recurrenceRule,
    @"originalStartDate": originalStart,
    @"isDetached": @(event.isDetached),
    @"organizer": organizer,
    @"creationDate": MillisOrNull(event.creationDate),
    @"lastModifiedDate": MillisOrNull(event.lastModifiedDate),
  };
}

/** The numbers of a rule part as EventKit takes them; nil for none */
static NSArray<NSNumber *> *NumbersOf(const facebook::react::LazyVector<double> &values)
{
  if (values.empty()) return nil;
  NSMutableArray<NSNumber *> *numbers = [NSMutableArray new];
  for (std::int32_t i = 0; i < values.size(); i++) [numbers addObject:@(static_cast<NSInteger>(values[i]))];
  return numbers;
}

static EKRecurrenceRule *RecurrenceRuleOf(const spec::NativeRecurrenceRule &rule)
{
  EKRecurrenceEnd *end = nil;
  std::optional<double> endDate = rule.endDate();
  std::optional<double> occurrence = rule.occurrence();
  if (endDate.has_value()) {
    end = [EKRecurrenceEnd recurrenceEndWithEndDate:DateOf(*endDate)];
  } else if (occurrence.has_value()) {
    end = [EKRecurrenceEnd recurrenceEndWithOccurrenceCount:static_cast<NSUInteger>(*occurrence)];
  }
  facebook::react::LazyVector<spec::NativeWeekday> weekdays = rule.daysOfTheWeek();
  NSMutableArray<EKRecurrenceDayOfWeek *> *days = weekdays.empty() ? nil : [NSMutableArray new];
  for (std::int32_t i = 0; i < weekdays.size(); i++) {
    spec::NativeWeekday weekday = weekdays[i];
    EKWeekday day = static_cast<EKWeekday>(static_cast<NSInteger>(weekday.day()));
    [days addObject:[EKRecurrenceDayOfWeek dayOfWeek:day weekNumber:static_cast<NSInteger>(weekday.weekNumber())]];
  }
  return [[EKRecurrenceRule alloc] initRecurrenceWithFrequency:FrequencyOf(rule.frequency())
                                                      interval:static_cast<NSInteger>(rule.interval())
                                                 daysOfTheWeek:days
                                                daysOfTheMonth:NumbersOf(rule.daysOfTheMonth())
                                               monthsOfTheYear:NumbersOf(rule.monthsOfTheYear())
                                                weeksOfTheYear:NumbersOf(rule.weeksOfTheYear())
                                                 daysOfTheYear:NumbersOf(rule.daysOfTheYear())
                                                  setPositions:NumbersOf(rule.setPositions())
                                                           end:end];
}

NSString *ApplyInput(const spec::NativeEventInput &input, EKEvent *event, BOOL rules)
{
  // first what can fail, so that a refused input leaves the event as it is
  NSTimeZone *timeZone = nil;
  if (!input.allDay()) {
    NSString *name = input.timeZone();
    timeZone = name != nil ? [NSTimeZone timeZoneWithName:name] : NSTimeZone.localTimeZone;
    if (timeZone == nil) return [NSString stringWithFormat:@"There is no time zone %@", name];
  }
  EKRecurrenceRule *rule = nil;
  std::optional<spec::NativeRecurrenceRule> inputRule = input.recurrenceRule();
  if (rules && inputRule.has_value()) {
    @try {
      rule = RecurrenceRuleOf(*inputRule);
    } @catch (NSException *exception) {
      // EventKit may raise for rule parts the frequency does not take; refusing the call keeps the app running
      return exception.reason ?: @"EventKit does not take the recurrence rule";
    }
  }

  event.title = input.title();
  event.location = input.location();
  event.notes = input.notes();
  NSString *url = input.url();
  event.URL = url != nil ? [NSURL URLWithString:url] : nil;
  event.allDay = input.allDay();
  // the time zone before the dates, so that they stay the moments the input names
  event.timeZone = timeZone;
  event.startDate = DateOf(input.startDate());
  // EventKit ends an all-day event within its last day, the input at the midnight after it
  event.endDate = DateOf(input.allDay() ? input.endDate() - 1000 : input.endDate());

  // null is busy; a calendar keeps only the availabilities it offers
  NSString *availabilityName = input.availability();
  EKEventAvailability availability = availabilityName != nil ? AvailabilityOf(availabilityName) : EKEventAvailabilityBusy;
  if (event.calendar.supportedEventAvailabilities & MaskOf(availability)) event.availability = availability;

  facebook::react::LazyVector<spec::NativeAlarm> inputAlarms = input.alarms();
  NSMutableArray<EKAlarm *> *alarms = [NSMutableArray new];
  for (std::int32_t i = 0; i < inputAlarms.size(); i++) {
    [alarms addObject:[EKAlarm alarmWithRelativeOffset:inputAlarms[i].relativeOffset() * 60.0]];
  }
  event.alarms = alarms;

  if (rules) {
    EKRecurrenceRule *current = event.recurrenceRules.firstObject;
    // an unchanged rule stays: a new one would count its occurrences afresh where a change of future events splits the series
    BOOL unchanged = rule == nil ? current == nil : current != nil && [RuleDictionaryOf(rule) isEqualToDictionary:RuleDictionaryOf(current)];
    if (!unchanged) event.recurrenceRules = rule != nil ? @[rule] : nil;
  }
  return nil;
}

} // namespace devicecalendar
