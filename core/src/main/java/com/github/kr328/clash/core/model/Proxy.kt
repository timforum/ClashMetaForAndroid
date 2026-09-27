package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

@Serializable
data class Proxy(
    val name: String,
    val title: String,
    val subtitle: String,
    val type: Type,
    val delay: Int,
) : Parcelable {
    @Suppress("unused")
    @Serializable(with = TypeSerializer::class)
    enum class Type(val group: Boolean) {
        Direct(false),
        Reject(false),
        RejectDrop(false),
        Compatible(false),
        Pass(false),

        Shadowsocks(false),
        ShadowsocksR(false),
        Snell(false),
        Socks5(false),
        Http(false),
        Vmess(false),
        Vless(false),
        Trojan(false),
        Hysteria(false),
        Hysteria2(false),
        Tuic(false),
        WireGuard(false),

        // Added by the mihomo upgrade. The core learned these protocol names,
        // and every one of them used to arrive here as a string this enum had
        // no entry for, which failed the whole deserialization.
        Ssh(false),
        Mieru(false),
        AnyTLS(false),
        Sudoku(false),
        Masque(false),
        TrustTunnel(false),
        ShadowQuic(false),
        OpenVPN(false),
        Tailscale(false),
        ZeroTier(false),
        EasyTier(false),
        GostRelay(false),

        // Never selectable, but the core can still name them.
        PassRule(false),
        Rematch(false),
        Dns(false),

        Relay(true),
        Selector(true),
        Fallback(true),
        URLTest(true),
        LoadBalance(true),

        Unknown(false);

        companion object {
            fun fromNameOrNull(name: String): Type? = values().firstOrNull { it.name == name }
        }
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<Proxy> {
        override fun createFromParcel(parcel: Parcel): Proxy {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<Proxy?> {
            return arrayOfNulls(size)
        }
    }
}
