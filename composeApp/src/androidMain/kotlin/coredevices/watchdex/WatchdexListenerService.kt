package coredevices.watchdex

import android.net.Uri
import co.touchlab.kermit.Logger
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import coredevices.libindex.database.repository.RingTransferRepository
import coredevices.libindex.device.IndexDeviceManager
import coredevices.libindex.device.IndexIdentifier
import coredevices.ring.service.recordings.RecordingProcessingQueue
import coredevices.ring.storage.RecordingStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.UUID
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Ingests audio published by the watchdex01 Wear OS watch app over the Wear
 * Data Layer at paths matching `/watchdex01/audio/<transferId>`.
 *
 * For each delivered recording:
 *  1. Pulls the AAC `Asset` bytes from the [DataMapItem].
 *  2. Decodes to 16-bit LE mono PCM via [WatchdexAudioDecoder].
 *  3. Inserts a `RingTransfer` row (status = Started) and writes PCM into the
 *     same `RecordingStorage` cache paths a ring capture would use
 *     (`<id>` and `<id>-clean` — both identical for the watch since the watch
 *     already does no DC-bias-removed preprocessing).
 *  4. Marks the transfer Completed with the matching `fileId` and queues the
 *     downstream processing pipeline (transcription, upload, etc.).
 *  5. Calls `IndexDeviceManager.update(...)` so the watch shows up as a
 *     cosmetic [WatchdexIndexDevice] in the Devices list.
 *  6. Deletes the `DataItem` so the Wear Data Layer cache doesn't grow.
 *
 * Registered in `composeApp/src/androidMain/AndroidManifest.xml` with a
 * `<data ... pathPrefix="/watchdex01" />` filter; see pebble-patch/README.md.
 */
class WatchdexListenerService : WearableListenerService() {

    private val ringTransferRepository: RingTransferRepository by inject()
    private val recordingStorage: RecordingStorage by inject()
    private val recordingProcessingQueue: RecordingProcessingQueue by inject()
    private val indexDeviceManager: IndexDeviceManager by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val uri = event.dataItem.uri
            if (uri.path?.startsWith(PATH_PREFIX) != true) continue

            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val asset = map.getAsset("audio") ?: continue
            val transferId = map.getString("transferId") ?: UUID.randomUUID().toString()
            val recordedAt = map.getLong("recordedAt").takeIf { it > 0 } ?: System.currentTimeMillis()
            val nodeId = uri.host ?: "unknown"

            scope.launch { ingest(asset, transferId, recordedAt, nodeId, uri) }
        }
    }

    private suspend fun ingest(
        asset: Asset,
        transferId: String,
        recordedAt: Long,
        nodeId: String,
        dataItemUri: Uri,
    ) {
        val dataClient = Wearable.getDataClient(applicationContext)
        runCatching {
            val fd = Tasks.await(dataClient.getFdForAsset(asset))
            val aacBytes = fd.inputStream.use { it.readBytes() }
            fd.release()

            val decoded = WatchdexAudioDecoder.decodeBytes(aacBytes, cacheDir)
                ?: error("AAC decode returned null for $transferId")

            val recordingId = "watch_${nodeId}-${transferId}-${Uuid.random()}"
            // synthetic collection index — only used as a transfer dedupe key
            val collectionIndex = (recordedAt and 0x7FFFFFFFL).toInt()

            val dbId = ringTransferRepository.createRingTransfer(
                advertisementReceived = Instant.fromEpochMilliseconds(recordedAt),
                startIndex = collectionIndex,
                endIndex = collectionIndex,
            )

            val pcmBytes = decoded.samples.toByteArrayLe()
            recordingStorage.openRecordingSink(recordingId, decoded.sampleRate, MIME_PCM).use { sink ->
                sink.write(pcmBytes)
            }
            recordingStorage.openCleanRecordingSink(recordingId, decoded.sampleRate, MIME_PCM).use { sink ->
                sink.write(pcmBytes)
            }

            ringTransferRepository.markTransferCompleteAndSetFileId(dbId, recordingId)
            // buttonSequence type isn't visible in the open-source slice of haversine;
            // null is what RingSync passes when collectionStartIndex is set and !final.
            recordingProcessingQueue.queueAudioProcessing(dbId, null)

            indexDeviceManager.update(
                WatchdexIndexDevice(
                    identifier = IndexIdentifier("watchdex01-$nodeId"),
                    name = "watchdex01",
                )
            )

            Tasks.await(dataClient.deleteDataItems(dataItemUri))

            logger.i {
                "ingested $recordingId (${aacBytes.size} B AAC -> ${decoded.samples.size} samples @ ${decoded.sampleRate} Hz)"
            }
        }.onFailure {
            logger.e(it) { "ingest failed for $transferId" }
        }
    }

    private fun ShortArray.toByteArrayLe(): ByteArray {
        val out = ByteArray(size * 2)
        var i = 0
        for (s in this) {
            val v = s.toInt()
            out[i++] = (v and 0xFF).toByte()
            out[i++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    companion object {
        private val logger = Logger.withTag("WatchdexListenerService")
        private const val PATH_PREFIX = "/watchdex01/audio"
        private const val MIME_PCM = "audio/raw"
    }
}
