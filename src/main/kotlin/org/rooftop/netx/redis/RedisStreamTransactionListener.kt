package org.rooftop.netx.redis

import io.lettuce.core.RedisBusyException
import org.rooftop.netx.engine.AbstractTransactionDispatcher
import org.rooftop.netx.engine.AbstractTransactionListener
import org.rooftop.netx.idl.Transaction
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.stream.StreamReceiver
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

class RedisStreamTransactionListener(
    transactionDispatcher: AbstractTransactionDispatcher,
    connectionFactory: ReactiveRedisConnectionFactory,
    private val nodeGroup: String,
    private val nodeName: String,
    private val reactiveRedisTemplate: ReactiveRedisTemplate<String, ByteArray>,
) : AbstractTransactionListener(transactionDispatcher) {

    private val options = StreamReceiver.StreamReceiverOptions.builder()
        .pollTimeout(1.hours.toJavaDuration())
        .build()

    private val receiver = StreamReceiver.create(connectionFactory, options)

    override fun receive(): Flux<Pair<Transaction, String>> {
        return createGroupIfNotExists()
            .flatMap {
                receiver.receive(
                    Consumer.from(nodeGroup, nodeName),
                    StreamOffset.create(STREAM_KEY, ReadOffset.from(">"))
                ).publishOn(Schedulers.parallel())
                    .map { Transaction.parseFrom(it.value["data"]?.toByteArray()) to it.id.value }
            }
    }

    private fun createGroupIfNotExists(): Flux<String> {
        return reactiveRedisTemplate.opsForStream<String, ByteArray>()
            .createGroup(STREAM_KEY, ReadOffset.from("0"), nodeGroup)
            .onErrorResume {
                if (it.cause is RedisBusyException) {
                    return@onErrorResume Mono.just("OK")
                }
                throw it
            }
            .flatMapMany { Flux.just(it) }
    }

    private companion object {
        private const val STREAM_KEY = "NETX_STREAM"
    }
}