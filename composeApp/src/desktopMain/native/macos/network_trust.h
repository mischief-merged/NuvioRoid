#import <Foundation/Foundation.h>
#import <Security/Security.h>
#include <mpv/client.h>

static BOOL NuvioValidHeader(NSString *line) {
    return [line rangeOfCharacterFromSet:[NSCharacterSet characterSetWithCharactersInString:@"\r\n"]].location == NSNotFound;
}

// Respect user/admin trust overrides before exporting an anchor. A PEM bundle cannot represent
// host/application exceptions, so scoped SSL overrides are excluded instead of broadened.
static BOOL NuvioAnchorAllowed(SecCertificateRef cert, BOOL systemDefault) {
    for (SecTrustSettingsDomain domain : {kSecTrustSettingsDomainUser, kSecTrustSettingsDomainAdmin}) {
        CFArrayRef raw = nullptr;
        OSStatus status = SecTrustSettingsCopyTrustSettings(cert, domain, &raw);
        if (status == errSecItemNotFound) continue;
        if (status != errSecSuccess) return NO;
        NSArray *settings = CFBridgingRelease(raw);
        if (settings.count == 0) return YES;
        BOOL allowed = NO;
        for (NSDictionary *entry in settings) {
            id policy = entry[(__bridge NSString *)kSecTrustSettingsPolicy];
            if (policy) {
                NSDictionary *properties = CFBridgingRelease(SecPolicyCopyProperties((__bridge SecPolicyRef)policy));
                if ([properties[(__bridge NSString *)kSecPolicyClient] boolValue]) continue;
                id oid = properties[(__bridge NSString *)kSecPolicyOid];
                BOOL ssl = [oid isEqual:(__bridge NSString *)kSecPolicyAppleSSL];
                if (@available(macOS 15.4, *)) ssl |= [oid isEqual:(__bridge NSString *)kSecPolicyAppleSSLServer];
                if (!ssl && ![oid isEqual:(__bridge NSString *)kSecPolicyAppleX509Basic]) continue;
            }
            NSNumber *result = entry[(__bridge NSString *)kSecTrustSettingsResult];
            NSInteger value = result ? result.integerValue : kSecTrustSettingsResultTrustRoot;
            // A PEM file cannot encode a scoped deny. Do not accidentally broaden it.
            if (value == kSecTrustSettingsResultDeny) return NO;
            if (entry[(__bridge NSString *)kSecTrustSettingsPolicyString] ||
                entry[(__bridge NSString *)kSecTrustSettingsApplication] ||
                entry[(__bridge NSString *)kSecTrustSettingsAllowedError]) continue;
            NSNumber *usage = entry[(__bridge NSString *)kSecTrustSettingsKeyUsage];
            if (usage && !(usage.unsignedIntValue & kSecTrustSettingsKeyUseSignCert)) continue;
            allowed |= value == kSecTrustSettingsResultTrustRoot || value == kSecTrustSettingsResultTrustAsRoot;
        }
        if (allowed) return YES;
    }
    return systemDefault;
}

// GnuTLS does not consult Keychain. Export the system roots plus explicitly trusted user/admin CAs.
static NSSet<NSData *> *NuvioCertificateCandidates(NSMutableSet<NSData *> *system) {
    CFArrayRef anchors = nullptr;
    if (SecTrustCopyAnchorCertificates(&anchors) != errSecSuccess || !anchors) return nil;
    for (id item in (__bridge NSArray *)anchors) {
        NSData *data = CFBridgingRelease(SecCertificateCopyData((__bridge SecCertificateRef)item));
        if (data) [system addObject:data];
    }
    CFRelease(anchors);
    NSMutableSet<NSData *> *candidates = [system mutableCopy];
    for (SecTrustSettingsDomain domain : {kSecTrustSettingsDomainUser, kSecTrustSettingsDomainAdmin}) {
        CFArrayRef certs = nullptr;
        OSStatus status = SecTrustSettingsCopyCertificates(domain, &certs);
        if (status != errSecSuccess && status != errSecNoTrustSettings) return nil;
        if (!certs) continue;
        for (id item in (__bridge NSArray *)certs) {
            NSData *data = CFBridgingRelease(SecCertificateCopyData((__bridge SecCertificateRef)item));
            if (data) [candidates addObject:data];
        }
        CFRelease(certs);
    }
    return candidates;
}

static NSString *NuvioSystemTrustPEM(void) {
    NSMutableSet<NSData *> *system = [NSMutableSet set];
    NSSet<NSData *> *candidates = NuvioCertificateCandidates(system);
    if (!candidates) return nil;
    NSMutableString *pem = [NSMutableString string];
    for (NSData *der in candidates) {
        SecCertificateRef cert = SecCertificateCreateWithData(nullptr, (__bridge CFDataRef)der);
        BOOL accepted = cert && NuvioAnchorAllowed(cert, [system containsObject:der]);
        if (cert) CFRelease(cert);
        if (accepted) {
            [pem appendFormat:@"-----BEGIN CERTIFICATE-----\n%@\n-----END CERTIFICATE-----\n",
                [der base64EncodedStringWithOptions:NSDataBase64Encoding64CharacterLineLength | NSDataBase64EncodingEndLineWithLineFeed]];
        }
    }
    return pem.length ? pem : nil;
}

// Let Security.framework enforce hostname, application, key usage, expiry and deny settings.
// Additional anchors are used only by isolated probes; the application always passes nullptr.
static BOOL NuvioEvaluateServerTrust(NSArray<NSData *> *chain, NSString *host, CFArrayRef additionalAnchors = nullptr) {
    if (!host.length || !chain.count) return NO;
    NSMutableArray *certificates = [NSMutableArray arrayWithCapacity:chain.count];
    for (NSData *der in chain) {
        SecCertificateRef certificate = SecCertificateCreateWithData(nullptr, (__bridge CFDataRef)der);
        if (!certificate) return NO;
        [certificates addObject:CFBridgingRelease(certificate)];
    }
    SecPolicyRef policy = SecPolicyCreateSSL(true, (__bridge CFStringRef)host);
    if (!policy) return NO;
    SecTrustRef trust = nullptr;
    OSStatus status = SecTrustCreateWithCertificates((__bridge CFArrayRef)certificates, policy, &trust);
    CFRelease(policy);
    if (status != errSecSuccess || !trust) return NO;
    // Do not let certificate-controlled AIA URLs open unchecked private-network connections.
    status = SecTrustSetNetworkFetchAllowed(trust, false);
    if (status == errSecSuccess && additionalAnchors) status = SecTrustSetAnchorCertificates(trust, additionalAnchors);
    BOOL accepted = status == errSecSuccess && SecTrustEvaluateWithError(trust, nullptr);
    CFRelease(trust);
    return accepted;
}

static BOOL NuvioSecureMpv(mpv_handle *mpv, const char *caFile, BOOL allowUnverified) {
    // Fail closed if this runtime does not understand a security option.
    const char *options[][2] = {
        {"config", "no"}, {"load-scripts", "no"}, {"ytdl", "no"}, {"osc", "no"},
        {"load-stats-overlay", "no"}, {"load-osd-console", "no"}, {"load-auto-profiles", "no"},
        {"input-default-bindings", "no"}, {"input-terminal", "no"},
        {"tls-verify", allowUnverified ? "no" : "yes"}, {"tls-ca-file", caFile},
    };
    for (const auto &option : options) {
        if (mpv_set_option_string(mpv, option[0], option[1]) < 0) return NO;
    }
    return YES;
}

static BOOL NuvioCertificateFailure(const char *message) {
    NSString *text = message ? [[NSString stringWithUTF8String:message] lowercaseString] : @"";
    return [text containsString:@"certificate failed verification"] ||
        [text containsString:@"unable to verify peer certificate"] ||
        [text containsString:@"certificate's owner does not match hostname"] ||
        [text containsString:@"certificate verify failed"];
}

static NSData *NuvioArtworkData(NSString *value) {
    NSString *prefix = @"data:image/png;base64,";
    if (![value hasPrefix:prefix] || value.length > prefix.length + ((8 * 1024 * 1024 + 2) / 3) * 4) return nil;
    NSData *data = [[NSData alloc] initWithBase64EncodedString:[value substringFromIndex:prefix.length] options:0];
    return data.length <= 8 * 1024 * 1024 ? data : nil;
}
