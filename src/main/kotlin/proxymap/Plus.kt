package com.moshy.proxymap

import com.moshy.ProxyMap

/** Apply [lens] onto a receiver and return the transformed object. */
operator fun <T: Any> T.plus(lens: ProxyMap<T>): T = lens.applyToObject(this)