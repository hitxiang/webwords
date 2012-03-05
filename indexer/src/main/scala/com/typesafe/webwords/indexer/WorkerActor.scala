package com.typesafe.webwords.indexer

import akka.actor._
import akka.dispatch._
import akka.pattern.ask
import akka.util.duration._
import com.typesafe.webwords.common._
import java.net.URL
import akka.util.Timeout

/**
 * This actor listens to the work queue, spiders and caches results.
 * It's the "root" actor of the indexer process.
 */
class WorkerActor(config: WebWordsConfig)
    extends WorkQueueWorkerActor with ActorLogging {

    private val spider = context.actorOf(Props[SpiderActor], "spider")
    private val cache = context.actorOf(Props().withCreator({ new IndexStorageActor(config.mongoURL) }), "index-storage")

    implicit val timeout = Timeout(5000 milliseconds) // TODO:ban get from config

    override def handleRequest(request: WorkQueueRequest): Future[WorkQueueReply] = {
        request match {
            case SpiderAndCache(url) =>
                log.debug("SpiderAndCache({})", url)
                // TODO:ban comment this to explain logic
                (spider ? Spider(new URL(url)) map {
                    case Spidered(_, index) => index
                } flatMap {
                    index => cache ? CacheIndex(url, index) map { _ => SpideredAndCached(url) }
                } recover {
                    case _ => SpideredAndCached(url)
                }).mapTo[WorkQueueReply]
        }
    }
}
