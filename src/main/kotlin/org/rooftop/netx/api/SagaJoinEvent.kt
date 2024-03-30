package org.rooftop.netx.api

class SagaJoinEvent internal constructor(
    id: String,
    nodeName: String,
    group: String,
    event: String?,
    codec: Codec,
) : SagaEvent(id, nodeName, group, event, codec) {

    override fun copy(): SagaJoinEvent =
        SagaJoinEvent(id, nodeName, group, event, codec)
}
