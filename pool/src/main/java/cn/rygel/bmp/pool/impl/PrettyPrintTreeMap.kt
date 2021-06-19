package cn.rygel.bmp.pool.impl

import java.lang.StringBuilder
import java.util.*

// Never serialized.
internal class PrettyPrintTreeMap<K, V> : TreeMap<K, V>() {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("( ")
        for ((key, value) in entries) {
            sb.append('{').append(key).append(':').append(value).append("}, ")
        }
        if (!isEmpty()) {
            sb.replace(sb.length - 2, sb.length, "")
        }
        return sb.append(" )").toString()
    }
}