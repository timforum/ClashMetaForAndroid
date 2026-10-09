package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.common.util.createListFromParcelSlice
import com.github.kr328.clash.common.util.writeToParcelSlice
import kotlinx.serialization.Serializable

@Serializable
data class ProxyGroup(
    val type: String,
    val proxies: List<Proxy>,
    val now: String,
    /**
     * Leaf proxy the connection is on behind this group, reached by following
     * the selection down. For a group whose members are other groups [now]
     * answers with the sub group it routes through, which is not a node a
     * gate could measure or a selector could be moved to; this is the one a
     * caller that has to name one node acts on.
     */
    val inUse: String = "",
) : Parcelable {
    class SliceProxyList(data: List<Proxy>) : List<Proxy> by data, Parcelable {
        constructor(parcel: Parcel) : this(Proxy.createListFromParcelSlice(parcel, 0, 50))

        override fun describeContents(): Int {
            return 0
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            writeToParcelSlice(dest, flags)
        }

        companion object CREATOR : Parcelable.Creator<SliceProxyList> {
            override fun createFromParcel(parcel: Parcel): SliceProxyList {
                return SliceProxyList(parcel)
            }

            override fun newArray(size: Int): Array<SliceProxyList?> {
                return arrayOfNulls(size)
            }
        }
    }

    constructor(parcel: Parcel) : this(
        parcel.readString()!!,
        SliceProxyList(parcel),
        parcel.readString()!!,
        parcel.readString() ?: "",
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(type)
        SliceProxyList(proxies).writeToParcel(parcel, 0)
        parcel.writeString(now)
        parcel.writeString(inUse)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<ProxyGroup> {
        override fun createFromParcel(parcel: Parcel): ProxyGroup {
            return ProxyGroup(parcel)
        }

        override fun newArray(size: Int): Array<ProxyGroup?> {
            return arrayOfNulls(size)
        }
    }
}