#import <EventKit/EventKit.h>
#import <ReactNativeDeviceCalendarSpec/ReactNativeDeviceCalendarSpec.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/** The mapping between EventKit and the dictionaries and structs of the spec (src/NativeReactNativeDeviceCalendar.ts) */
namespace devicecalendar {

/** The permission as the spec names it */
NSString *PermissionName(EKAuthorizationStatus status);

NSDate *DateOf(double millis);

/** A colour of the form #RRGGBB, which the JavaScript side has checked */
UIColor *ColorOf(NSString *hex);

NSDictionary *SourceOf(EKSource *source);

/** A calendar; primaryId is the identifier of the calendar for new events */
NSDictionary *CalendarOf(EKCalendar *calendar, NSString *_Nullable primaryId);

NSDictionary *EventOf(EKEvent *event);

NSDictionary *AttendeeOf(EKParticipant *participant);

/** The start an occurrence has by its rule, the start of an event outside a series; for an all-day event its local midnight */
double OriginalStartOf(EKEvent *event);

/**
 * Writes the input into the event, its recurrence rule only when `rules` is set. Returns why it cannot, before anything of the
 * event changed; nil when it wrote the input.
 */
NSString *_Nullable ApplyInput(const JS::NativeReactNativeDeviceCalendar::NativeEventInput &input, EKEvent *event, BOOL rules);

} // namespace devicecalendar

NS_ASSUME_NONNULL_END
