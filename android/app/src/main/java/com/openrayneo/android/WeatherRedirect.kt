// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import okhttp3.HttpUrl

/** Permit canonical weather URLs without broadening the destination of a request. */
internal object WeatherRedirect {
    fun resolve(original: HttpUrl, location: String): HttpUrl {
        val target = requireNotNull(original.resolve(location)) { "Weather redirect address is invalid." }
        require(target.scheme == "https" && target.host == original.host && target.port == 443 &&
            target.username.isEmpty() && target.password.isEmpty()) { "Weather service returned an unexpected redirect." }
        return target
    }
}
