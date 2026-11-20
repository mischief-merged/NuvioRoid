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
            char *verify = mpv_get_property_string(handle, "tls-verify");
            BOOL secured = verify && strcmp(verify, "yes") == 0;
            mpv_free(verify);
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
        afterWakeup = [mode isEqual:@"after-wakeup"];
        @try {
            [[FailingPlayer alloc] initWithHostView:host sourceUrl:@"https://example.invalid/stream" headerLines:@[] playWhenReady:NO initialPositionMs:0 controlsUrl:@"about:blank" decoderPriority:0 javaVm:nullptr eventSink:nullptr eventMethod:nullptr caFile:@"" network:@"" allowUnverified:NO trustWarning:@""];
            return 22;
        } @catch (NSException *exception) {
            if (![exception.name isEqual:@"StartupProbe"]) return 23;
        }
        printf("shutdowns=%d subviews=%lu\n", shutdowns, (unsigned long)host.subviews.count);
        // Do not pump a dangling callback on the old implementation after reporting its leak.
        return shutdowns == 1 && host.subviews.count == 0 ? 0 : 24;
    }
}
