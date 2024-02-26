package org.rooftop.netx.engine

import org.rooftop.netx.idl.Transaction
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

abstract class AbstractTransactionRetrySupporter(
    private val backpressureSize: Int,
    private val recoveryMilli: Long,
) {

    fun handleLostTransactions() {
        Flux.interval(recoveryMilli.milliseconds.toJavaDuration())
            .flatMap {
                handleOrphanTransaction(backpressureSize)
                    .onErrorResume { Mono.empty() }
            }
            .subscribeOn(Schedulers.boundedElastic())
            .restartWhenTerminated()
            .subscribe()
    }

    protected abstract fun handleOrphanTransaction(backpressureSize: Int): Flux<Pair<Transaction, String>>

    private fun <T> Flux<T>.restartWhenTerminated(): Flux<T> {
        return this.doOnTerminate {
            handleLostTransactions()
        }
    }
}
