package com.typesafe.webwords.indexer

import org.scalatest.matchers._
import org.scalatest._
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import akka.util.duration._
import akka.dispatch.Await

//import java.net.URL
import com.typesafe.webwords.common._
import java.net.URI

class SpiderActorSpec extends FlatSpec with ShouldMatchers with BeforeAndAfterAll {

    implicit val system = ActorSystem("SpiderActorSpec")

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

    implicit val timeout = Timeout(10 seconds)

    behavior of "local http server used to test spider"

    it should "fetch our test resource" in {
        val fetcher = system.actorOf(Props[URLFetcher])
        val f = fetcher ? FetchURL(httpServer.resolve("/resource/Functional_programming.html"))
        Await.result(f, timeout.duration) match {
            case URLFetched(status, headers, body) =>
                if (status != 200)
                    println(body)
                status should be(200)
            case _ =>
                throw new Exception("Wrong reply message from fetcher")
        }
        system.stop(fetcher)
    }

    behavior of "utility functions"

    it should "remove a uri fragment" in {
        val uri = new URI("http://example.com:907/foo?hello=world#bar")
        uri.getFragment should be("bar")
        val noFragment = SpiderActor.removeFragment(uri)
        noFragment.getFragment should be(null)

        noFragment.getScheme should be(uri.getScheme)
        noFragment.getHost should be(uri.getHost)
        noFragment.getPort should be(uri.getPort)
        noFragment.getPath should be(uri.getPath)
        noFragment.getQuery should be(uri.getQuery)
        noFragment.getUserInfo should be(uri.getUserInfo)
    }

    it should "know if uris are below other uris" in {
        val example = new URI("http://example.com/")
        val example_a = new URI("http://example.com/a")
        val example_b = new URI("http://example.com/b")
        val example_a_c = new URI("http://example.com/a/c")
        val example_a_d = new URI("http://example.com/a/d")
        val example_b_e = new URI("http://example.com/b/e")
        val elsewhere = new URI("http://typesafe.com/")
        val elsewhere_a = new URI("http://typesafe.com/a")

        val correct = Seq(
            (example, example) -> false,
            (example, example_a) -> true,
            (example_a, example_a_c) -> true,
            (example_a, example_b) -> false,
            (example_a, example_b_e) -> false,
            (example_a_c, example_a_d) -> false,
            (elsewhere, elsewhere_a) -> true,
            (elsewhere, example) -> false,
            (elsewhere, example_a) -> false,
            (elsewhere_a, example_a) -> false,
            (elsewhere_a, example_a_c) -> false)

        for (((parent, possibleChild), result) <- correct) {
            try {
                SpiderActor.isBelow(parent, possibleChild) should be(result)
            } catch {
                case e: Throwable =>
                    println("parent=" + parent)
                    println("possibleChild=" + possibleChild)
                    throw e
            }
        }
    }

    it should "compute uri path depth" in {
        SpiderActor.pathDepth(new URI("/")) should be(1)
        SpiderActor.pathDepth(new URI("/a")) should be(1)
        SpiderActor.pathDepth(new URI("/a/b")) should be(2)
        SpiderActor.pathDepth(new URI("/a/b/c")) should be(3)
    }

    it should "combine sorted word count lists" in {
        import SpiderActor._

        combineCounts(Nil, Nil) should be(Nil)
        val threeHellos = List("hello" -> 3)
        val sixHellos = List("hello" -> 6)
        combineCounts(threeHellos, Nil) should be(threeHellos)
        combineCounts(Nil, threeHellos) should be(threeHellos)
        combineCounts(threeHellos, threeHellos) should be(sixHellos)

        val someCounts = List("j" -> 5, "p" -> 4, "c" -> 3, "b" -> 2, "a" -> 1)
        val longerCounts = List("z" -> 11, "f" -> 7, "j" -> 5, "p" -> 4, "c" -> 3, "b" -> 2, "a" -> 1)
        val combinedCounts = List("z" -> 11, "j" -> 10, "p" -> 8, "f" -> 7, "c" -> 6, "b" -> 4, "a" -> 2)
        combineCounts(someCounts, longerCounts) should be(combinedCounts)
        combineCounts(longerCounts, someCounts) should be(combinedCounts)
    }

    behavior of "SpiderActor"

    it should "spider from test http server" in {
        val url = httpServer.resolve("/resource/ToSpider.html")
        val spider = system.actorOf(Props[SpiderActor])
        val indexFuture = (spider ? Spider(url)) map {
            case Spidered(url, index) =>
                index
            case whatever =>
                throw new Exception("Got bad result from Spider: " + whatever)
        }
        val index = Await.result(indexFuture, timeout.duration)

        index.wordCounts.size should be(50)
        val nowheres = (index.links filter { link => link._2.endsWith("/nowhere") } map { _._1 }).sorted
        nowheres should be(Seq("a", "d", "e", "f", "g", "h", "j", "k", "m", "o"))
        system.stop(spider)
    }
}
