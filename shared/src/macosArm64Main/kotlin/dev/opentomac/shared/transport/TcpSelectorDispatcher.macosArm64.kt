package dev.opentomac.shared.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val tcpSelectorDispatcher: CoroutineDispatcher = Dispatchers.Default
