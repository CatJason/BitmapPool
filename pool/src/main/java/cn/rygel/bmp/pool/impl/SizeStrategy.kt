package cn.rygel.bmp.pool.impl

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import cn.rygel.bmp.utils.getBitmapByteSize
import java.util.*

/**
 * A strategy for reusing bitmaps that relies on [Bitmap.reconfigure].
 *
 *
 * Requires [KitKat][Build.VERSION_CODES.KITKAT] or higher.
 */
@RequiresApi(Build.VERSION_CODES.KITKAT)
internal class SizeStrategy : LruPoolStrategy {
    private val keyPool = KeyPool()
    private val groupedMap = GroupedLinkedMap<Key, Bitmap>()
    private val sortedSizes: NavigableMap<Int?, Int> = PrettyPrintTreeMap()
    override fun put(bitmap: Bitmap) {
        val size = getBitmapByteSize(bitmap)
        val key = keyPool[size]
        groupedMap.put(key, bitmap)
        val current = sortedSizes[key.size]
        sortedSizes[key.size] = if (current == null) 1 else current + 1
    }

    override fun get(width: Int, height: Int, config: Bitmap.Config?): Bitmap? {
        val size = getBitmapByteSize(width, height, config)
        var key = keyPool[size]
        val possibleSize = sortedSizes.ceilingKey(size)
        if (possibleSize != null && possibleSize != size && possibleSize <= size * MAX_SIZE_MULTIPLE) {
            keyPool.offer(key)
            key = keyPool[possibleSize]
        }

        // Do a get even if we know we don't have a bitmap so that the key moves to the front in the
        // lru pool
        val result = groupedMap[key]
        if (result != null) {
            result.reconfigure(width, height, config)
            decrementBitmapOfSize(possibleSize)
        }
        return result
    }

    override fun removeLast(): Bitmap? {
        val removed = groupedMap.removeLast()
        if (removed != null) {
            val removedSize = getBitmapByteSize(removed)
            decrementBitmapOfSize(removedSize)
        }
        return removed
    }

    private fun decrementBitmapOfSize(size: Int?) {
        val current = sortedSizes[size]
        if (current == 1) {
            sortedSizes.remove(size)
        } else {
            sortedSizes[size] = current!! - 1
        }
    }

    override fun logBitmap(bitmap: Bitmap): String {
        return getBitmapString(bitmap)
    }

    override fun logBitmap(width: Int, height: Int, config: Bitmap.Config?): String {
        val size = getBitmapByteSize(width, height, config)
        return getBitmapString(size)
    }

    override fun getSize(bitmap: Bitmap): Int {
        return getBitmapByteSize(bitmap)
    }

    override fun toString(): String {
        return "SizeStrategy:\n  $groupedMap\n  SortedSizes$sortedSizes"
    }

    // Non-final for mocking.
    @VisibleForTesting
    internal class KeyPool : BaseKeyPool<Key?>() {
        operator fun get(size: Int): Key {
            val result = super.get()!!
            result.init(size)
            return result
        }

        override fun create(): Key {
            return Key(this)
        }
    }

    @VisibleForTesting
    internal class Key(private val pool: KeyPool) : Poolable {
        var size = 0
        fun init(size: Int) {
            this.size = size
        }

        override fun equals(o: Any?): Boolean {
            if (o is Key) {
                return size == o.size
            }
            return false
        }

        override fun hashCode(): Int {
            return size
        }

        // PMD.AccessorMethodGeneration: https://github.com/pmd/pmd/issues/807
        override fun toString(): String {
            return getBitmapString(size)
        }

        override fun offer() {
            pool.offer(this)
        }
    }

    companion object {
        private const val MAX_SIZE_MULTIPLE = 8
        private fun getBitmapString(bitmap: Bitmap): String {
            val size = getBitmapByteSize(bitmap)
            return getBitmapString(size)
        }

        fun getBitmapString(size: Int): String {
            return "[$size]"
        }
    }
}