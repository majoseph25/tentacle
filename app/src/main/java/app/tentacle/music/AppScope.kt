// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime scope for fire-and-forget server calls that must outlive a screen or the
 * service (revoking a token on sign-out, the final "stopped" playback report).
 */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)
