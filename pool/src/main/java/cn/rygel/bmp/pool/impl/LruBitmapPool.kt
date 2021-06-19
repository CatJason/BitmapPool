package cn.rygel.bmp.pool.impl

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import cn.rygel.bmp.pool.BitmapPool
import java.util.*
import kotlin.math.roundToLong

class LruBitmapPool internal constructor(
    private var initialMaxSize: Long,
    private val strategy: LruPoolStrategy,
    private val allowedConfigs: Set<Bitmap.Config?>
) : BitmapPool {
    private val tracker: BitmapTracker
    override var maxSize: Long = initialMaxSize

    /**
     * Returns the current size of the pool in bytes.
     */
    var currentSize: Long = 0
        private set
    private var hits = 0
    private var misses = 0
    private var puts = 0
    private var evictions = 0

    /**
     * Constructor for LruBitmapPool.
     *
     * @param maxSize The initial maximum size of the pool in bytes.
     */
    constructor(maxSize: Long) : this(maxSize, defaultStrategy, defaultAllowedConfigs) {}

    /**
     * Constructor for LruBitmapPool.
     *
     * @param maxSize        The initial maximum size of the pool in bytes.
     * @param allowedConfigs A white listed put of [Bitmap.Config] that are
     * allowed to be put into the pool. Configs not in the allowed put will be rejected.
     */
    // Public API.
    constructor(maxSize: Long, allowedConfigs: Set<Bitmap.Config?>) : this(maxSize, defaultStrategy, allowedConfigs) {}

    /**
     * Returns the number of cache hits for bitmaps in the pool.
     */
    fun hitCount(): Long {
        return hits.toLong()
    }

    /**
     * Returns the number of cache misses for bitmaps in the pool.
     */
    fun missCount(): Long {
        return misses.toLong()
    }

    /**
     * Returns the number of bitmaps that have been evicted from the pool.
     */
    fun evictionCount(): Long {
        return evictions.toLong()
    }

    @Synchronized
    override fun setSizeMultiplier(sizeMultiplier: Float) {
        maxSize = (initialMaxSize * sizeMultiplier).roundToLong()
        evict()
    }

    @Synchronized
    override fun put(bitmap: Bitmap?) {
        if (bitmap == null) {
            throw NullPointerException("Bitmap must not be null")
        }
        check(!bitmap.isRecycled) { "Cannot pool recycled bitmap" }
        if (!bitmap.isMutable
            || strategy.getSize(bitmap) > maxSize || !allowedConfigs.contains(bitmap.config)
        ) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(
                    TAG,
                    "Reject bitmap from pool"
                            + ", bitmap: "
                            + strategy.logBitmap(bitmap)
                            + ", is mutable: "
                            + bitmap.isMutable
                            + ", is allowed config: "
                            + allowedConfigs.contains(bitmap.config)
                )
            }
            bitmap.recycle()
            return
        }
        val size = strategy.getSize(bitmap)
        strategy.put(bitmap)
        tracker.add(bitmap)
        puts++
        currentSize += size.toLong()
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Put bitmap in pool=" + strategy.logBitmap(bitmap))
        }
        dump()
        evict()
    }

    private fun evict() {
        trimToSize(maxSize)
    }

    override fun get(width: Int, height: Int, config: Bitmap.Config?): Bitmap {
        var result = getDirtyOrNull(width, height, config)
        if (result != null) {
            // Bitmaps in the pool contain random data that in some cases must be cleared for an image
            // to be rendered correctly. we shouldn't force all consumers to independently erase the
            // contents individually, so we do so here. See issue #131.
            result.eraseColor(Color.TRANSPARENT)
        } else {
            result = createBitmap(width, height, config)
        }
        return result
    }

    override fun getDirty(width: Int, height: Int, config: Bitmap.Config?): Bitmap {
        var result = getDirtyOrNull(width, height, config)
        if (result == null) {
            result = createBitmap(width, height, config)
        }
        return result
    }

    @Synchronized
    private fun getDirtyOrNull(
        width: Int, height: Int, config: Bitmap.Config?
    ): Bitmap? {
        assertNotHardwareConfig(config)
        // Config will be null for non public config types, which can lead to transformations naively
        // passing in null as the requested config here. See issue #194.
        val result = strategy[width, height, config ?: DEFAULT_CONFIG]
        if (result == null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Missing bitmap=" + strategy.logBitmap(width, height, config))
            }
            misses++
        } else {
            hits++
            currentSize -= strategy.getSize(result).toLong()
            tracker.remove(result)
            normalize(result)
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Get bitmap=" + strategy.logBitmap(width, height, config))
        }
        dump()
        return result
    }

    override fun clearMemory() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "clearMemory")
        }
        trimToSize(0)
    }

    @SuppressLint("InlinedApi")
    override fun trimMemory(level: Int) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "trimMemory, level=$level")
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
            || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        ) {
            clearMemory()
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
            || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            trimToSize(maxSize / 2)
        }
    }

    @Synchronized
    private fun trimToSize(size: Long) {
        while (currentSize > size) {
            val removed = strategy.removeLast()
            // TODO: This shouldn't ever happen, see #331.
            if (removed == null) {
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "Size mismatch, resetting")
                    dumpUnchecked()
                }
                currentSize = 0
                return
            }
            tracker.remove(removed)
            currentSize -= strategy.getSize(removed).toLong()
            evictions++
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Evicting bitmap=" + strategy.logBitmap(removed))
            }
            dump()
            removed.recycle()
        }
    }

    private fun dump() {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            dumpUnchecked()
        }
    }

    private fun dumpUnchecked() {
        Log.v(
            TAG,
            """
                Hits=$hits, misses=$misses, puts=$puts, evictions=$evictions, currentSize=$currentSize, maxSize=$maxSize
                Strategy=$strategy
                """.trimIndent()
        )
    }

    private interface BitmapTracker {
        fun add(bitmap: Bitmap)
        fun remove(bitmap: Bitmap)
    }

    // Only used for debugging
    private class ThrowingBitmapTracker : BitmapTracker {
        private val bitmaps = Collections.synchronizedSet(HashSet<Bitmap>())
        override fun add(bitmap: Bitmap) {
            check(!bitmaps.contains(bitmap)) {
                ("Can't add already added bitmap: "
                        + bitmap
                        + " ["
                        + bitmap.width
                        + "x"
                        + bitmap.height
                        + "]")
            }
            bitmaps.add(bitmap)
        }

        override fun remove(bitmap: Bitmap) {
            check(bitmaps.contains(bitmap)) { "Cannot remove bitmap not in tracker" }
            bitmaps.remove(bitmap)
        }
    }

    private class NullBitmapTracker internal constructor() : BitmapTracker {
        override fun add(bitmap: Bitmap) {
            // Do nothing.
        }

        override fun remove(bitmap: Bitmap) {
            // Do nothing.
        }
    }

    companion object {
        private const val TAG = "LruBitmapPool"
        private val DEFAULT_CONFIG = Bitmap.Config.ARGB_8888
        private fun createBitmap(width: Int, height: Int, config: Bitmap.Config?): Bitmap {
            return Bitmap.createBitmap(width, height, config ?: DEFAULT_CONFIG)
        }

        @TargetApi(Build.VERSION_CODES.O)
        private fun assertNotHardwareConfig(config: Bitmap.Config?) {
            // Avoid short circuiting on sdk int since it breaks on some versions of Android.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }
            require(config != Bitmap.Config.HARDWARE) {
                ("Cannot create a mutable Bitmap with config: "
                        + config
                        + ". Consider setting Downsampler#ALLOW_HARDWARE_CONFIG to false in your"
                        + " RequestOptions and/or in GlideBuilder.setDefaultRequestOptions")
            }
        }

        // Setting these two values provides Bitmaps that are essentially equivalent to those returned
        // from Bitmap.createBitmap.
        private fun normalize(bitmap: Bitmap) {
            bitmap.setHasAlpha(true)
            maybeSetPreMultiplied(bitmap)
        }

        @TargetApi(Build.VERSION_CODES.KITKAT)
        private fun maybeSetPreMultiplied(bitmap: Bitmap) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                bitmap.isPremultiplied = true
            }
        }

        private val defaultStrategy: LruPoolStrategy
            get() {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    SizeConfigStrategy()
                } else {
                    AttributeStrategy()
                }
            }

        // GIFs, among other types, end up with a native Bitmap config that doesn't map to a java
        // config and is treated as null in java code. On KitKat+ these Bitmaps can be reconfigured
        // and are suitable for re-use.
        @get:TargetApi(Build.VERSION_CODES.O)
        private val defaultAllowedConfigs: Set<Bitmap.Config?>
            get() {
                val configs: MutableSet<Bitmap.Config?> = HashSet(Arrays.asList(*Bitmap.Config.values()))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    // GIFs, among other types, end up with a native Bitmap config that doesn't map to a java
                    // config and is treated as null in java code. On KitKat+ these Bitmaps can be reconfigured
                    // and are suitable for re-use.
                    configs.add(null)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    configs.remove(Bitmap.Config.HARDWARE)
                }
                return Collections.unmodifiableSet(configs)
            }
    }

    // Exposed for testing only.
    init {
        tracker = NullBitmapTracker()
    }
}