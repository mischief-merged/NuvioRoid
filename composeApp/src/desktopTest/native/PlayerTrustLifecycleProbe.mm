#import <WebKit/WebKit.h>
@interface ProbeWebConfiguration : WKWebViewConfiguration
@end
@implementation ProbeWebConfiguration
- (instancetype)init {
    self = [super init];
    if (self) self.websiteDataStore = [WKWebsiteDataStore nonPersistentDataStore];
    return self;
}
@end
#define WKWebViewConfiguration ProbeWebConfiguration
#include "player_bridge.mm"
#undef WKWebViewConfiguration
#import <objc/runtime.h>

static int shutdowns;
static bool afterWakeup;
@interface FailingPlayer : MpvWebPlayer
@end
@implementation FailingPlayer
- (void)startMpvWithSource:(NSString *)source headerLines:(NSArray<NSString *> *)headers playWhenReady:(BOOL)ready initialPositionMs:(long long)position decoderPriority:(int)decoder {
    mpv_handle *handle = mpv_create();
    Ivar ivar = class_getInstanceVariable([MpvWebPlayer class], "_mpv");
    memcpy((char *)(__bridge void *)self + ivar_getOffset(ivar), &handle, sizeof(handle));
    if (afterWakeup) {
        mpv_set_option_string(handle, "vo", "null");
        mpv_set_option_string(handle, "ao", "null");
        mpv_initialize(handle);
        [self startMpvEventDrain];
    }
    @throw [NSException exceptionWithName:@"StartupProbe" reason:@"startup failure" userInfo:nil];
}
- (void)shutdown { shutdowns++; [super shutdown]; }
@end

int main(int argc, char **argv) {
    @autoreleasepool {
        NSString *mode = argc > 1 ? @(argv[1]) : @"";
        if ([mode isEqual:@"monitor"]) {
            // Without a path report there must be no identity on which to authorize a bypass.
            return NuvioPlaybackNetwork().length == 0 ? 0 : 21;
        }
        if ([mode isEqual:@"download-monitor-stop"]) {
            NuvioStartPlaybackMonitor();
            NuvioStopPathMonitorLocked(nullptr);
            return gPlaybackPathMonitor ? 0 : 25;
        }
        if ([mode isEqual:@"playback-monitor-cancel"]) {
            NuvioStartPlaybackMonitor();
            if (!gPlaybackPathMonitor) return 26;
            nw_path_monitor_cancel(gPlaybackPathMonitor);
            NSDate *until = [NSDate dateWithTimeIntervalSinceNow:2];
            while (until.timeIntervalSinceNow > 0) {
                bool active;
                { std::lock_guard<std::mutex> guard(gPlaybackNetworkMutex); active = gPlaybackPathMonitor != nil; }
                if (!active) return NuvioPlaybackNetwork().length == 0 ? 0 : 27;
                [NSThread sleepForTimeInterval:0.01];
            }
            return 28;
        }
        if ([mode isEqual:@"queued-network-change"]) {
            MpvWebPlayer *player = [MpvWebPlayer new];
            mpv_handle *handle = mpv_create();
            mpv_set_option_string(handle, "vo", "null");
            mpv_set_option_string(handle, "ao", "null");
            mpv_set_option_string(handle, "tls-verify", "no");
            mpv_initialize(handle);
            Ivar ivar = class_getInstanceVariable([MpvWebPlayer class], "_mpv");
            memcpy((char *)(__bridge void *)player + ivar_getOffset(ivar), &handle, sizeof(handle));
            if ([player retryUnverifiedOnNetwork:@""]) return 29;
            [player setValue:@YES forKey:@"allowUnverified"];
            [player setValue:@"network:home" forKey:@"playbackNetwork"];
            { std::lock_guard<std::mutex> guard(gPlaybackNetworkMutex); gPlaybackNetwork = @"network:home"; }
            [player playbackNetworkChanged:[NSNotification notificationWithName:@"NuvioPlaybackNetworkChanged" object:nil userInfo:@{@"network": @"network:away"}]];
            // Read after the queued stop and verification requests, in the same async order.
            mpv_get_property_async(handle, 99, "tls-verify", MPV_FORMAT_STRING);
            BOOL received = NO;
            BOOL secured = NO;
            NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:3];
            while (!received && deadline.timeIntervalSinceNow > 0) {
                mpv_event *event = mpv_wait_event(handle, 0.1);
                if (event->event_id == MPV_EVENT_GET_PROPERTY_REPLY && event->reply_userdata == 99) {
                    mpv_event_property *property = (mpv_event_property *)event->data;
                    const char *verify = property && property->data ? *(char **)property->data : nullptr;
                    secured = verify && strcmp(verify, "yes") == 0;
                    received = YES;
                }
            }
            // The read reply proves completion of the trust reset.
            [player shutdown];
            return secured ? 0 : 30;
        }
        [NSApplication sharedApplication];
        NSView *host = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 100, 100)];
        if ([mode isEqual:@"artwork"]) {
            MpvWebPlayer *player = [MpvWebPlayer new];
            [player setNowPlayingArtworkUrlString:@(argv[2])];
            NSDate *until = [NSDate dateWithTimeIntervalSinceNow:1];
            while (until.timeIntervalSinceNow > 0) [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
            [player shutdown];
            return 0;
        }
        BOOL renderMode = [mode isEqual:@"render-startup"];
        NSWindow *window = nil;
        NSString *source = renderMode ? @(argv[2]) : @"https://example.invalid/stream";
        NSString *trustFile = renderMode ? NuvioWritePlaybackTrustFile(NuvioSystemTrustPEM()) : @"";
        if (renderMode) {
            window = [[NSWindow alloc] initWithContentRect:NSMakeRect(20, 20, 400, 300)
                                                styleMask:NSWindowStyleMaskTitled
                                                  backing:NSBackingStoreBuffered defer:NO];
            window.releasedWhenClosed = NO;
            host.frame = NSMakeRect(0, 0, 400, 300);
            window.contentView = host;
            [window orderFront:nil];
        }
        afterWakeup = [mode isEqual:@"after-wakeup"];
        Class playerClass = renderMode ? [MpvWebPlayer class] : [FailingPlayer class];
        MpvWebPlayer *player = [[playerClass alloc] initWithHostView:host
                sourceUrl:source
                headerLines:@[]
                playWhenReady:NO
                initialPositionMs:0
                controlsUrl:@"about:blank"
                decoderPriority:0
                inMiniPlayer:NO
                allowsRemoteCommands:NO
                headlessForTest:NO
                uiQueue:nil
                javaVm:nullptr
                eventSink:nullptr
                eventMethod:nullptr
                caFile:trustFile
                network:@""
                allowUnverified:NO
                trustWarning:@""];
        __block int result = 22;
        dispatch_semaphore_t completed = dispatch_semaphore_create(0);
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
            @try {
                [player startPlaybackWithSource:source headerLines:@[]
                                  playWhenReady:renderMode initialPositionMs:0 decoderPriority:2];
                if (renderMode) result = 0;
            } @catch (NSException *exception) {
                result = [exception.name isEqual:@"StartupProbe"] ? 0 : 23;
            }
            dispatch_semaphore_signal(completed);
        });
        NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:5];
        while (dispatch_semaphore_wait(completed, DISPATCH_TIME_NOW) != 0) {
            if (deadline.timeIntervalSinceNow <= 0) return 31;
            [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
        }
        if (result != 0) return result;
        if (renderMode) {
            deadline = [NSDate dateWithTimeIntervalSinceNow:10];
            while (([player isLoading] || ![player hasRenderedFrameForTest]) && deadline.timeIntervalSinceNow > 0) {
                [NSApp updateWindows];
                [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
            }
            BOOL rendered = [player hasRenderedFrameForTest] && ![player isLoading];
            dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
                [player shutdown];
                dispatch_semaphore_signal(completed);
            });
            deadline = [NSDate dateWithTimeIntervalSinceNow:5];
            while (dispatch_semaphore_wait(completed, DISPATCH_TIME_NOW) != 0) {
                if (deadline.timeIntervalSinceNow <= 0) return 33;
                [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
            }
            [window close];
            if (trustFile.length) [[NSFileManager defaultManager] removeItemAtPath:trustFile error:nil];
            printf("rendered=%d subviews=%lu\n", rendered, (unsigned long)host.subviews.count);
            return rendered && host.subviews.count == 0 ? 0 : 32;
        }
        printf("shutdowns=%d subviews=%lu\n", shutdowns, (unsigned long)host.subviews.count);
        // Do not pump a dangling callback on the old implementation after reporting its leak.
        return shutdowns == 1 && host.subviews.count == 0 ? 0 : 24;
    }
}
