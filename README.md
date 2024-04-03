# Netx <img src="https://avatars.githubusercontent.com/u/149151221?s=200&v=4" height = 100 align = left>

> Saga framework / Supports redis-stream and reactive

<br>

![version 0.4.0](https://img.shields.io/badge/version-0.4.0-black?labelColor=black&style=flat-square) ![jdk 17](https://img.shields.io/badge/minimum_jdk-17-orange?labelColor=black&style=flat-square) ![load-test](https://img.shields.io/badge/load%20test%2010%2C000%2C000-success-brightgreen?labelColor=black&style=flat-square)    
![redis--stream](https://img.shields.io/badge/-redis--stream-da2020?style=flat-square&logo=Redis&logoColor=white)

> **TPS(6,000) on my default Macbook air m2.** _[link](#tps-test)_ 

Netx is a Saga framework that supports Redis-Stream.   
`Netx` provides the following features:
1. Supports synchronous API and asynchronous [Reactor](https://projectreactor.io/) API.
2. Supports both Orchestration and Choreograph.
3. Automatically reruns loss events.
4. Automatically applies **`Transactional messaging pattern`**
5. Supports backpressure to control the number of events that can be processed per node.
6. Prevents multiple nodes in the same group from receiving duplicate events.
7. Ensures message delivery using the `At Least Once` approach.

You can see the test results [here](#test).

## Download

```groovy
dependencies {
    implementation "org.rooftopmsa:netx:${version}"
}
```

## How to use

Netx can be used in Spring environments, and it can be easily configured by adding the `@EnableSaga` annotation as follows:

```kotlin
@EnableSaga
@SpringBootApplication
class Application {

    companion object {
        @JvmStatic
        fun main(vararg args: String) {
            SpringApplication.run(Application::class.java, *args)
        }
    }
}
```

When configured automatically with the `@EnableSaga` annotation, netx uses the following properties to establish connections with event stream services:

#### Properties

| KEY                     | EXAMPLE   | DESCRIPTION                                                                                                                                                                   | DEFAULT |
|-------------------------|-----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------|
| **netx.mode**           | redis     | Specifies the mode implementation used for Saga management.                                                                                                                                             |         |
| **netx.host**           | localhost | The host URL of the event stream used for Saga management. (e.g., redis host)                                                                                                                           |         |
| **netx.password**       | 0000      | The password used to connect to the event stream used for Saga management. If not set, 0000 is mapped as the password.                                                                                                       | 0000    |
| **netx.port**           | 6379      | The port of the message queue used for Saga management.                                                                                                                                                 |         |
| **netx.group**          | pay-group | The group of distributed nodes. Saga events are sent to only one node within the same group.                                                                                                                                |         |
| **netx.node-id**        | 1         | The identifier used for id generation. Each server must be assigned a different id, and ids can be set from 1 to 256. _`Ids are generated using the Twitter Snowflake algorithm to prevent duplicate id generation.`_                                            |         |
| **netx.node-name**      | pay-1     | The name of the server participating in the _`netx.group`_. There should be no duplicate names within the same group.                                                                                                                    |         |
| **netx.recovery-milli** | 1000      | Finds and reruns Sagas not processed for _`netx.recovery-milli`_ milliseconds every _`netx.orphan-milli`_ milliseconds.                                                                                                 | 1000    |
| **netx.orphan-milli**   | 60000     | Finds events in the PENDING state that have not become ACK state even after _`netx.orphan-milli`_ milliseconds and restarts them.                                                                                                          | 60000   |
| **netx.backpressure**   | 40        | Adjusts the number of events that can be received at once. **Setting this too high can cause server load, and setting it too low can reduce performance.** This setting affects the amount of events received from other servers and the amount of events failed to be processed. Unreceived or dropped events are automatically put into the retry queue. | 40      |
| **netx.logging.level**  | info      | Specifies the logging level. Possible values are: "info", "warn", "off"                                                                                                            | "off"   |
| **netx.pool-size**      | 40        | Used to adjust the maximum number of connections when connections need to be continuously established.                                                                                                                                       | 10      |

### Usage example

#### Orchestrator-example.

> [!TIP]   
> When using Orchestrator, `Transactional messaging pattern` is automatically applied.   
> The retry unit for event loss is each operation (one function) of the Orchestrator, and either all chains succeed or rollback is called.

```kotlin
// Use Orchestrator
@Service
class OrderService(private val orderOrchestrator: Orchestrator<Order, OrderResponse>) {

    fun order(orderRequest: Order): OrderResult {
        val result = orderOrchestrator.sagaSync(orderRequest)
        
        result.decodeResultOrThrow(OrderResult::class) // If success get result or else throw exception 
    }
}

// Register Orchestrator
@Configurer
class OrchestratorConfigurer {
    
    private val orchestratorFactory = OrchestratorFactory.instance()

    @Bean
    fun orderOrchestartor(): Orchestrator<Order, OrderResponse> { // <First Request, Last Response>
        return orchestratorFactory.create("orderOrchestrator")
            .start(
                orchestrate = { order -> // its order type
                    // Do your bussiness logic 
                    // something like ... "Check valid seller"
                    return@start user
                },
                rollback = { order ->
                    // do rollback logic
                }
            )
            .joinReactive(
                orchestrate = { user -> // Before operations response type "User" flow here 
                    // Webflux supports, should return Mono type.
                },
                // Can skip rollback operation, if you want
            )
            .joinWithContext(
                contextOrchestrate = { context, request ->
                    context.set("key", request) // save data on context
                    context.decode("foo", Foo::class) // The context set in the upstream chain can be retrieved.
                },
            )
            .commit(
                orchestrate = { request ->
                    // When an error occurs, all rollbacks are called from the bottom up, 
                    // starting from the location where the error occurred.
                    throw IllegalArgumentException("Oops! Something went wrong..")
                },
                rollback = { request ->
                    ...
                }
            )
    }
}
```

#### Events-Example. Handle saga event

When another distributed server (or itself) starts or changes the state of a saga through the sagaManager, the appropriate handler is called based on the state.    
By implementing this handler, you can implement logic for each saga state. If an error is thrown in each handler, rollback is automatically called, and when the handler is terminated, the state set in the annotation successWith is automatically called.

> [!WARNING]   
> Saga handlers must accept only **one** `Saga...Event` that corresponds to the handler.    
> When using Event, `Transactional messaging pattern` must be applied directly.    
> You can easily apply it by moving all business logic into the @Saga...Listener as shown below.

```kotlin

@SagaHandler
class SagaHandler(
    private val sagaManager: SagaManager,
) {
    
    fun start() {
        val foo = Foo("...")
        sagaManager.startSync(foo) // it will call 
    }

    @SagaStartListener(event = Foo::class, successWith = SuccessWith.PUBLISH_JOIN) // Receive saga event when event can be mapped to Foo.class
    fun handleSagaStartEvent(event: SagaStartEvent) {
        val foo: Foo = event.decodeEvent(Foo::class) // Get event field to Foo.class
        // ...
        event.setNextEvent(nextFoo) // When this handler terminates and calls the next event or rollback, the event set here is published together.
    }

    @SagaJoinListener(successWith = SuccessWith.PUBLISH_COMMIT) // Receive all saga event when no type is defined. And, when terminated this function, publish commit state
    fun handleSagaJoinEvent(event: SagaJoinEvent) {
        // ...
    }

    @SagaCommitListener(
        event = Foo::class,
        noRollbackFor = [IllegalArgumentException::class] // Don't rollback when throw IllegalArgumentException. *Rollback if throw Throwable or IllegalArgumentException's super type* 
    )
    fun handleSagaCommitEvent(event: SagaCommitEvent): Mono<String> { // In Webflux framework, publisher must be returned.
        throw IllegalArgumentException("Ignore this exception")
        // ...
    }

    @SagaRollbackListener(Foo::class)
    fun handleSagaRollbackEvent(event: SagaRollbackEvent) { // In Mvc framework, publisher must not returned.
        val undo: Foo = event.decodeUndo(Foo::class) // Get event field to Foo.class
    }
}
```

#### Events-Example. Start pay saga

```kotlin
// Sync
fun pay(param: Any): Any {
    val sagaId = sagaManager.syncStart(Pay(id = 1L, paid = 1000L)) // start saga

    runCatching {
        // Do your bussiness logic
    }.fold(
        onSuccess = { sagaManager.syncCommit(sagaId) }, // commit saga
        onFailure = {
            sagaManager.syncRollback(
                sagaId,
                it.message
            )
        } // rollback saga
    )
}

// Async
fun pay(param: Any): Mono<Any> {
    return sagaManager.start(
        Pay(
            id = 1L,
            paid = 1000L
        )
    ) // Start distributed saga and publish saga start event
        .flatMap { sagaId ->
            service.pay(param)
                .doOnError { throwable ->
                    sagaManager.rollback(
                        sagaId,
                        throwable.message
                    ) // Publish rollback event to all saga joined node
                }
        }.doOnSuccess { sagaId ->
            sagaManager.commit(sagaId) // Publish commit event to all saga joined node
        }
}
```

#### Events-Example. Join order saga

```kotlin
//Sync
fun order(param: Any): Any {
    val sagaId = sagaManager.syncJoin(
        param.saganId,
        Order(id = 1L, state = PENDING)
    ) // join saga

    runCatching { // This is kotlin try catch, not netx library spec
        // Do your bussiness logic
    }.fold(
        onSuccess = { sagaManager.syncCommit(sagaId) }, // commit saga
        onFailure = {
            sagaManager.syncRollback(
                sagaId,
                it.message
            )
        } // rollback saga
    )
}

// Async
fun order(param: Any): Mono<Any> {
    return sagaManager.join(
        param.sagaId,
        Order(id = 1L, state = PENDING)
    ) // join exists distributed saga and publish saga join event
        .flatMap { sagaId ->
            service.order(param)
                .doOnError { throwable ->
                    sagaManager.rollback(sagaId, throwable.message)
                }
        }.doOnSuccess { sagaId ->
            sagaManager.commit(sagaId)
        }
}
```

#### Events-Example. Check exists saga

```kotlin
// Sync
fun exists(param: Any): Any {
    return sagaManager.syncExists(param.sagaId)
}

// Async
fun exists(param: Any): Mono<Any> {
    return sagaManager.exists(param.sagaId) // Find any saga has ever been started 
}
```

## Test

### TPS test

> **How to test?**    
> For 333,333 tests, the sequence proceeds as follows: saga start -> saga join -> saga commit.   
> For 444,444 tests, the sequence proceeds as follows: saga start -> saga join -> saga commit -> saga rollback.   
> The combined test, consisting of both sequences, took a total of 2 minutes and 10 seconds.

### Order -> payment -> rollback transaction

> **How to test?**   
> Pending order -> Pending payment -> Successful payment -> Successful order -> Inventory deduction failure -> Order failure -> Payment failure

<img src = "https://github.com/rooftop-MSA/Netx/assets/62425964/08ed9050-1923-42b5-803f-5b7ea37a263f"/>

### Jvm 

> **How to test?**   
> Continuously sent requests at a certain rate per second for one hour without interruption.    
> During this time, I monitored the status using Redis-stat and VisualVM.
