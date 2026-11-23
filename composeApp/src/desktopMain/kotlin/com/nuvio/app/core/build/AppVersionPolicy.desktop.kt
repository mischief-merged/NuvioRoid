package com.nuvio.app.core.build

actual object AppVersionPolicy {
    actual val packageVersionName: String = AppVersionConfig.DESKTOP_VERSION_NAME
    actual val displayVersionName: String = decodeForkVersion(packageVersionName)
    actual val displayVersionCode: Int = AppVersionConfig.DESKTOP_VERSION_CODE
    actual val basedOnVersionName: String? = AppVersionConfig.VERSION_NAME.takeIf { it != packageVersionName }
    actual val userAgentAppName: String = "NuvioDesktop"
}
