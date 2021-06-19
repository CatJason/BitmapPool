package cn.rygel.bmp.pool.impl

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import cn.rygel.bmp.utils.getBitmapByteSize
import java.util.*

/**
 * Keys [Bitmaps][Bitmap] using both [ ][Bitmap.getAllocationByteCount] and the [Bitmap.Config]
 * returned from [Bitmap.getConfig].
 *
 *
 * Using both the config and the byte size allows us to safely re-use a greater variety of [ ], which increases the hit rate of the pool and therefore the
 * performance of applications. This class works around #301 by only allowing re-use of [ ] with a matching number of bytes per pixel.
 */
@RequiresApi(Build.VERSION_CODES.KITKAT)
class SizeConfigStrategy : LruPoolStrategy {
    companion object {
        private const val MAX_SIZE_MULTIPLE = 8
        private val ARGB_8888_IN_CONFIGS: Array<Bitmap.Config?>

        init {
            var result = arrayOf(
                Bitmap.Config.ARGB_8888,  // The value returned by Bitmaps with the hidden Bitmap config.
                null
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                result = result.copyOf(result.size + 1)
                result[result.size - 1] = Bitmap.Config.RGBA_F16
            }
            ARGB_8888_IN_CONFIGS = result
        }

        private val RGBA_F16_IN_CONFIGS = ARGB_8888_IN_CONFIGS

        // We probably could allow ARGB_4444 and RGB_565 to decode into each other, but ARGB_4444 is
        // deprecated and we'd rather be safe.
        private val RGB_565_IN_CONFIGS = arrayOf<Bitmap.Config?>(Bitmap.Config.RGB_565)
        private val ARGB_4444_IN_CONFIGS = arrayOf<Bitmap.Config?>(Bitmap.Config.ARGB_4444)
        private val ALPHA_8_IN_CONFIGS = arrayOf<Bitmap.Config?>(Bitmap.Config.ALPHA_8)
        fun getBitmapString(size: Int, config: Bitmap.Config?): String {
            return "[$size]($config)"
        }

        private fun getInConfigs(requested: Bitmap.Config?): Array<Bitmap.Config?> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (Bitmap.Config.RGBA_F16 == requested) { // NOPMD - Avoid short circuiting sdk checks.
                    return RGBA_F16_IN_CONFIGS
                }
            }
            return when (requested) {
                Bitmap.Config.ARGB_8888 -> ARGB_8888_IN_CONFIGS
                Bitmap.Config.RGB_565 -> RGB_565_IN_CONFIGS
                Bitmap.Config.ARGB_4444 -> ARGB_4444_IN_CONFIGS
                Bitmap.Config.ALPHA_8 -> ALPHA_8_IN_CONFIGS
                else -> arrayOf(requested)
            }
        }
    }

    private val keyPool = KeyPool()
    private val groupedMap = GroupedLinkedMap<Key?, Bitmap>()
    private val sortedSizes: MutableMap<Bitmap.Config?, NavigableMap<Int, Int>> = EnumMap(Bitmap.Config::class.java)
    override fun put(bitmap: Bitmap) {
        val size = getBitmapByteSize(bitmap)
        val key = keyPool[size, bitmap.config]
        groupedMap.put(key, bitmap)
        val sizes = getSizesForConfig(bitmap.config)
        val current = sizes[key!!.size]
        sizes[key.size] = if (current == null) 1 else current + 1
    }

    override fun get(width: Int, height: Int, config: Bitmap.Config?): Bitmap? {
        val size = getBitmapByteSize(width, height, config)
        val bestKey = findBestKey(size, config)
        val result = groupedMap[bestKey]
        if (result != null) {
            // Decrement must be called before reconfigure.
            decrementBitmapOfSize(bestKey!!.size, result)
            result.reconfigure(width, height, config)
        }
        return result
    }

    private fun findBestKey(size: Int, config: Bitmap.Config?): Key? {
        var result = keyPool[size, config]
        for (possibleConfig in getInConfigs(config)) {
            val sizesForPossibleConfig = getSizesForConfig(possibleConfig)
            val possibleSize = sizesForPossibleConfig.ceilingKey(size)
            if (possibleSize != null && possibleSize <= size * MAX_SIZE_MULTIPLE) {
                if (possibleSize != size
                    || if (possibleConfig == null) config != null else possibleConfig != config
                ) {
                    keyPool.offer(result)
                    result = keyPool[possibleSize, possibleConfig]
                }
                break
            }
        }
        return result
    }

    override fun removeLast(): Bitmap? {
        val removed = groupedMap.removeLast()
        if (removed != null) {
            val removedSize = getBitmapByteSize(removed)
            decrementBitmapOfSize(removedSize, removed)
        }
        return removed
    }

    private fun decrementBitmapOfSize(size: Int, removed: Bitmap) {
        val config = removed.config
        val sizes = getSizesForConfig(config)
        val current = sizes[size]
            ?: throw NullPointerException(
                "Tried to decrement empty size"
                        + ", size: "
                        + size
                        + ", removed: "
                        + logBitmap(removed)
                        + ", this: "
                        + this
            )
        if (current == 1) {
            sizes.remove(size)
        } else {
            sizes[size] = current - 1
        }
    }

    private fun getSizesForConfig(config: Bitmap.Config?): NavigableMap<Int, Int> {
        var sizes = sortedSizes[config]
        if (sizes == null) {
            sizes = TreeMap()
            sortedSizes[config] = sizes
        }
        return sizes
    }

    override fun logBitmap(bitmap: Bitmap): String {
        val size = getBitmapByteSize(bitmap)
        return getBitmapString(size, bitmap.config)
    }

    override fun logBitmap(width: Int, height: Int, config: Bitmap.Config?): String {
        val size = getBitmapByteSize(width, height, config)
        return getBitmapString(size, config)
    }

    override fun getSize(bitmap: Bitmap): Int {
        return getBitmapByteSize(bitmap)
    }

    override fun toString(): String {
        val sb = StringBuilder()
            .append("SizeConfigStrategy{groupedMap=")
            .append(groupedMap)
            .append(", sortedSizes=(")
        for ((key, value) in sortedSizes) {
            sb.append(key).append('[').append(value).append("], ")
        }
        if (sortedSizes.isNotEmpty()) {
            sb.replace(sb.length - 2, sb.length, "")
        }
        return sb.append(")}").toString()
    }

    @VisibleForTesting
    internal class KeyPool : BaseKeyPool<Key?>() {
        operator fun get(size: Int, config: Bitmap.Config?): Key? {
            val result = get()
            result!!.init(size, config)
            return result
        }

        override fun create(): Key {
            return Key(this)
        }
    }

    @VisibleForTesting
    internal class Key(private val pool: KeyPool) : Poolable {
        var size = 0
        private var config: Bitmap.Config? = null

        @VisibleForTesting
        constructor(pool: KeyPool, size: Int, config: Bitmap.Config?) : this(pool) {
            init(size, config)
        }

        fun init(size: Int, config: Bitmap.Config?) {
            this.size = size
            this.config = config
        }

        override fun offer() {
            pool.offer(this)
        }

        override fun toString(): String {
            return getBitmapString(size, config)
        }

        override fun equals(other: Any?): Boolean {
            if (other is Key) {
                return if (size == other.size && config == null) other.config == null else config == other.config
            }
            return false
        }

        override fun hashCode(): Int {
            var result = size
            result = 31 * result + if (config != null) config.hashCode() else 0
            return result
        }
    }
}