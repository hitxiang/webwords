package com.typesafe.webwords.indexer

import org.scalatest.matchers._
import org.scalatest._
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import akka.dispatch.Await
import com.typesafe.webwords.common._

class WorkerActorSpec extends FlatSpec with ShouldMatchers with BeforeAndAfterAll {

    implicit val system = ActorSystem("WorkerActorSpec")

    var httpServer: TestHttpServer = null

    override def beforeAll = {
        httpServer = new TestHttpServer(Some(this.getClass))
        httpServer.start()
    }

    override def afterAll = {
        system.shutdown()
        httpServer.stop()
        httpServer = null
    }

    behavior of "WorkerActor"

    implicit val timeout = Timeout(system.settings.config.getMilliseconds("akka.timeout.default"))

    it should "get an index" in {
        val testdb = Some("mongodb://localhost/webwordsworkertest")
        val testIndexerPath=ActorPath.fromString("akka://WorkerActorSpec@127.0.0.1:14711/user/index-worker")
        val config = WebWordsConfig(testIndexerPath, testdb, None)
        val url = httpServer.resolve("/resource/ToSpider.html")
        val worker = system.actorOf(Props(new WorkerActor(config)), "index-worker")
        Thread.sleep(500) // help ensure worker's amqp exchange is set up
        val client = system.actorOf(Props(new ClientActor(config)))
        val indexFuture = (client ? GetIndex(url.toExternalForm, skipCache = false)) map {
            case GotIndex(url, Some(index), cacheHit) =>
                index
            case whatever =>
                throw new Exception("Got bad result from worker: " + whatever)
        }
        val index = Await.result(indexFuture, timeout.duration)

        index.wordCounts.size should be(50)
        val nowheres = (index.links filter { link => link._2.endsWith("/nowhere") } map { _._1 }).sorted
        nowheres should be(Seq("a", "d", "e", "f", "g", "h", "j", "k", "m", "o"))
        
        system.stop(client)
        system.stop(worker)
    }
}
