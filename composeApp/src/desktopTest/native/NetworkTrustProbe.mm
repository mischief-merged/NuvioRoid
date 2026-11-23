#include <initializer_list>
#include <cstring>
#include <clocale>
#include "network_trust.h"

int main(int argc, char **argv) {
    @autoreleasepool {
        if (argc == 3 && strcmp(argv[1], "bundle") == 0) {
            NSString *pem = NuvioSystemTrustPEM();
            return pem && [pem writeToFile:[NSString stringWithUTF8String:argv[2]] atomically:YES encoding:NSUTF8StringEncoding error:nil] ? 0 : 1;
        }
        if (argc == 2 && strcmp(argv[1], "headers") == 0) {
            return NuvioValidHeader(@"X-Test: good") && !NuvioValidHeader(@"X-Test: bad\r\nInjected: yes") && !NuvioValidHeader(@"\n") ? 0 : 1;
        }
        if (argc == 3 && strcmp(argv[1], "artwork") == 0) {
            NSString *value = [NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:nil];
            NSData *data = NuvioArtworkData(value);
            printf("received=%d\n", data.length > 0 ? 1 : 0);
            return 0;
        }
        if (argc != 4) return 2;
        setlocale(LC_NUMERIC, "C");
        mpv_handle *mpv = mpv_create();
        if (!mpv || !NuvioSecureMpv(mpv, argv[2], strcmp(argv[3], "allow") == 0)) return 3;
        mpv_set_option_string(mpv, "vo", "null");
        mpv_set_option_string(mpv, "ao", "null");
        mpv_set_option_string(mpv, "pause", "yes");
        mpv_request_log_messages(mpv, "warn");
        if (mpv_initialize(mpv) < 0) return 4;
        const char *load[] = {"loadfile", argv[1], nullptr};
        mpv_command(mpv, load);
        BOOL loaded = NO, certificate = NO, retried = NO;
        NSDate *until = [NSDate dateWithTimeIntervalSinceNow:12];
        while (until.timeIntervalSinceNow > 0) {
            mpv_event *event = mpv_wait_event(mpv, 0.2);
            if (event->event_id == MPV_EVENT_FILE_LOADED) { loaded = YES; break; }
            if (event->event_id == MPV_EVENT_LOG_MESSAGE) {
                const char *text = ((mpv_event_log_message *)event->data)->text;
                fprintf(stderr, "%s", text ?: "");
                certificate |= NuvioCertificateFailure(text);
            }
            if (event->event_id == MPV_EVENT_END_FILE) {
                if (certificate && !retried && strcmp(argv[3], "retry") == 0) {
                    if (mpv_set_property_string(mpv, "tls-verify", "no") < 0) return 5;
                    retried = YES;
                    mpv_command(mpv, load);
                } else break;
            }
        }
        printf("loaded=%d certificate=%d\n", loaded ? 1 : 0, certificate ? 1 : 0);
        mpv_terminate_destroy(mpv);
        return 0;
    }
}
