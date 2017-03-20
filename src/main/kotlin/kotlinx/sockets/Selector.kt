package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import java.io.*
import java.nio.channels.*

class SelectorManager(val dispatcher: CoroutineDispatcher = ioPool.asCoroutineDispatcher()) : AutoCloseable, Closeable {
    @Volatile
    private var closed = false
    private val selector = lazy { if (closed) throw ClosedSelectorException(); Selector.open() }
    private val q = ArrayChannel<AsyncSelectable>(1000)

    private val selectorJob = launch(dispatcher, false) {
        selectorLoop(selector.value)
    }

    fun socket(): AsyncSocket {
        ensureStarted()
        return AsyncSocketImpl(selector.value.provider().openSocketChannel().apply {
            configureBlocking(false)
        }, this)
    }

    fun serverSocket(): AsyncServerSocket {
        ensureStarted()
        return AsyncServerSocketImpl(selector.value.provider().openServerSocketChannel().apply {
            configureBlocking(false)
        }, this)
    }

    override fun close() {
        closed = true
        if (selector.isInitialized()) selector.value.close()
    }

    internal suspend fun registerSafe(selectable: AsyncSelectable) {
        q.send(selectable)
        selector.value.wakeup()
    }

    private tailrec fun selectorLoop(selector: Selector) {
        selector.select()

        while (true) {
            val selectable = q.poll() ?: break
            handleRegister(selectable)
        }

        val keys = selector.selectedKeys().iterator()
        while (keys.hasNext()) {
            val key = keys.next()
            keys.remove()

            handleKey(key)
        }

        selectorLoop(selector)
    }

    private fun handleKey(key: SelectionKey) {
        try {
            key.interestOps(0)

            launch(dispatcher) {
                handleSelectedKey(key, null)
            }
        } catch (t: Throwable) { // key cancelled or rejected execution
            launch(dispatcher) {
                handleSelectedKey(key, t)
            }
        }
    }

    private fun ensureStarted() {
        if (closed) throw ClosedSelectorException()
        selectorJob.start()
    }

    private suspend fun handleSelectedKey(key: SelectionKey, t: Throwable?) {
        (key.attachment() as? AsyncSelectable)?.apply {
            if (t != null) {
                onSelectionFailed(t)
            } else {
                try {
                    onSelected(key)
                } catch (t: CancelledKeyException) {
                    key.attach(null)
                    onSelectionFailed(t)
                }
            }
        }
    }

    private fun handleRegister(selectable: AsyncSelectable) {
        try {
            registerUnsafe(selectable)
        } catch (c: ClosedChannelException) {
        } catch (c: CancelledKeyException) {
        }
    }

    private fun registerUnsafe(selectable: AsyncSelectable) {
        val requiredOps = selectable.interestedOps

        selectable.channel.keyFor(selector.value)?.also { key -> if (key.interestOps() != requiredOps) key.interestOps(selectable.interestedOps) }
                ?: selectable.channel.register(selector.value, requiredOps).also { key -> key.attach(selectable) }
    }
}
