/*
 * Copyright 2021 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.graphql.dgs.client

import graphql.GraphQLException
import io.mockk.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.test.StepVerifier

/*
 * Copyright 2021 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlin.math.sin

class WebsocketGraphQLClientTest {
    companion object {
        private val VERIFY_TIMEOUT = Duration.ofSeconds(10)
        private val CONNECTION_ACK_MESSAGE = OperationMessage(GQL_CONNECTION_ACK, null, null)
        private val TEST_DATA_A = mapOf(Pair("a", 1), Pair("b", "hello"), Pair("c", false))
        private val TEST_DATA_B = mapOf(Pair("a", 2), Pair("b", null), Pair("c", true))
    }

    lateinit var subscriptionsClient: SubscriptionsTransportWsClient
    lateinit var client: WebsocketGraphQLClient

    @BeforeEach
    fun setup() {
        subscriptionsClient = mockk(relaxed = true)
        client = WebsocketGraphQLClient(subscriptionsClient)
    }

    @Test
    fun timesOutIfNoAckFromServer() {
        every { subscriptionsClient.receive() } returns Flux.never()

        val client = WebsocketGraphQLClient(subscriptionsClient, Duration.ofSeconds(1))
        val responses = client.reactiveExecuteQuery("", emptyMap())
        StepVerifier.create(responses)
            .expectError(TimeoutException::class.java)
            .verify(VERIFY_TIMEOUT)
    }

    @Test
    fun errorsIfMessageOtherThanAckFromServer() {
        every { subscriptionsClient.receive() } returns dataMessages(listOf(TEST_DATA_A), "1")
            .mergeWith(Flux.never())

        val responses = client.reactiveExecuteQuery("", emptyMap())
        StepVerifier.create(responses)
            .expectError(GraphQLException::class.java)
            .verify(VERIFY_TIMEOUT)
    }

    @Test
    fun sendsInitMessage() {
        every { subscriptionsClient.receive() } returns Flux.empty()

        client.reactiveExecuteQuery("", emptyMap()).blockLast()
        verify { subscriptionsClient.send(OperationMessage(GQL_CONNECTION_INIT, null, null)) }
    }

    @Test
    fun sendsQuery() {
        every { subscriptionsClient.receive() } returns Flux.empty()

        client.reactiveExecuteQuery("{ helloWorld }", emptyMap()).blockLast()
        verify { subscriptionsClient.send(OperationMessage(GQL_START, QueryPayload(emptyMap(), emptyMap(), null, "{ helloWorld }"), "1")) }
    }

    @Test
    fun parsesData() {
        every { subscriptionsClient.receive() } returns Flux
            .just(CONNECTION_ACK_MESSAGE)
            .mergeWith(dataMessages(listOf(TEST_DATA_A), "1"))
            .mergeWith(Flux.never())

        val responses = client.reactiveExecuteQuery("", emptyMap())
        StepVerifier.create(responses.take(1))
            .expectSubscription()
            .expectNextMatches {
                it.extractValue<Int>("a") == 1
                        && it.extractValue<String>("b") == "hello"
                        && !it.extractValue<Boolean>("c")
            }
            .expectComplete()
            .verify(VERIFY_TIMEOUT)
    }

    @Test
    fun parsesMultipleData() {
        every { subscriptionsClient.receive() } returns Flux
            .just(CONNECTION_ACK_MESSAGE)
            .mergeWith(dataMessages(listOf(TEST_DATA_A, TEST_DATA_B), "1"))
            .mergeWith(Flux.never())

        val responses = client.reactiveExecuteQuery("", emptyMap())
        StepVerifier.create(responses.take(2))
            .expectSubscription()
            .expectNextMatches {
                it.extractValue<Int>("a") == 1
                        && it.extractValue<String?>("b") == "hello"
                        && !it.extractValue<Boolean>("c")
            }
            .expectNextMatches {
                it.extractValue<Int>("a") == 2
                        && it.extractValue<String?>("b") == null
                        && it.extractValue("c")
            }
            .expectComplete()
            .verify(VERIFY_TIMEOUT)
    }

    @Test
    fun completesOnCompleteMessage() {
        every { subscriptionsClient.receive() } returns Flux
            .just(CONNECTION_ACK_MESSAGE)
            .mergeWith(dataMessages(listOf(TEST_DATA_A), "1"))
            .mergeWith(Flux.just(OperationMessage(GQL_COMPLETE, null, "1")))
            .mergeWith(Flux.never())

        val responses = client.reactiveExecuteQuery("", emptyMap())
        StepVerifier.create(responses)
            .expectSubscription()
            .expectNextMatches { it.extractValue<Int>("a") == 1 }
            .expectComplete()
            .verify(VERIFY_TIMEOUT)
    }

    @Test
    fun finishesOnGraphQLError() {
        every { subscriptionsClient.receive() } returns Flux
            .just(CONNECTION_ACK_MESSAGE, OperationMessage(GQL_ERROR, "An error occurred", "1"))
            .mergeWith(Flux.never())

        val responses = client.reactiveExecuteQuery("", emptyMap())
        StepVerifier.create(responses)
            .expectSubscription()
            .expectError(GraphQLException::class.java)
            .verify(VERIFY_TIMEOUT)
    }

    @Test
    fun sendsStopMessageIfCancelled() {
        // TODO: Should fail because never subscribed!
        every { subscriptionsClient.receive() } returns Flux
            .just(CONNECTION_ACK_MESSAGE)
            .mergeWith(Flux.never())

        val responses = client.reactiveExecuteQuery("", emptyMap())
        StepVerifier.create(responses)
            .expectSubscription()
            .thenAwait()
            .thenCancel()
            .verify(VERIFY_TIMEOUT)

        verifyOrder {
            subscriptionsClient.send(OperationMessage(GQL_CONNECTION_INIT, null, null))
            subscriptionsClient.send(OperationMessage(GQL_START, QueryPayload(emptyMap(), emptyMap(), null, ""), "1"))
            subscriptionsClient.send(OperationMessage(GQL_STOP, null, "1"))
        }
    }

    @Test
    fun handlesMultipleSubscriptions() {
        every { subscriptionsClient.receive() } returns Flux
            .just(CONNECTION_ACK_MESSAGE)
            .mergeWith(dataMessages(listOf(TEST_DATA_A), "1"))
            .mergeWith(Flux.just(OperationMessage(GQL_COMPLETE, null, "1")))
            .mergeWith(dataMessages(listOf(TEST_DATA_B), "2"))
            .mergeWith(Flux.just(OperationMessage(GQL_COMPLETE, null, "2")))
            .mergeWith(Flux.never())


        val responses1 = client.reactiveExecuteQuery("", emptyMap())
        val responses2 = client.reactiveExecuteQuery("", emptyMap())

        StepVerifier.create(responses1.map { it.extractValue<Int>("a") })
            .expectSubscription()
            .expectNext(1)
            .expectComplete()
            .verify(VERIFY_TIMEOUT)

        StepVerifier.create(responses2.map { it.extractValue<Int>("a") })
            .expectSubscription()
            .expectNext(2)
            .expectComplete()
            .verify(VERIFY_TIMEOUT)
    }

    fun dataMessages(data: List<Map<String, Any?>>, id: String): Flux<OperationMessage> {
        return Flux
            .fromIterable(data)
            .map { OperationMessage(GQL_DATA, DataPayload(it, null), id) }
    }
}