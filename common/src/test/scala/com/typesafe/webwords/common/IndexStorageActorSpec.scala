package com.typesafe.webwords.common

import org.scalatest.matchers._
import org.scalatest._
import akka.actor._
import java.net.URL
import akka.util.Timeout
import akka.testkit.{ImplicitSender, TestKit}

class IndexStorageActorSpec extends TestKit(ActorSystem("IndexActorSpec")) with FlatSpec with ShouldMatchers
    with BeforeAndAfterAll with ImplicitSender {

    override def afterAll = { system.shutdown() }

    implicit val timeout = Timeout(system.settings.config.getMilliseconds("akka.timeout.test"))

    private val sampleIndex = Index(
        links = Seq(
            "dogs" -> "http://dogs.com/",
            "cats" -> "http://cats.com/"),
        wordCounts = Seq(
            "hello" -> 10,
            "world" -> 5,
            "quick" -> 4,
            "brown" -> 3))
    private val anotherIndex = Index(
        links = Seq(
            "pigs" -> "http://pigs.com/",
            "cows" -> "http://cows.com/"),
        wordCounts = Seq(
            "hello" -> 7,
            "world" -> 1,
            "quick" -> 4,
            "brown" -> 2))
    private val emptyIndex = Index(Nil, Nil)
    private val exampleUrl = new URL("http://example.com/")
    private val exampleUrl2 = new URL("http://example2.com/")

    private def newActor = system.actorOf(Props(new IndexStorageActor(Some("mongodb://127.0.0.1/webwordstest"))))

    behavior of "IndexStorageActor"

    private def cacheIndex(storage: ActorRef, url: String, index: Index) = {
        storage ! CacheIndex(url, index)
        expectMsg(IndexCached(url))
    }

    private def fetchIndex(storage: ActorRef, url: String, index: Index) = {
        storage ! FetchCachedIndex(url)
        expectMsg(CachedIndexFetched(Some(index)))
    }

    private def cacheSize(storage: ActorRef, size: Long) = {
        storage ! GetCacheSize
        expectMsg(CacheSize(size))
    }

    it should "drop the cache in case of leftovers" in {
        val storage = newActor
        within(timeout.duration) {
            storage ! DropCache
            cacheSize(storage, 0)
        }
        system.stop(storage)
    }

    it should "store and retrieve an index" in {
        val storage = newActor
        val url = exampleUrl.toExternalForm
        within(timeout.duration) {
            cacheIndex(storage, url, sampleIndex)
            fetchIndex(storage, url, sampleIndex)
            cacheSize(storage, 1)
        }
        system.stop(storage)
    }

    it should "store and retrieve an empty index" in {
        val storage = newActor
        val url = exampleUrl2.toExternalForm
        within(timeout.duration) {
            cacheIndex(storage, url, emptyIndex)
            fetchIndex(storage, url, emptyIndex)
            cacheSize(storage, 2)
        }
        system.stop(storage)
    }

    it should "use the newest entry" in {
        val storage = newActor
        val url = exampleUrl.toExternalForm
        within(timeout.duration) {
            // check we have leftovers from previous test
            fetchIndex(storage, url, sampleIndex)
            // now replace the leftovers
            cacheIndex(storage, url, anotherIndex)
            fetchIndex(storage, url, anotherIndex)
        }
        system.stop(storage)
    }

    it should "drop the cache" in {
        val storage = newActor
        val url = exampleUrl.toExternalForm
        within(timeout.duration) {
            // check we have leftovers from a previous test
            fetchIndex(storage, url, anotherIndex)
            // drop the cache
            storage ! DropCache
            cacheSize(storage, 0)
        }
        system.stop(storage)
    }
}
