package com.nuvio.app.core.memory

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Only for replaceable results, never pending work, saved records or ownership locks. */
internal class BoundedCache<K, V>(val capacity: Int = 128) : AbstractMutableMap<K, V>() {
    private val lock = SynchronizedObject()
    private val data = LinkedHashMap<K, V>()

    init {
        require(capacity > 0)
        registerMemoryCache(this)
    }

    override val size: Int get() = synchronized(lock) { data.size }
    override fun containsKey(key: K): Boolean = synchronized(lock) { data.containsKey(key) }
    override fun get(key: K): V? = synchronized(lock) {
        if (!data.containsKey(key)) return@synchronized null
        val value = data.remove(key)
        @Suppress("UNCHECKED_CAST")
        data[key] = value as V
        value
    }
    override fun put(key: K, value: V): V? = synchronized(lock) {
        val previous = data.remove(key)
        data[key] = value
        while (data.size > capacity) data.remove(data.keys.first())
        previous
    }
    override fun remove(key: K): V? = synchronized(lock) { data.remove(key) }
    override fun clear() = synchronized(lock) { data.clear() }

    // Iteration is a snapshot; removing through a view still removes the live entry.
    // No caller holds our lock while it walks a collection or does work with a result.
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = object : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
            override val size: Int get() = this@BoundedCache.size
            override fun add(element: MutableMap.MutableEntry<K, V>): Boolean =
                throw UnsupportedOperationException()
            override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
                val snapshot = synchronized(lock) { data.toMap() }.entries.iterator()
                var current: K? = null
                var canRemove = false
                return object : MutableIterator<MutableMap.MutableEntry<K, V>> {
                    override fun hasNext() = snapshot.hasNext()
                    override fun next(): MutableMap.MutableEntry<K, V> {
                        val entry = snapshot.next()
                        current = entry.key
                        canRemove = true
                        return object : MutableMap.MutableEntry<K, V> {
                            override val key = entry.key
                            override var value = entry.value
                            override fun equals(other: Any?): Boolean =
                                other is Map.Entry<*, *> && other.key == key && other.value == value
                            override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)
                            override fun setValue(newValue: V): V {
                                val old = value
                                put(key, newValue)
                                value = newValue
                                return old
                            }
                        }
                    }
                    override fun remove() {
                        check(canRemove)
                        @Suppress("UNCHECKED_CAST")
                        this@BoundedCache.remove(current as K)
                        canRemove = false
                    }
                }
            }
        }
}

internal expect fun registerMemoryCache(cache: BoundedCache<*, *>)
