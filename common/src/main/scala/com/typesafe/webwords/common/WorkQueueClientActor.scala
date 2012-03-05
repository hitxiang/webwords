package com.typesafe.webwords.common

import akka.actor._

/**
 * This actor wraps the work queue on the "client" side (in the web process).
 */
class WorkQueueClientActor(indexerPath: ActorPath)
    extends Actor with ActorLogging{

    val indexer = context.system.actorFor(indexerPath)

    def receive = {
        case request: WorkQueueRequest =>
            indexer forward request
    }
}
