package coredevices.watchdex

import coredevices.libindex.device.IndexIdentifier
import coredevices.libindex.device.KnownIndexDevice

/**
 * Cosmetic [KnownIndexDevice] that surfaces the paired Wear OS watch in the
 * Pebble app's device list. It does not participate in BLE pairing or the
 * haversine satellite manager — audio arrives over the Wear Data Layer via
 * [WatchdexListenerService] and is injected directly into the recording
 * pipeline (same Room rows, same processing queue) as if it were a ring
 * transfer.
 *
 * `remove()` is intentionally a no-op: dismissing the watch from the device
 * list should NOT clear `prefs.ringPaired` (that slot belongs to the real
 * Index ring), and there is nothing to unbond at the BT layer. If you want
 * "stop showing watch recordings" UX, hook it to its own preference and
 * have `WatchdexListenerService` skip ingestion when set.
 */
class WatchdexIndexDevice(
    override val identifier: IndexIdentifier,
    override val name: String = "watchdex01",
) : KnownIndexDevice {
    override fun remove() {
        // see KDoc
    }
}
