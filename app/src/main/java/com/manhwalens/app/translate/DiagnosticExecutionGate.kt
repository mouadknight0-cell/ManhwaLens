package com.manhwalens.app.translate

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Allows ordinary translation requests to run concurrently while reserving the shared
 * engine exclusively for diagnostics. The fair lock prevents a stream of reader requests
 * from starving a queued benchmark. A diagnostic owner may call [shared] reentrantly.
 */
internal object DiagnosticExecutionGate {
    private val lock = ReentrantReadWriteLock(true)

    fun <T> shared(block: () -> T): T {
        val read = lock.readLock()
        read.lock()
        try {
            return block()
        } finally {
            read.unlock()
        }
    }

    fun <T> exclusive(block: () -> T): T {
        val write = lock.writeLock()
        write.lock()
        try {
            return block()
        } finally {
            write.unlock()
        }
    }
}
