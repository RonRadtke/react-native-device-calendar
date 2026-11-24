#import <EventKitUI/EventKitUI.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/** The delegate of one system calendar dialog: dismisses its controller, then tells once how the person closed it */
@interface ReactNativeDeviceCalendarDialog
    : NSObject <EKEventViewDelegate, EKEventEditViewDelegate, UIAdaptivePresentationControllerDelegate>

/** dismissAction: the action when the person swipes the sheet away, which the controllers do not report */
- (instancetype)initWithDismissAction:(NSString *)dismissAction
                           completion:(void (^)(NSString *action, NSString *_Nullable eventId))completion;

@end

NS_ASSUME_NONNULL_END
