package io.openusbip.server.net

import io.openusbip.server.protocol.UsbipDevice

/**
 * The set of devices this phone is offering, and who currently holds each one.
 *
 * USB/IP is exclusive per device: a USB peripheral has exactly one host, so
 * once a client imports a device no other client may. `usbipd` implements this
 * by dropping an in-use device from the exported list entirely rather than
 * advertising it as busy, and this matches that -- a second client simply does
 * not see it.
 */
class DeviceRegistry {

    private data class Entry(val backend: UsbBackend, var claimedBy: String? = null)

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    /** Offer a device. Replaces any previous entry with the same busid. */
    fun add(backend: UsbBackend) = synchronized(lock) {
        entries[backend.busid] = Entry(backend)
    }

    /**
     * Withdraw a device, e.g. because it was unplugged or the user stopped
     * sharing it. Any holder is dropped; its next URB fails and the client
     * sees the device go away.
     */
    fun remove(busid: String): UsbBackend? = synchronized(lock) {
        entries.remove(busid)?.also { it.backend.close() }?.backend
    }

    fun clear() = synchronized(lock) {
        entries.values.forEach { it.backend.close() }
        entries.clear()
    }

    /** Everything offered, claimed or not. For the UI, not for the wire. */
    fun all(): List<Pair<UsbipDevice, String?>> = synchronized(lock) {
        entries.values.map { it.backend.device to it.claimedBy }
    }

    /**
     * What a client should see in OP_REP_DEVLIST: only unclaimed devices.
     */
    fun availableDevices(): List<UsbipDevice> = synchronized(lock) {
        entries.values.filter { it.claimedBy == null }.map { it.backend.device }
    }

    /**
     * Try to claim [busid] for [client].
     *
     * Returns the backend on success, or null if the device is unknown or
     * already held -- both of which the server answers with a failed IMPORT.
     */
    fun claim(busid: String, client: String): UsbBackend? = synchronized(lock) {
        val entry = entries[busid] ?: return null
        if (entry.claimedBy != null) return null
        entry.claimedBy = client
        entry.backend.open()
        return entry.backend
    }

    /**
     * Release [busid] if held by [client].
     *
     * Scoped to the holder so a stale session cannot release a device that has
     * since been claimed by someone else.
     */
    fun release(busid: String, client: String) = synchronized(lock) {
        val entry = entries[busid] ?: return
        if (entry.claimedBy == client) {
            entry.claimedBy = null
            entry.backend.close()
        }
    }

    /** Release everything held by [client]; used when a connection drops. */
    fun releaseAll(client: String) = synchronized(lock) {
        entries.values.filter { it.claimedBy == client }.forEach {
            it.claimedBy = null
            it.backend.close()
        }
    }

    fun holderOf(busid: String): String? = synchronized(lock) { entries[busid]?.claimedBy }

    fun size(): Int = synchronized(lock) { entries.size }
}
