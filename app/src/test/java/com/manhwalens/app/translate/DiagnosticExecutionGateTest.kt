package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosticExecutionGateTest {
    @Test
    fun ordinaryRequestsCanHoldSharedAccessConcurrently() {
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        try {
            val tasks = (1..2).map {
                pool.submit {
                    DiagnosticExecutionGate.shared {
                        entered.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                    }
                }
            }
            assertTrue("both shared requests should overlap", entered.await(2, TimeUnit.SECONDS))
            release.countDown()
            tasks.forEach { it.get(2, TimeUnit.SECONDS) }
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun exclusiveDiagnosticWaitsForActiveRequestsAndBlocksItsBodyUntilTheyFinish() {
        val pool = Executors.newFixedThreadPool(2)
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val diagnosticEntered = CountDownLatch(1)
        try {
            val request = pool.submit {
                DiagnosticExecutionGate.shared {
                    requestEntered.countDown()
                    check(releaseRequest.await(3, TimeUnit.SECONDS))
                }
            }
            assertTrue(requestEntered.await(2, TimeUnit.SECONDS))

            val diagnostic = pool.submit {
                DiagnosticExecutionGate.exclusive { diagnosticEntered.countDown() }
            }
            assertTrue("diagnostic must wait for active translation", !diagnosticEntered.await(150, TimeUnit.MILLISECONDS))
            releaseRequest.countDown()
            request.get(2, TimeUnit.SECONDS)
            diagnostic.get(2, TimeUnit.SECONDS)
            assertEquals(0L, diagnosticEntered.count)
        } finally {
            releaseRequest.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun diagnosticOwnerCanCallSharedEngineMethodReentrantly() {
        val result = DiagnosticExecutionGate.exclusive {
            DiagnosticExecutionGate.shared { "completed" }
        }
        assertEquals("completed", result)
    }
}
