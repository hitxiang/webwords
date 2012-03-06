package com.typesafe.webwords.common

import org.scalatest.matchers._
import org.scalatest._
import akka.actor._
import java.net.URL
import akka.util.Timeout
import akka.util.duration._
import akka.pattern.ask
import akka.dispatch.{Await, Future}

class IndexStorageActorSpec extends FlatSpec with ShouldMatchers with BeforeAndAfterAll {
    implicit val system = ActorSystem("IndexerActorSpec")

    implicit val timeout = Timeout(5 seconds)

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

    private def newActor = system.actorOf(Props[IndexStorageActor].withCreator({
        new IndexStorageActor(Some("mongodb://localhost/webwordstest")) }))

    behavior of "IndexStorageActor"

    private def cacheIndex(storage: ActorRef, url: URL, index: Index) = {
        storage ! CacheIndex(url.toExternalForm, index)
    }

    private def fetchIndex(storage: ActorRef, url: URL): Future[Index] = {
        (storage ? FetchCachedIndex(url.toExternalForm)) map {
            case CachedIndexFetched(Some(index)) =>
                index
            case whatever =>
                throw new Exception("failed to get index, got: " + whatever)
        }
    }

    private def cacheSize(storage: ActorRef): Future[Long] = {
        (storage ? GetCacheSize) map {
            case CacheSize(x) => x
            case whatever =>
                throw new Exception("failed to get cache size, got: " + whatever)
        }
    }

    it should "drop the cache in case of leftovers" in {
        val storage = newActor
        storage ! DropCache
        val size = Await.result(cacheSize(storage), timeout.duration)
        size should be(0)
        system.stop(storage)
    }

    it should "store and retrieve an index" in {
        val storage = newActor
        cacheIndex(storage, exampleUrl, sampleIndex)
        val fetched = Await.result(fetchIndex(storage, exampleUrl), timeout.duration)
        fetched should be(sampleIndex)
        system.stop(storage)
    }

    it should "store and retrieve an empty index" in {
        val storage = newActor
        cacheIndex(storage, exampleUrl2, emptyIndex)
        val fetched = Await.result(fetchIndex(storage, exampleUrl2), timeout.duration)
        fetched should be(emptyIndex)
        system.stop(storage)
    }

    it should "use the newest entry" in {
        val storage = newActor
        // check we have leftovers from previous test
        val fetched = Await.result(fetchIndex(storage, exampleUrl), timeout.duration)
        fetched should be(sampleIndex)
        // now replace the leftovers
        cacheIndex(storage, exampleUrl, anotherIndex)
        val newIndex = Await.result(fetchIndex(storage, exampleUrl), timeout.duration)
        newIndex should be(anotherIndex)
        system.stop(storage)
    }

    it should "drop the cache" in {
        val storage = newActor
        // check we have leftovers from a previous test
        val fetched = Await.result(fetchIndex(storage, exampleUrl), timeout.duration)
        fetched should be(anotherIndex)
        storage ! DropCache
        val size = Await.result(cacheSize(storage), timeout.duration)
        size should be(0)
        system.stop(storage)
    }

    override def afterAll = { system.shutdown() }
}
