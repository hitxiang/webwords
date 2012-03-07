package com.typesafe.webwords.common

import scala.collection.JavaConverters._

import org.scalatest.matchers._
import org.scalatest._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import akka.dispatch.Await

import javax.servlet.http.HttpServletResponse

class URLFetcherSpec extends FlatSpec with ShouldMatchers with BeforeAndAfterAll {
    behavior of "URLFetcher"

    implicit val system = ActorSystem("URLFetcherSpec")

    var httpServer: TestHttpServer = null

    override def beforeAll = {
        httpServer = new TestHttpServer()
        httpServer.start()
    }

    override def afterAll = {
        system.shutdown()
        httpServer.stop()
        httpServer = null
    }

    implicit val timeout = Timeout(system.settings.config.getMilliseconds("akka.timeout.default"))

    it should "fetch an url" in {
        val fetcher = system.actorOf(Props[URLFetcher])
        val f = fetcher ? FetchURL(httpServer.resolve("/hello"))
        Await.result(f, timeout.duration) match {
            case URLFetched(status, headers, body) =>
                status should be(HttpServletResponse.SC_OK)
                body should be("Hello\n")
            case _ =>
                throw new Exception("Wrong reply message from fetcher")
        }
        system.stop(fetcher)
    }

    it should "handle a 404" in {
        val fetcher = system.actorOf(Props[URLFetcher])
        val f = fetcher ? FetchURL(httpServer.resolve("/nothere"))
        Await.result(f, timeout.duration) match {
            case URLFetched(status, headers, body) =>
                status should be(HttpServletResponse.SC_NOT_FOUND)
            case _ =>
                throw new Exception("Wrong reply message from fetcher")
        }
        system.stop(fetcher)
    }

    it should "fetch many urls in parallel" in {
        // the httpServer only has a fixed number of threads so if you make latency
        // or number of requests too high, the futures will start to time out
        httpServer.withRandomLatency(300) {
            val fetcher = system.actorOf(Props[URLFetcher])
            val numToFetch = 500
            val responses = for (i <- 1 to numToFetch)
                yield (fetcher ? FetchURL(httpServer.resolve("/echo", "what", i.toString)), i)

            val completionOrder = new java.util.concurrent.ConcurrentLinkedQueue[Int]()

            responses foreach { tuple =>
                tuple._1.onComplete({ f =>
                    completionOrder.add(tuple._2)
                })
            }

            var nFetched = 0
            responses foreach { tuple =>
                val f = tuple._1
                val expected = tuple._2.toString
                Await.result(f, timeout.duration) match {
                    case URLFetched(status, headers, body) =>
                        status should be(HttpServletResponse.SC_OK)
                        body should be(expected)
                        nFetched += 1
                    case _ =>
                        throw new Exception("Wrong reply message from fetcher")
                }
            }
            nFetched should be(numToFetch)

            val completed = completionOrder.asScala.toList
            completed.length should be(numToFetch)
            // the random latency should mean we completed in semi-random order
            completed should not be (completed.sorted)

            system.stop(fetcher)
        }
    }
}
