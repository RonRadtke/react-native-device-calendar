#import "ReactNativeDeviceCalendarDialog.h"

@implementation ReactNativeDeviceCalendarDialog {
  NSString *_dismissAction;
  void (^_completion)(NSString *action, NSString *_Nullable eventId);
}

- (instancetype)initWithDismissAction:(NSString *)dismissAction
                           completion:(void (^)(NSString *action, NSString *_Nullable eventId))completion
{
  if (self = [super init]) {
    _dismissAction = [dismissAction copy];
    _completion = [completion copy];
  }
  return self;
}

/** Dismisses the controller, then tells how the dialog ended; only the first end counts */
- (void)finish:(UIViewController *)controller action:(NSString *)action eventId:(NSString *)eventId
{
  void (^completion)(NSString *, NSString *) = _completion;
  if (completion == nil) return;
  _completion = nil;
  // the presenting controller also dismisses a controller inside the presented navigation controller
  UIViewController *presenter = controller.presentingViewController;
  if (presenter == nil) {
    completion(action, eventId);
    return;
  }
  [presenter dismissViewControllerAnimated:YES
                                completion:^{
                                  completion(action, eventId);
                                }];
}

- (void)eventViewController:(EKEventViewController *)controller didCompleteWithAction:(EKEventViewAction)action
{
  NSString *name = @"done";
  if (action == EKEventViewActionResponded) name = @"responded";
  if (action == EKEventViewActionDeleted) name = @"deleted";
  [self finish:controller action:name eventId:nil];
}

- (void)eventEditViewController:(EKEventEditViewController *)controller didCompleteWithAction:(EKEventEditViewAction)action
{
  if (action == EKEventEditViewActionSaved) {
    [self finish:controller action:@"saved" eventId:controller.event.eventIdentifier];
    return;
  }
  [self finish:controller action:action == EKEventEditViewActionDeleted ? @"deleted" : @"canceled" eventId:nil];
}

- (void)presentationControllerDidDismiss:(UIPresentationController *)presentationController
{
  [self finish:nil action:_dismissAction eventId:nil];
}

@end
