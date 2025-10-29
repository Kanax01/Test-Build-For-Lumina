package com.project.lumina.relay

import com.project.lumina.relay.handler.SessionCloseHandler
import com.project.lumina.relay.listener.LuminaRelayPacketListener
import io.netty.util.internal.PlatformDependent
import org.cloudburstmc.protocol.bedrock.BedrockClientSession
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket
import java.util.Queue

class LuminaRelaySession(peer: BedrockPeer, subClientId: Int, val luminaRelay: LuminaRelay) {
    val server = ServerSession(peer, subClientId)
    val listeners: MutableList<LuminaRelayPacketListener> = ArrayList()
    private val packetQueue: Queue<Pair<BedrockPacket, Boolean>> = PlatformDependent.newMpscQueue()

    var client: ClientSession? = null
        internal set(value) {
            value?.apply {
                codec = server.codec
                peer.codecHelper.apply {
                    blockDefinitions = server.peer.codecHelper.blockDefinitions
                    itemDefinitions = server.peer.codecHelper.itemDefinitions
                    cameraPresetDefinitions = server.peer.codecHelper.cameraPresetDefinitions
                    encodingSettings = server.peer.codecHelper.encodingSettings
                }
                packetQueue.forEach { (packet, immediate) ->
                    if (immediate) sendPacketImmediately(packet) else sendPacket(packet)
                }
                packetQueue.clear()
            }
            field = value
        }

    fun clientBound(packet: BedrockPacket) = server.sendPacket(packet)
    fun clientBoundImmediately(packet: BedrockPacket) = server.sendPacketImmediately(packet)

    fun serverBound(packet: BedrockPacket) =
        client?.sendPacket(packet) ?: packetQueue.add(packet to false)

    fun serverBoundImmediately(packet: BedrockPacket) =
        client?.sendPacketImmediately(packet) ?: packetQueue.add(packet to true)

    private fun createDisconnectHandler(isServer: Boolean) = SessionCloseHandler { reason ->
        println("${if (isServer) "Server" else "Client"} disconnect: $reason")
        runCatching {
            (if (isServer) server else client)?.disconnect()
            listeners.forEach { it.onDisconnect(reason) }
        }
    }

    private fun processPacket(wrapper: BedrockPacketWrapper,
                              beforeFunc: (LuminaRelayPacketListener) -> Boolean,
                              sendFunc: (BedrockPacket) -> Unit,
                              afterFunc: (LuminaRelayPacketListener) -> Unit) {
        listeners.forEach {
            runCatching { if (beforeFunc(it)) return }
                .onFailure {
                    when (it) {
                        is NoClassDefFoundError -> {
                            if (it.message?.contains("java/awt/Color") == true) {
                                println("AWT Color error detected - BiomeDefinitionListPacket compatibility issue: ${it.message}")
                            } else {
                                println("NoClassDefFoundError in packet processing: ${it.message}")
                            }
                        }
                        else -> println("Before packet error: ${it.stackTraceToString()}")
                    }
                }
        }

        runCatching {
            UnknownPacket().apply {
                payload = wrapper.packetBuffer.retainedSlice().skipBytes(wrapper.headerLength)
                packetId = wrapper.packetId
            }.let(sendFunc)
        }.onFailure {
            when (it) {
                is NoClassDefFoundError -> {
                    if (it.message?.contains("java/awt/Color") == true) {
                        println("AWT Color error in packet send - skipping packet: ${it.message}")
                        return
                    } else {
                        println("NoClassDefFoundError in packet send: ${it.message}")
                    }
                }
                else -> println("Packet send error: ${it.stackTraceToString()}")
            }
        }

        listeners.forEach {
            runCatching { afterFunc(it) }
                .onFailure {
                    when (it) {
                        is NoClassDefFoundError -> {
                            if (it.message?.contains("java/awt/Color") == true) {
                                println("AWT Color error in after packet processing: ${it.message}")
                            } else {
                                println("NoClassDefFoundError in after packet processing: ${it.message}")
                            }
                        }
                        else -> println("After packet error: ${it.stackTraceToString()}")
                    }
                }
        }
    }

    inner class ServerSession(peer: BedrockPeer, subClientId: Int) : BedrockServerSession(peer, subClientId) {
        init { packetHandler = createDisconnectHandler(false) }

        override fun onPacket(wrapper: BedrockPacketWrapper) = processPacket(
            wrapper,
            { it.beforeClientBound(wrapper.packet) },
            ::serverBound,
            { it.afterClientBound(wrapper.packet) }
        )
    }

    inner class ClientSession(peer: BedrockPeer, subClientId: Int) : BedrockClientSession(peer, subClientId) {
        init { packetHandler = createDisconnectHandler(true) }

        override fun onPacket(wrapper: BedrockPacketWrapper) = processPacket(
            wrapper,
            { it.beforeServerBound(wrapper.packet) },
            ::clientBound,
            { it.afterServerBound(wrapper.packet) }
        )
    }
}