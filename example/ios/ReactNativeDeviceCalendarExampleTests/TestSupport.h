#import <Foundation/Foundation.h>

/** Milliseconds since 1970 of a local time in the zone, as JavaScript hands dates to the module */
static inline double MillisAt(NSTimeZone *zone, NSInteger year, NSInteger month, NSInteger day, NSInteger hour = 0, NSInteger minute = 0)
{
  NSCalendar *calendar = [[NSCalendar alloc] initWithCalendarIdentifier:NSCalendarIdentifierGregorian];
  calendar.timeZone = zone;
  NSDateComponents *components = [NSDateComponents new];
  components.year = year;
  components.month = month;
  components.day = day;
  components.hour = hour;
  components.minute = minute;
  return [calendar dateFromComponents:components].timeIntervalSince1970 * 1000.0;
}

static inline NSDate *DateAt(NSTimeZone *zone, NSInteger year, NSInteger month, NSInteger day, NSInteger hour = 0, NSInteger minute = 0)
{
  return [NSDate dateWithTimeIntervalSince1970:MillisAt(zone, year, month, day, hour, minute) / 1000.0];
}

/** An event input as the JavaScript side builds it; null fields are left out, as the bridge leaves them out */
static inline NSDictionary *EventInput(NSString *calendarId, NSString *title, double start, double end, NSDictionary *extra = nil)
{
  NSMutableDictionary *input = [@{
    @"calendarId": calendarId,
    @"title": title,
    @"startDate": @(start),
    @"endDate": @(end),
    @"allDay": @NO,
    @"alarms": @[],
  } mutableCopy];
  [input addEntriesFromDictionary:extra ?: @{}];
  return input;
}

/** A recurrence rule as the JavaScript side builds it */
static inline NSDictionary *RuleInput(NSString *frequency, NSDictionary *extra = nil)
{
  NSMutableDictionary *rule = [@{
    @"frequency": frequency,
    @"interval": @1,
    @"daysOfTheWeek": @[],
    @"daysOfTheMonth": @[],
    @"monthsOfTheYear": @[],
    @"weeksOfTheYear": @[],
    @"daysOfTheYear": @[],
    @"setPositions": @[],
  } mutableCopy];
  [rule addEntriesFromDictionary:extra ?: @{}];
  return rule;
}
