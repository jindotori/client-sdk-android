package io.livekit.android.room.participant

import android.content.Context
import com.github.ajalt.timberkt.Timber
import com.google.protobuf.ByteString
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.track.*
import livekit.LivekitModels
import livekit.LivekitRtc
import org.webrtc.*
import java.nio.ByteBuffer

class LocalParticipant
@AssistedInject
internal constructor(
    @Assisted
    info: LivekitModels.ParticipantInfo,
    internal val engine: RTCEngine,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val context: Context,
    private val eglBase: EglBase,
) :
    Participant(info.sid, info.identity) {

    init {
        updateFromInfo(info)
    }

    private val localTrackPublications
        get() = tracks.values.toList()

    fun createAudioTrack(
        audioConstraints: MediaConstraints = MediaConstraints(),
        name: String = ""
    ) = LocalAudioTrack.createTrack(peerConnectionFactory, audioConstraints, name)

    fun createVideoTrack(
        isScreencast: Boolean = false,
        name: String = "",
    ): LocalVideoTrack {
        return LocalVideoTrack.createTrack(
            peerConnectionFactory,
            context,
            isScreencast,
            name,
            eglBase
        )
    }

    suspend fun publishAudioTrack(
        track: LocalAudioTrack,
        publishListener: PublishListener? = null
    ) {
        if (localTrackPublications.any { it.track == track }) {
            publishListener?.onPublishFailure(TrackException.PublishException("Track has already been published"))
            return
        }

        val cid = track.rtcTrack.id()
        val trackInfo =
            engine.addTrack(cid = cid, name = track.name, kind = track.kind)
        val transInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf(this.sid)
        )
        // TODO: sendEncodings to customize
        val transceiver =
            engine.publisher.peerConnection.addTransceiver(track.rtcTrack, transInit)

        if (transceiver == null) {
            publishListener?.onPublishFailure(TrackException.PublishException("null sender returned from peer connection"))
            return
        }

        val publication = LocalTrackPublication(trackInfo, track, this)
        addTrackPublication(publication)
        publishListener?.onPublishSuccess(publication)
    }

    suspend fun publishVideoTrack(
        track: LocalVideoTrack,
        publishListener: PublishListener? = null
    ) {
        if (localTrackPublications.any { it.track == track }) {
            publishListener?.onPublishFailure(TrackException.PublishException("Track has already been published"))
            return
        }

        val cid = track.rtcTrack.id()
        val trackInfo =
            engine.addTrack(cid = cid, name = track.name, kind = LivekitModels.TrackType.VIDEO)
        val transInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf(this.sid)
        )
        // TODO: video encodings & simulcast
        val transceiver =
            engine.publisher.peerConnection.addTransceiver(track.rtcTrack, transInit)

        if (transceiver == null) {
            publishListener?.onPublishFailure(TrackException.PublishException("null sender returned from peer connection"))
            return
        }

        val publication = LocalTrackPublication(trackInfo, track, this)
        addTrackPublication(publication)
        publishListener?.onPublishSuccess(publication)
    }

    fun unpublishTrack(track: Track) {
        val publication = localTrackPublications.firstOrNull { it.track == track }
        if (publication === null) {
            Timber.d { "this track was never published." }
            return
        }
        track.stop()
        if (track is MediaTrack) {
            unpublishMediaTrack(track, sid)
        }
        val sid = publication.sid
        tracks.remove(sid)
        when (publication.kind) {
            LivekitModels.TrackType.AUDIO -> audioTracks.remove(sid)
            LivekitModels.TrackType.VIDEO -> videoTracks.remove(sid)
            LivekitModels.TrackType.DATA -> dataTracks.remove(sid)
            else -> {}
        }
    }

    /**
     * Publish a new data payload to the room. Data will be forwarded to each participant in the room.
     * Each payload must not exceed 15k in size
     *
     * @param data payload to send
     * @param reliability for delivery guarantee, use RELIABLE. for fastest delivery without guarantee, use LOSSY
     */
    fun publishData(data: ByteArray, reliability: DataPublishReliability) {
        if (data.size > RTCEngine.MAX_DATA_PACKET_SIZE) {
            throw IllegalArgumentException("cannot publish data larger than " + RTCEngine.MAX_DATA_PACKET_SIZE)
        }

        val kind = when (reliability) {
            DataPublishReliability.RELIABLE -> LivekitRtc.DataPacket.Kind.RELIABLE
            DataPublishReliability.LOSSY -> LivekitRtc.DataPacket.Kind.LOSSY
        }
        val channel = when (reliability) {
            DataPublishReliability.RELIABLE -> engine.reliableDataChannel
            DataPublishReliability.LOSSY -> engine.lossyDataChannel
        } ?: throw TrackException.PublishException("data channel not established")

        val userPacket = LivekitRtc.UserPacket.newBuilder().
                setPayload(ByteString.copyFrom(data)).
                setParticipantSid(sid).
                build()
        val dataPacket = LivekitRtc.DataPacket.newBuilder().
            setUser(userPacket).
            setKind(kind).
            build()
        val buf = DataChannel.Buffer(
            ByteBuffer.wrap(dataPacket.toByteArray()),
            true,
        )

        channel.send(buf)
    }

    override fun updateFromInfo(info: LivekitModels.ParticipantInfo) {
        super.updateFromInfo(info)

        // detect tracks that have been muted on the server side, apply those changes
        for (ti in info.tracksList) {
            val publication = this.tracks[ti.sid] as? LocalTrackPublication ?: continue
            if (ti.muted != publication.muted) {
                publication.setMuted(ti.muted)
            }
        }
    }

    private fun <T> unpublishMediaTrack(
        track: T,
        sid: String
    ) where T : MediaTrack {
        val senders = engine.publisher.peerConnection.senders ?: return
        for (sender in senders) {
            val t = sender.track() ?: continue
            if (t == track.rtcTrack) {
                engine.publisher.peerConnection.removeTrack(sender)
            }
        }
    }

    interface PublishListener {
        fun onPublishSuccess(publication: TrackPublication) {}
        fun onPublishFailure(exception: Exception) {}
    }

    @AssistedFactory
    interface Factory {
        fun create(info: LivekitModels.ParticipantInfo): LocalParticipant
    }
}
