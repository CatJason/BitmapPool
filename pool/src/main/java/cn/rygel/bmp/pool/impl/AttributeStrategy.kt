package cn.rygel.bmp.pool.impl

import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
import cn.rygel.bmp.utils.getBitmapByteSize

internal class AttributeStrategy : LruPoolStrategy {
    private val keyPool = KeyPool()
    private val groupedMap = GroupedLinkedMap<Key?, Bitmap>()
    override fun put(bitmap: Bitmap) {
        val key = keyPool[bitmap.width, bitmap.height, bitmap.config]
        groupedMap.put(key, bitmap)
    }

    override fun get(width: Int, height: Int, config: Bitmap.Config?): Bitmap? {
        val key = keyPool[width, height, config]
        return groupedMap[key]
    }

    override fun removeLast(): Bitmap? {
        return groupedMap.removeLast()
    }

    override fun logBitmap(bitmap: Bitmap): String {
        return getBitmapString(bitmap)
    }

    override fun logBitmap(width: Int, height: Int, config: Bitmap.Config?): String {
        return getBitmapString(width, height, config)
    }

    override fun getSize(bitmap: Bitmap): Int {
        return getBitmapByteSize(bitmap)
    }

    override fun toString(): String {
        return "AttributeStrategy:\n  $groupedMap"
    }

    @VisibleForTesting
    internal class KeyPool : BaseKeyPool<Key?>() {
        operator fun get(width: Int, height: Int, config: Bitmap.Config?): Key? {
            val result = get()
            result?.init(width, height, config)
            return result
        }

        override fun create(): Key {
            return Key(this)
        }
    }

    @VisibleForTesting
    internal class Key(private val pool: KeyPool) : Poolable {
        private var width = 0
        private var height = 0

        // Config can be null :(
        private var config: Bitmap.Config? = null
        fun init(width: Int, height: Int, config: Bitmap.Config?) {
            this.width = width
            this.height = height
            this.config = config
        }

        override fun equals(other: Any?): Boolean {
            if (other is Key) {
                return width == other.width && height == other.height && config == other.config
            }
            return false
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + if (config != null) config.hashCode() else 0
            return result
        }

        override fun toString(): String {
            return getBitmapString(width, height, config)
        }

        override fun offer() {
            pool.offer(this)
        }
    }

    companion object {
        private fun getBitmapString(bitmap: Bitmap): String {
            return getBitmapString(bitmap.width, bitmap.height, bitmap.config)
        }

        fun getBitmapString(width: Int, height: Int, config: Bitmap.Config?): String {
            return "[" + width + "x" + height + "], " + config
        }
    }
}