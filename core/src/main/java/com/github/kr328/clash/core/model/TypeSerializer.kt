package com.github.kr328.clash.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Reads a [Proxy.Type] without ever failing.
 *
 * The enum mirrors the adapter type names the core sends over. A default
 * kotlinx enum decoder throws `IllegalArgumentException` for a name it does
 * not know, and because this sits inside a `Proxy` list that came across
 * binder, that exception propagated into the UI process and took the screen
 * down with it. The core gained a batch of protocol names in this upgrade
 * (anytls, ssh, mieru, tailscale, ...) and each missing one was a crash the
 * first time a subscription containing it was opened.
 *
 * Falling back to [Proxy.Type.Unknown] means an unrecognised protocol shows up
 * as an unknown node in the list instead of taking the process with it, which
 * is the whole point: a name the UI has never heard of is not an error.
 */
object TypeSerializer : KSerializer<Proxy.Type> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Proxy.Type", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Proxy.Type {
        val name = decoder.decodeString()

        return Proxy.Type.fromNameOrNull(name) ?: Proxy.Type.Unknown
    }

    override fun serialize(encoder: Encoder, value: Proxy.Type) {
        encoder.encodeString(value.name)
    }
}
