#import <Security/Security.h>
#import <Foundation/Foundation.h>
#include <initializer_list>
static NSArray *testSettings;
static OSStatus CopySettings(SecCertificateRef, SecTrustSettingsDomain domain, CFArrayRef *settings) {
    if (domain != kSecTrustSettingsDomainUser) return errSecItemNotFound;
    *settings = (__bridge_retained CFArrayRef)testSettings;
    return errSecSuccess;
}
#define SecTrustSettingsCopyTrustSettings CopySettings
#include "network_trust.h"
#undef SecTrustSettingsCopyTrustSettings

int main(int argc, char **argv) {
    @autoreleasepool {
        NSString *mode = argc > 1 ? @(argv[1]) : @"";
        NSDictionary *allow = @{(__bridge NSString *)kSecTrustSettingsResult: @(kSecTrustSettingsResultTrustRoot)};
        if ([mode isEqual:@"siblings"]) {
            for (NSString *key in @[(__bridge NSString *)kSecTrustSettingsPolicyString, (__bridge NSString *)kSecTrustSettingsApplication, (__bridge NSString *)kSecTrustSettingsAllowedError]) {
                NSDictionary *scoped = @{key: @"scoped"};
                for (NSArray *entries in @[@[allow, scoped], @[scoped, allow]]) {
                    testSettings = entries;
                    if (!NuvioAnchorAllowed(nullptr, NO)) return 1;
                }
            }
        } else if ([mode isEqual:@"usage"]) {
            testSettings = @[@{(__bridge NSString *)kSecTrustSettingsKeyUsage: @(kSecTrustSettingsKeyUseSignCert)}];
            if (!NuvioAnchorAllowed(nullptr, NO)) return 2;
        } else if ([mode isEqual:@"ssl-server"]) {
            if (@available(macOS 15.4, *)) {
                SecPolicyRef policy = SecPolicyCreateWithProperties(kSecPolicyAppleSSLServer, nullptr);
                if (!policy) return 3;
                testSettings = @[@{(__bridge NSString *)kSecTrustSettingsPolicy: (__bridge id)policy}];
                BOOL allowed = NuvioAnchorAllowed(nullptr, NO);
                CFRelease(policy);
                if (!allowed) return 4;
            }
        } else if ([mode isEqual:@"deny"]) {
            testSettings = @[allow, @{(__bridge NSString *)kSecTrustSettingsResult: @(kSecTrustSettingsResultDeny)}];
            if (NuvioAnchorAllowed(nullptr, YES)) return 5;
            testSettings = @[@{(__bridge NSString *)kSecTrustSettingsPolicyString: @"only.example"}];
            if (NuvioAnchorAllowed(nullptr, NO)) return 6;
        } else if ([mode isEqual:@"evaluate"] && argc == 4) {
            NSData *(^readPEM)(NSString *) = ^NSData *(NSString *path) {
                NSString *pem = [NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:nil];
                NSString *base64 = [[pem stringByReplacingOccurrencesOfString:@"-----BEGIN CERTIFICATE-----" withString:@""] stringByReplacingOccurrencesOfString:@"-----END CERTIFICATE-----" withString:@""];
                return [[NSData alloc] initWithBase64EncodedString:base64 options:NSDataBase64DecodingIgnoreUnknownCharacters];
            };
            NSData *der = readPEM(@(argv[2]));
            NSData *root = readPEM(@(argv[3]));
            SecCertificateRef certificate = SecCertificateCreateWithData(nullptr, (__bridge CFDataRef)root);
            if (!certificate) return 11;
            NSArray *anchors = @[(__bridge id)certificate];
            BOOL valid = NuvioEvaluateServerTrust(@[der, root], @"localhost", (__bridge CFArrayRef)anchors);
            BOOL wrongHost = NuvioEvaluateServerTrust(@[der, root], @"other.example", (__bridge CFArrayRef)anchors);
            BOOL untrusted = NuvioEvaluateServerTrust(@[der, root], @"localhost");
            CFRelease(certificate);
            printf("valid=%d wrong-host=%d untrusted=%d\n", valid, wrongHost, untrusted);
            if (!valid || wrongHost || untrusted) return 12;
        } else return 10;
        return 0;
    }
}
