package com.typesafe.webwords.web

import com.typesafe.webwords.common._
import scala.xml
import scala.xml.Attribute
import com.typesafe.play.mini._
import play.api.mvc._
import play.api.mvc.Results._
import play.api.libs.concurrent._
import play.api.data.Form
import play.api.data.Forms._
import java.net.URL
import java.net.URI
import java.net.MalformedURLException
import java.net.URISyntaxException
import akka.actor.{Props, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout

/**
 * This is the main application for the web process.
 * This application is registered via Global
 */
object WebMain extends Application {
    private val system = ActorSystem("WebWordsWeb")
    private val client = system.actorOf(Props(new ClientActor(WebWordsConfig())), "web-client")

    def route = {

        case GET(Path("/")) => Action {
            Ok(wordsPage(form("", false), Nil)).as("text/html")
        }

        case GET(Path("/words")) => Action { implicit request =>
            wordsForm.bindFromRequest.fold(
            formWithErrors => BadRequest(wordsPage(form("", false, true), Nil)).as("text/html"),
            { case (url, skipCache) => handleWords(url, skipCache.getOrElse(false)) })
        }

    }

    val wordsForm = Form(
        tuple(
            "url" -> text,
            "skipCache" -> optional(checked("Skip Cache"))))

    private def form(url: String, skipCache: Boolean, badUrl: Boolean = false) = {
        <div>
            <form action="/words" method="get">
                <fieldset>
                    <div>
                        <label for="url">Site</label>
                        <input type="text" id="url" name="url" value={ url } style="min-width: 300px;"/>
                        {
                        if (badUrl) {
                            <div style="font-color: red;">Invalid or missing URL</div>
                        }
                        }
                    </div>
                    <div>
                        {
                        <input type="checkbox" id="skipCache" name="skipCache" value="true"/> %
                            (if (skipCache) Attribute("checked", xml.Text(""), xml.Null) else xml.Null)
                        }
                        <label for="skipCache">Skip cache</label>
                    </div>
                    <div>
                        <button>Spider &amp; Index</button>
                    </div>
                </fieldset>
            </form>
        </div>
    }

    private def results(url: String, index: Index, cacheHit: Boolean, elapsed: Long) = {
        // world's ugliest word cloud!
        def countToStyle(count: Int) = {
            val maxCount = (index.wordCounts.headOption map { _._2 }).getOrElse(1)
            val font = 6 + ((count.doubleValue / maxCount.doubleValue) * 24).intValue
            Attribute("style", xml.Text("font-size: " + font + "pt;"), xml.Null)
        }

        <div>
            <p>
                <a href={ url }>{ url }</a>
                spidered and indexed.
            </p>
            <p>{ elapsed }ms elapsed.</p>
            <p>{ index.links.size } links scraped.</p>
            {
            if (cacheHit)
                <p>Results were retrieved from cache.</p>
            else
                <p>Results newly-spidered (not from cache).</p>
            }
        </div>
            <h3>Word Counts</h3>
            <div style="max-width: 600px; margin-left: 100px; margin-top: 20px; margin-bottom: 20px;">
                {
                val nodes = xml.NodeSeq.newBuilder
                for ((word, count) <- index.wordCounts) {
                    nodes += <span title={ count.toString }>{ word }</span> % countToStyle(count)
                    nodes += xml.Text(" ")
                }
                nodes.result
                }
            </div>
            <div style="font-size: small">(hover to see counts)</div>
            <h3>Links Found</h3>
            <div style="margin-left: 50px;">
                <ol>
                    {
                    val nodes = xml.NodeSeq.newBuilder
                    for ((text, url) <- index.links)
                        nodes += <li><a href={ url }>{ text }</a></li>
                    nodes.result
                    }
                </ol>
            </div>
    }

    def wordsPage(formNode: xml.NodeSeq, resultsNode: xml.NodeSeq) = {
        <html>
            <head>
                <title>Web Words!</title>
            </head>
            <body style="max-width: 800px;">
                <div>
                    <div>
                        { formNode }
                    </div>
                    {
                    if (resultsNode.nonEmpty)
                        <div>
                            { resultsNode }
                        </div>
                    }
                </div>
            </body>
        </html>
    }

    private def completeWithHtml(html: xml.NodeSeq) = {
        "<!DOCTYPE html>\n" + html
    }

    private def parseURL(s: String): Option[URL] = {
        val maybe = try {
            val uri = new URI(s) // we want it to be a valid URI also
            val url = new URL(s)
            // apparently a valid URI can have no hostname
            if (uri.getHost() == null)
                throw new URISyntaxException(s, "No host in URI")
            Some(url)
        } catch {
            case e: MalformedURLException => None
            case e: URISyntaxException => None
        }
        maybe.orElse({
            if (s.startsWith("http"))
                None
            else
                parseURL("http://" + s)
        })
    }

    private def handleWords(url: String, skipCache: Boolean) = {
        val parsedUrl = parseURL(url)

        if (parsedUrl.isDefined) {
            implicit val timeout = Timeout(system.settings.config.getMilliseconds("akka.timeout.default"))
            val startTime = System.currentTimeMillis

            AsyncResult {
                (client ? GetIndex(parsedUrl.get.toExternalForm, skipCache)).mapTo[GotIndex].asPromise map {
                    case GotIndex(url, indexOption, cacheHit) =>
                        val elapsed = System.currentTimeMillis - startTime
                        if (indexOption.isDefined) {
                            val html = wordsPage(form(url, skipCache), results(url, indexOption.get, cacheHit, elapsed))
                            Ok(completeWithHtml(html)).as("text/html")
                        } else {
                            Ok(completeWithHtml(<H1>Failed to index url in { elapsed } ms (try reloading)</H1>)).as("text/html")
                        }
                }
            }
        } else {
            val html = wordsPage(form(url, skipCache, badUrl = true), resultsNode = xml.NodeSeq.Empty)

            BadRequest(completeWithHtml(html)).as("text/html")
        }
    }
}
