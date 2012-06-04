package com.typesafe.webwords.indexer

import org.scalatest.matchers._
import org.scalatest._
import akka.actor._
import akka.util.Timeout
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.webwords.common._

class WorkerActorSpec extends TestKit(ActorSystem("WorkerActorSpec")) with FlatSpec with ShouldMatchers
    with BeforeAndAfterAll with ImplicitSender {

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

    implicit val timeout = Timeout(system.settings.config.getMilliseconds("akka.timeout.test"))

    it should "get an index" in {
        val testdb = Some("mongodb://localhost/webwordsworkertest")
        val testIndexerPath=ActorPath.fromString("akka://WorkerActorSpec@127.0.0.1:14711/user/index-worker")
        val config = WebWordsConfig(testIndexerPath, testdb)
        val url = httpServer.resolve("/resource/ToSpider.html")
        val worker = system.actorOf(Props(new WorkerActor(config)), "index-worker")
        val client = system.actorOf(Props(new ClientActor(config)))
        val index = within(timeout.duration) {
            val urlStr = url.toExternalForm
            client ! GetIndex(urlStr, skipCache = true)
            expectMsgType[GotIndex] match {
                case GotIndex(url, optIndex, cacheHit) =>
                    cacheHit should be(false)
                    url should be(urlStr)
                    optIndex.getOrElse(Index(List(), List()))
            }
        }
        index.wordCounts.size should be(50)
        val nowheres = (index.links filter { link => link._2.endsWith("/nowhere") } map { _._1 }).sorted
        nowheres should be(Seq("a", "d", "e", "f", "g", "h", "j", "k", "m", "o"))
        
        system.stop(client)
        system.stop(worker)
    }
}
