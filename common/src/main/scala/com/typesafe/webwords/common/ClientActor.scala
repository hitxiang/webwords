package com.typesafe.webwords.common

import akka.actor._
import akka.dispatch._
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import akka.util.duration._
import scala.Option

sealed trait ClientActorIncoming
case class GetIndex(url: String, skipCache: Boolean) extends ClientActorIncoming

sealed trait ClientActorOutgoing
case class GotIndex(url: String, index: Option[Index], cacheHit: Boolean) extends ClientActorOutgoing

/**
 * This actor encapsulates:
 *  - checking the cache for an index of a certain URL
 *  - asking the indexer worker process to index the URL if it's not cached
 *  - checking the cache again when the worker is done
 * It coordinates a WorkQueueClientActor and IndexStorageActor to accomplish
 * this.
 */
class ClientActor(config: WebWordsConfig) extends Actor with ActorLogging {

    private val client = context.actorOf(Props().withCreator({ new WorkQueueClientActor(config.indexerPath) }), "work-queue-client")
    private val cache = context.actorOf(Props().withCreator({ new IndexStorageActor(config.mongoURL) }), "index-storage")

    override def receive = {
        case incoming: ClientActorIncoming =>
            incoming match {
                case GetIndex(url, skipCache) =>
                    log.debug("GetIndex({}, {})", url, skipCache)
                    // we look in the cache, if that fails, ask spider to
                    // spider and then notify us, and then we look in the
                    // cache again.
                    def getWithoutCache = {
                        import context.dispatcher
                        getFromWorker(client, url) flatMap { _ =>
                            getFromCacheOrElse(cache, url, cacheHit = false) {
                                Promise.successful(GotIndex(url, index = None, cacheHit = false))
                            }
                        }
                    }

                    val futureGotIndex = if (skipCache)
                        getWithoutCache
                    else
                        getFromCacheOrElse(cache, url, cacheHit = true) { getWithoutCache }

                    futureGotIndex pipeTo sender
            }
    }

    implicit val timeout = Timeout(10 seconds)

    private def getFromCacheOrElse(cache: ActorRef, url: String, cacheHit: Boolean)(fallback: => Future[GotIndex]): Future[GotIndex] = {
        import context.dispatcher
        cache ? FetchCachedIndex(url) flatMap {
            case CachedIndexFetched(Some(index)) =>
                Promise.successful(GotIndex(url, Some(index), cacheHit))
            case CachedIndexFetched(None) =>
                fallback
        }
    }

    private def getFromWorker(client: ActorRef, url: String): Future[Unit] = {
        client ? SpiderAndCache(url) map {
            case SpideredAndCached(returnedUrl) =>
                Unit
        }
    }
}
