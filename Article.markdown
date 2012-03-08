# Scaling Out with Scala, Akka, and Heroku

This article is a guided tour of an application called ["Web Words"][repo]
illustrating the use of Scala and Akka on Heroku's Cedar stack. Web Words
goes beyond "Hello, World" to show you a sampler of the technology you might
use in a real-world application:

 - organizing a scalable application with **Akka actors**, rather than with
   explicit threads and `java.util.concurrent`
 - the power of **functional programming** and **parallel collections** in Scala
 - dividing an application into **separate web and worker processes**,
   using **RabbitMQ** to send jobs to the worker
 - caching worker results in a **MongoDB capped collection**
 - embedding a **Jetty** HTTP server
 - forwarding Jetty requests to **Akka HTTP** to handle them asynchronously
   without tying up a thread
 - many small details along the way: using a Java library from Scala, using
   Akka actor pools, using Akka's Future class, some cute Scala tricks,
   and more.

Because this sampler application shows so many ideas, you may want to skip
around the article to the topics you're most interested in.

Don't forget, if the article skips a detail you'd like to know more about,
you can also [jump to the source code][repo] to see what's what.

The [README][repo] over on GitHub has instructions for running Web Words
locally or on Heroku.

This article is not a ground-up tutorial on any of the technologies
mentioned; the idea is to give you a taste. So don't worry if some of the
details remain unclear, just follow the links and dig deeper!

## Web Words

The sample application, [Web Words][repo], takes a site URL and does a "shallow
spider" (following just a few of the same-site links at the URL). It churns
through the HTML on the site, calculating word frequencies and scraping
links, then presents results:

![Web Words screenshot](webwords.png "Web Words screenshot")

Web Words includes both IO-bound and CPU-bound tasks, illustrating how Scala
and Akka support both.

The application is split into a web process, which parses HTTP requests and
formats results in HTML, and a worker process called `indexer` which does
the spidering and computes the results.

## Overview: a request step-by-step

If you follow an incoming request to Web Words, here's what the app
shows you:

 - an **embedded Jetty** HTTP server receives requests to spider sites
     - http://wiki.eclipse.org/Jetty/Tutorial/Embedding_Jetty
 - requests are forwarded to **Akka HTTP**, which uses Jetty Continuations
   to keep requests from tying up threads
     - http://akka.io/docs/akka/1.2/scala/http.html
 - the web process checks for previously-spidered info in a
   **MongoDB capped collection** which acts as a cache.
   This uses the **Heroku MongoHQ addon**.
     - http://www.mongodb.org/display/DOCS/Capped+Collections
     - http://devcenter.heroku.com/articles/mongohq
 - if the spider results are not cached, the web process
   sends a spider request to an indexer process using
   the **RabbitMQ AMQP addon**
     - http://www.rabbitmq.com/getstarted.html
     - http://blog.heroku.com/archives/2011/8/31/rabbitmq_add_on_now_available_on_heroku/
 - the app talks to RabbitMQ using **Akka AMQP**
      - http://akka.io/docs/akka-modules/1.2/modules/amqp.html
 - the indexer process receives a request from AMQP and shallow-spiders
   the site using an Akka actor that encapsulates **AsyncHttpClient**
     - https://github.com/sonatype/async-http-client
 - the indexer uses Akka, **Scala parallel collections**, and **JSoup** to
   grind through the downloaded HTML taking advantage of multiple CPU cores
     - http://www.scala-lang.org/api/current/scala/collection/parallel/package.html
     - http://jsoup.org
 - the indexer stores its output back in the MongoDB cache and sends
   an AMQP message back to the web process
 - the web process loads the now-cached data from MongoDB
 - the web process unsuspends the Jetty request and writes out the results

## Akka: Actor and Future

Almost all the code in Web Words runs inside Akka actors.

An _actor_ is the fundamental unit of organization in an [Akka][akka]
application. The [actor model][actors] comes from [Erlang][erlang], where
it's said to support code with nine "9"s of uptime.

An actor is:

 - an object
 - that sends and receives messages
 - that's guaranteed to process only one message on one thread at a time

Because actors work with messages, rather than methods, they look like
little network servers rather than regular objects. However, since they're
(often) in-process, actors are much lighter-weight than a separate server
daemon would be. In fact, they're much lighter-weight than threads.  One JVM
process could contain millions of actors, compared to thousands of threads.

### Toy Actor example

A trivial, toy example could look like:

    import akka.actor._

    class HelloActor extends Actor {
        override def receive = {
            case "Hello" => self reply "World"
        }
    }

This is an actor that receives a string object "Hello" as a message and
sends back the string object "World" as a message.

There are only two steps to create an actor:

 - extend the `Actor` base class
 - override `receive` to handle your messages

The whole point of an `Actor` is that `receive` need not be thread-safe; it
will be called for _one message at a time_ so there's no need for locking on
your actor's member variables, as long as you only touch the actor's state
from inside `receive`. (If you spawn your own threads and have those touch
the actor outside Akka's control, you are on your own. Don't do that.)

To use this actor, you could write:

    import akka.actor._
    import akka.actor.Actor.actorOf

    val actor = actorOf[HelloActor]
    actor.start
    val future = actor ? "Hello"
    println("Got: " + future.get)
    actor.stop

The method `Actor.actorOf` creates an [ActorRef][actorref], which is a
handle that lets you talk to an actor. The idea is to forbid you from
calling methods on the actor; you can only send messages. (Also: the
`ActorRef` may refer to an actor running on another machine, or due to actor
restarts may refer to different actor instances over time.)

Actor references have a `start` method which registers the actor with the
Akka system and a `stop` method which unregisters the actor.

The operator `?` (also known as `ask`) sends a message to an actor and
returns a `Future` containing the reply to that message. `Future.get` blocks
and waits for the future to be completed (a bad practice! we'll see later
how to avoid it), returning the result contained in the `Future`.

If you don't need a `Future` for the reply, you can use the `!` operator
(also known as `tell`) to send a message instead. If you use `!` from within
another actor, the sending actor will still receive any reply message, but
it will be handled by the actor's `receive` method rather than sent to a
`Future`.

### Real actors and futures in Web Words

Now let's look at a real example.

#### URLFetcher actor

In [URLFetcher.scala][webwordsurlfetcher], an actor encapsulates
[AsyncHttpClient][asynchttpclient]. The actor supports only one message,
`FetchURL`, which asks it to download a URL:

    sealed trait URLFetcherIncoming
    case class FetchURL(u: URL) extends URLFetcherIncoming

While messages can be any object, it's highly recommended to use _immutable_
objects (immutable means no "setters" or modifiable state). In Scala, a
[case class][caseclasses] makes an ideal message.

The `URLFetcherIncoming` trait is optional: it gives you a type shared by
all messages coming in to the actor. Because the trait is `sealed`, the
compiler can warn you if a match expression doesn't handle all message
types.

The `URLFetcher` actor supports only one outgoing message, a reply to
`FetchURL`:

    sealed trait URLFetcherOutgoing
    case class URLFetched(status: Int, headers: Map[String, String], body: String) extends URLFetcherOutgoing

The actor itself holds an `AsyncHttpClient` object from the
[AsyncHttpClient][asynchttpclient] library and uses it to handle `FetchURL`
messages, like this:

    class URLFetcher extends Actor {

        private val asyncHttpClient = URLFetcher.makeClient

        override def receive = {
            case incoming: URLFetcherIncoming => {
                val f = incoming match {
                    case FetchURL(u) =>
                        URLFetcher.fetchURL(asyncHttpClient, u)
                }

                self.channel.replyWith(f)
            }
        }

        override def postStop = {
            asyncHttpClient.close()
        }
    }

Of course the real work happens in `URLFetcher.fetchURL()` which maps the
[AsyncHttpClient][asynchttpclient] API onto an Akka future. Check out
[URLFetcher.scala][webwordsurlfetcher] to see that code.

`postStop` is a hook method actors can override to clean up when the actor
shuts down, in this case it closes the `AsyncHttpClient` object.

Akka automatically sets up a `self` field, referring to an actor's own
[ActorRef][actorref].

`self.channel` refers to the current message's sender. A `Channel` can
receive messages, and may be either a `Future` or an `Actor`.

`replyWith` is a utility method kept in the Web Words `common` project. It's
added to Akka's `Channel` using the so-called
["Pimp my Library" pattern][pimpmylibrary], so [its implementation][webwordscommonpackage]
illustrates both that pattern and the use of `Future`:

    // Class that adds replyWith to Akka channels
    class EnhancedChannel[-T](underlying: Channel[T]) {
        /**
         * Replies to a channel with the result or exception from
         * the passed-in future
         */
        def replyWith[A <: T](f: Future[A])(implicit sender: UntypedChannel) = {
            f.onComplete({ f =>
                f.value.get match {
                    case Left(t) =>
                        underlying.sendException(t)
                    case Right(v) =>
                        underlying.tryTell(v)
                }
            })
        }
    }

    // implicitly create an EnhancedChannel wrapper to add methods to the
    // channel
    implicit def enhanceChannel[T](channel: Channel[T]): EnhancedChannel[T] = {
        new EnhancedChannel(channel)
    }

The above code is in the
[package.scala for the common project][webwordscommonpackage]. In it, you
can see how to set up an `onComplete` callback to be invoked when a `Future`
is completed.

Important caution: a `Future` will always invoke callbacks in another
thread!  To avoid concurrency issues and stick to the actor model, use
callbacks _only_ to send messages to actors, keeping the real work in an
actor's `receive` method.

#### SpiderActor

`URLFetcher` doesn't do all that much; it's a simple proxy giving the
`AsyncHttpClient` object an Akka-style API.

Let's look at [SpiderActor][webwordsspideractor], which uses the
`URLFetcher` to shallow-spider a site.

Again this actor has one request and one reply to go with it:

    sealed trait SpiderRequest
    case class Spider(url: URL) extends SpiderRequest

    sealed trait SpiderReply
    case class Spidered(url: URL, index: Index)

Given a site URL, the `SpiderActor` computes an `Index` (see
[Index.scala][webwordsindex]) to go with it.

`SpiderActor` delegates to two other actors, one of which is the
`URLFetcher`:

    class SpiderActor
        extends Actor {
        private val indexer = actorOf[IndexerActor]
        private val fetcher = actorOf[URLFetcher]

        override def preStart() = {
            indexer.start
            fetcher.start
        }

        override def postStop() = {
            indexer.stop
            fetcher.stop
        }

`SpiderActor` ties the two other actors to its own lifecycle by overriding
`preStart` and `postStop`, ensuring that the entire "tree" of actors starts
and stops together.

#### Composing futures

`SpiderActor` offers a nice illustration of how to use `map` and `flatMap`
with `Future`. First, in a `fetchBody` method, we send a request to the
`URLFetcher` then use `map` to convert the `URLFetched` reply into a
simple string:

    private def fetchBody(fetcher: ActorRef, url: URL): Future[String] = {
        val fetched = fetcher ? FetchURL(url)
        fetched map {
            case URLFetched(status, headers, body) if status == 200 =>
                body
            case URLFetched(status, headers, body) =>
                throw new Exception("Failed to fetch, status: " + status)
            case whatever =>
                throw new IllegalStateException("Unexpected reply to url fetch: " + whatever)
        }
    }

This example _does not block_. The code after `map` runs asynchronously,
after the `URLFetched` reply arrives, and extracts the reply body as a
string. If something goes wrong and the exceptions here are thrown, the
returned `Future[String]` would be completed with an exception instead of a
result.

Once a reply body comes back, `SpiderActor` will want to index it (a task
performed by [IndexerActor][webwordsindexeractor]). Indexing is itself an
asynchronous operation. To "chain" two futures, use `flatMap`.

Both `map` and `flatMap` return a new future. With `map`, you provide a
function to convert the original future's value, when available, into a new
value. With `flatMap`, you provide a function to convert the original
future's value, when available, into yet another future. `flatMap` is useful
if you need to do something else asynchronous, once you have a value from
the original future.

This code from `SpiderActor` uses both `map` and `flatMap` to chain the
`Future[String]` from `fetchBody` (shown above) into a `Future[Index]`.

    private def fetchIndex(indexer: ActorRef, fetcher: ActorRef, url: URL): Future[Index] = {
        fetchBody(fetcher, url) flatMap { body =>
            val indexed = indexer ? IndexHtml(url, body)
            indexed map { result =>
                result match {
                    case IndexedHtml(index) =>
                        index
                }
            }
        }
    }

Nothing here is blocking, because the code never uses `Future.await` or
`Future.get`. Instead, `map` and `flatMap` are used to transform
futures... in the future.

The nice thing about this is that `map` and `flatMap` are standard methods
as seen in Scala's normal collections library, and as seen in Scala's
`Option` class. `Future` is like a one-element collection that automatically
keeps itself asynchronous as it's transformed.

Other collection operations such as `filter` and `foreach` work on `Future`,
too!

### Actor pools

[IndexerActor][webwordsindexeractor], used by
[SpiderActor][webwordsspideractor], is an example of an _actor pool_. An
actor pool is an actor that contains a pool of identical delegate
actors. Pools can be configured to determine how they load-balance messages
among delegates, and to control when they create and destroy delegates.

In Web Words, actor pools are set up in two abstract utility classes,
[CPUBoundActorPool][webwordscpuboundpool] and
[IOBoundActorPool][webwordsioboundpool]. These pools have settings intended
to make sense for delegates that compute something on the CPU or delegates
that perform blocking IO, respectively.

Many of the settings defined in these utility classes were not arrived at
scientifically; you'd need to run benchmarks are on your particular
application and hardware to know the ideal settings for sure.

Let's look at [CPUBoundActorPool][webwordscpuboundpool], then its subclass
[IndexerActor][webwordsindexeractor].

First, [CPUBoundActorPool][webwordscpuboundpool] mixes in some traits to
select desired policies:

    trait CPUBoundActorPool
        extends DefaultActorPool
        with SmallestMailboxSelector
        with BoundedCapacityStrategy
        with MailboxPressureCapacitor
        with Filter
        with BasicRampup
        with BasicBackoff {

Reading from the top down, this actor pool will:

 - `SmallestMailboxSelector`: send each message to the delegate with the
   smallest mailbox (least message backlog)
 - `BoundedCapacityStrategy`: computes the number of delegates within an
   upper and a lower limit, based on a `pressure` and a `filter` method.
   `pressure` returns the number of "busy" delegates, while `filter` computes
   a change in actual number of delegates based on the current number and
   the current pressure.
 - `MailboxPressureCapacitor`: provides a `pressure` method which counts
   delegates as "busy" if they have a backlog of messages exceeding a
   certain threshold
 - `Filter`: provides a `filter` method which delegates to
   `rampup` and `backoff` methods. These compute proposed increases and
   decreases in capacity, respectively.
 - `BasicRampup`: implements the `rampup` method to compute a percentage increase in delegates
   when pressure reaches current capacity.
 - `BasicBackoff`: implements the `backoff` method to compute a percentage
   decrease in delegates when pressure falls below a threshold percentage of
   capacity.

[CPUBoundActorPool][webwordscpuboundpool] configures its mixin traits by
overriding methods:

        // Selector: selectionCount is how many pool members to send each message to
        override def selectionCount = 1

        // Selector: partialFill controls whether to pick less than selectionCount or
        // send the same message to duplicate delegates, when the pool is smaller
        // than selectionCount. Does not matter if lowerBound >= selectionCount.
        override def partialFill = true

        // BoundedCapacitor: create between lowerBound and upperBound delegates in the pool
        override val lowerBound = 1
        override lazy val upperBound = Runtime.getRuntime().availableProcessors() * 2

        // MailboxPressureCapacitor: pressure is number of delegates with >pressureThreshold messages queued
        override val pressureThreshold = 1

        // BasicRampup: rampupRate is percentage increase in capacity when all delegates are busy
        override def rampupRate = 0.2

        // BasicBackoff: backoffThreshold is the percentage-busy to drop below before
        // we reduce actor count
        override def backoffThreshold = 0.7

        // BasicBackoff: backoffRate is the amount to back off when we are below backoffThreshold.
        // this one is intended to be less than 1.0-backoffThreshold so we keep some slack.
        override def backoffRate = 0.20

Each message will go to just one delegate. The pool will vary between 1 and
(2x number of cores) delegates. We'll ramp up by 20% if all delegates have a
backlog of 1 already. We'll back off by 20% if only 70% of delegates have a
backlog of 1. Again, the exact settings are not scientific; you'd have to
tune this in a real application.

To subclass [CPUBoundActorPool][webwordscpuboundpool],
[IndexerActor][webwordsindexeractor] has to implement just one more thing, a
method called `instance` which generates a new delegate:

    override def instance = Actor.actorOf(new Worker())

Actor pools have a method `_route` which just forwards to a delegate, so
`IndexerActor` can implement `receive` with that:

    override def receive = _route

Optionally, an actor pool could look at the message and decide whether to
send it to `_route` or do something else instead.

### akka.conf

Akka has a configuration file `akka.conf`, automatically loaded from the
classpath.  Typically you might want to configure the size of Akka's thread
pool and the length of Akka's timeouts. See
[the akka.conf for the web process][webwordsakkaconf] for an example.

## Scala

While this article is not an introduction to Scala, the Web Words example
does show off some nice properties of Scala that deserve mention.

### Working with Java libraries

If you had to rewrite all your Java code, you'd never be able to switch to
Scala. Fortunately, you don't.

For example, [IndexerActor][webwordsindexeractor] uses a Java library,
called [JSoup][jsoup], to parse HTML.

In general, you import a Java library and then use it, like this:

    import org.jsoup.Jsoup

    val doc = Jsoup.parse(docString, url.toExternalForm)

The most common "catch" is that Scala APIs use Scala's collections library,
while Java APIs use Java's collections library. To solve that, Scala
provides two options.

The first one adds explicit `asScala` and `asJava` methods to collections,
and can be found in `JavaConverters`:

    import scala.collection.JavaConverters._

    val anchors = doc.select("a").asScala

The second option, not used in [IndexerActor][webwordsindexeractor], adds
implicit conversions among Scala and Java collections so things "just work";
the downside is, you can't see by reading the code that there's a conversion
going on. To get implicit conversions, import
`scala.collection.JavaConversions._` rather than `JavaConverters`.

The choice between explicit `asScala` and `asJava` methods, and implicit
conversions, is a matter of personal taste in most cases. There may be some
situations where an explicit conversion is required if the Scala compiler
can't figure out which implicit to use.

The converters work efficiently by creating wrappers around the original
collection, so in general should not add much overhead.

### Functional programming

With CPUs getting more cores rather than higher clock speeds, functional
programming becomes more relevant than ever. Akka's actor model and Scala's
functional programming emphasis are two tools for developing multithreaded
code without error-prone thread management and locking.

#### (What is it, anyway?)

You may be wondering what "functional programming" means, and why it's
important that Scala offers it.

Here's a simple definition. [Functional programming][functional] emphasizes
transformation (take a value, return a new value) over mutable state (take a
value, change the value in-place). Functional programming contrasts with
[imperative or procedural programming][imperative].

The word _function_ here has the sense of a mathematics-style function. If
you think about _f(x)_ in math, it maps a value `x` to some result `f(x)`.
`f(x)` always represents the same value for a given `x`. This "always the
same output for the same input" property also describes program subroutines
that don't rely upon or modify any mutable state.

In addition to the core distinction between transformation and mutation,
"functional programming" tends to imply certain cultural traditions: for
example, a `map` operation that transforms a list by applying a function to
each list element.

Functional programming isn't really a language feature, it's a pattern
that can be applied in any language. For example, here's how you could use
add one to each element in a list in Java, by modifying the list in-place
(treating the list as mutable state):

    public static void addOneToAll(ArrayList<Integer> items) {
        for (int i = 0; i < items.size(); ++i) {
            items.set(i, items.get(i) + 1);
        }
    }

But you could also use a functional style in Java, transforming the list
into a new list without modifying the original:

    public static List<Integer> addOneToAll(List<Integer> items) {
        ArrayList<Integer> result = new ArrayList<Integer>();
        for (int i : items) {
            result.add(i + 1);
        }
        return result;
    }

Unsurprisingly, you can use either style in Scala as well. Imperative
style in Scala:

    def addOneToAll(items : mutable.IndexedSeq[Int]) = {
        var i = 0
        while (i < items.length) {
            items.update(i, items(i) + 1)
            i += 1
        }
    }

Functional style in Scala:

    def addOneToAll(items : Seq[Int]) = items map { _ + 1 }

You might notice that the "functional style in Scala" example is shorter
than the other three approaches. Not an uncommon situation.

There are several advantages to functional programming:

  - it's inherently parallelizable and thread-safe
  - it enables many optimizations, such as lazy evaluation
  - it can make code more flexible and generic
  - it can make code shorter

Let's look at some examples in Web Words.

#### Collection transformation

In [SpiderActor][webwordsspideractor], there's a long series of
transformations to choose which links on a page to spider:

    // pick a few links on the page to follow, preferring to "descend"
    private def childLinksToFollow(url: URL, index: Index): Seq[URL] = {
        val uri = removeFragment((url.toURI))
        val siteRoot = copyURI(uri, path = Some(null))
        val parentPath = new File(uri.getPath).getParent
        val parent = if (parentPath != null) copyURI(uri, path = Some(parentPath)) else siteRoot

        val sameSiteOnly = index.links map {
            kv => kv._2
        } map {
            new URI(_)
        } map {
            removeFragment(_)
        } filter {
            _ != uri
        } filter {
            isBelow(siteRoot, _)
        } sortBy {
            pathDepth(_)
        }
        val siblingsOrChildren = sameSiteOnly filter { isBelow(parent, _) }
        val children = siblingsOrChildren filter { isBelow(uri, _) }

        // prefer children, if not enough then siblings, if not enough then same site
        val toFollow = (children ++ siblingsOrChildren ++ sameSiteOnly).distinct take 10 map { _.toURL }
        toFollow
    }

(The syntax `{ _ != uri }` is a function with one parameter, represented by
`_`, that returns a boolean value.)

This illustrates some handy methods found in the Scala collections API.

- `map` transforms each element in a collection, returning a new collection
  of transformed elements. For example, `map { new URI(_) }` in the above
  converts a list of strings to a list of `URI` objects.
- `filter` uses a boolean test on each element, including only the elements
  matching the test in a new collection. For example, `filter { _ != uri }`
  in the above includes only those URIs that aren't the same as the original
  root URI.
- `sortBy` sorts a collection using a function on each element as the key,
  so to sort by path depth it's `sortBy { pathDepth(_) }`.
- `distinct` unique-ifies the collection.
- `take` picks only the first N items from a collection.

The `childLinksToFollow` function might be longer and more obfuscated if you
wrote it in Java with the Java collections API. The Scala version is also
better abstracted: `index.links` could be any kind of collection (Set or
List, parallel or sequential) with few or no code changes.

#### Better refactoring

First-class functions are a powerful feature for factoring out common
code. For example, in the [AMQPCheck][webwordsamqpcheck] class
(incidentally, another nice example of using an existing Java API from
Scala), several places need to close an AMQP object while ignoring
possible exceptions. You can quickly and easily do this in Scala:

    private def ignoreCloseException(body: => Unit): Unit = {
        try {
            body
        } catch {
            case e: IOException =>
            case e: AlreadyClosedException =>
        }
    }

Then use it like this:

     ignoreCloseException { channel.close() }
     ignoreCloseException { connection.close() }

You could also use a more traditional Java-style syntax, like this:

     ignoreCloseException(channel.close())
     ignoreCloseException(connection.close())

In Java, factoring this out to a common method might be clunky enough to
keep you from doing it.

#### Parallel collections

[Parallel collections][par] have the same API as regular Scala collections,
but operations on them magically take advantage of multiple CPU cores.

Convert any regular (sequential) collection to parallel with the `par`
method and convert any parallel collection to sequential with the `seq`
method. In most situations, parallel and sequential collections are
interchangeable, so conversions may not be needed in most code.

Two important points about Scala's collections library that may be
surprising compared to Java:

- immutable collections are the default; operations on immutable collections
  return a new, transformed collection, rather than changing the old one in-place
- when transforming a collection, the new collection will have the same type
  as the original collection

These properties are crucial to [parallel collections][par]. As you use
`map`, `filter`, `sortBy`, etc. on a parallel collection, each new result
you compute will itself be parallel as well. This means you only need to
convert to parallel once, with a call to `par`, to convert an entire chain
of computations into parallel computations.

Parallel collections are enabled by functional programming; as long as you
only use the functional style, the use of multiple threads doesn't create
bugs or trickiness. Parallel looks just like sequential.

Returning to [IndexerActor][webwordsindexeractor], you can see parallel
collections in action. We want to perform a word count; it's a
parallelizable algorithm. So we split the HTML into a parallel
collection of lines:

    val lines = s.split("\\n").toSeq.par

(`toSeq` here converts the array from `java.lang.String.split()` to a Scala
sequence, then `par` converts to parallel.)

Then for each line _in parallel_ we can break the line into words:

    val words = lines flatMap { line =>
            notWordRegex.split(line) filter { w => w.nonEmpty }
        }

The `flatMap` method creates a new collection by matching each element in
the original collection to a new sub-collection, then combining the
sub-collections into the new collection. In this case, because `lines` was a
parallel collection, the new collection from `flatMap` will be too.

The parallel collection of words then gets filtered to take out boring words
like "is":

    splitWords(body.text) filter { !boring(_) }

And then there's a function to do the actual word count, again in parallel:

    private[indexer] def wordCount(words: ParSeq[String]) = {
        words.aggregate(Map.empty[String, Int])({ (sofar, word) =>
            sofar.get(word) match {
                case Some(old) =>
                    sofar + (word -> (old + 1))
                case None =>
                    sofar + (word -> 1)
            }
        }, mergeCounts)
    }

The `aggregate` method needs two functions. The first argument to
`aggregate` is identical to the one you'd pass to `foldLeft`: here it adds
one new word to a map from words to counts, returning the new map. In fact
you could write `wordCount` with `foldLeft`, but it wouldn't use multiple
threads since `foldLeft` has to process elements in sequential order:

    // ParSeq can't parallelize foldLeft in this version
    private[indexer] def wordCount(words: ParSeq[String]) = {
        words.foldLeft(Map.empty[String, Int])({ (sofar, word) =>
            sofar.get(word) match {
                case Some(old) =>
                    sofar + (word -> (old + 1))
                case None =>
                    sofar + (word -> 1)
            }
        })
    }

The second argument to `aggregate` makes it different from `foldLeft`: it
allows `aggregate` to combine two intermediate results. The signature of
`mergeCounts` is:

    def mergeCounts(a: Map[String, Int], b: Map[String, Int]): Map[String, Int]

With this available, `aggregate` can:

 - subdivide the parallel collection (split the sequence of words into multiple sequences)
 - fold the elements in each subdivision together (counting word frequencies
   per-subdivision in a `Map[String,Int]`)
 - aggregate the results from each subdivision (merging word frequency maps
   into one word frequency map)

When `wordCount` returns, [IndexerActor][webwordsindexeractor] computes a
list of the top 50 words:

    wordCount(words).toSeq.sortBy(0 - _._2) take 50

`toSeq` here converts the `Map[String,Int]` to a `Seq[(String, Int)]`; the
result gets sorted in descending order by count; then `take 50` takes up to
50 items from the start of the sequence.

Full disclosure: it's not really a given that using parallel collections for
[IndexerActor][webwordsindexeractor] makes sense. That is, it's completely
possible that if you benchmark on a particular hardware setup with some
particular input data, using parallel collections here turns out to be
slower than sequential. Fortunately, one advantage of the parallel
collections approach is that it's trivial to switch between parallel and
sequential collections as your benchmark results roll in.

### XML Support

In [WebActors.scala][webwordswebactors] you can see an example of Scala's
inline XML support. In this case, it works as a simple template system to
generate HTML. Of course there are many template systems available for Scala
(plus you can use all the Java ones), but a simple application such as Web
Words gets pretty far with the built-in XML support.

Here's a function from [WebActors.scala][webwordswebactors] that returns the
page at `/words`:

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

You can just type XML literals into a Scala program, breaking out into Scala
code with `{}` anywhere inside the XML. The `{}` blocks should return a
string (which will be escaped) or a `NodeSeq`. XML literals themselves are
values of type `NodeSeq`.

## Bridging HTTP to Akka

There are lots of ways to serve HTTP from Scala, even if you only count
Scala-specific libraries and frameworks and ignore the many options
inherited from Java.

Web Words happens to combine embedded Jetty with Akka's HTTP support.

### Embedded Jetty: web server in a box

Heroku gives you more flexibility than most cloud JVM providers because you
can run your own `main()` method, rather than providing a `.war` file to be
deployed in a servlet container.

Web Words takes advantage of this, using embedded Jetty to start up an HTTP
server. Because Web Words on Heroku knows it's using Jetty, it can rely on
[Jetty Continuations][jettycontinuations], a Jetty-specific feature that
allows Akka HTTP to reply to HTTP requests asynchronously without tying up a
thread for the duration of the request. (Traditionally, Java servlet
containers need a thread for every open request.)

There's very little to this; see [WebServer.scala][webwordswebserver], where
we fire up a Jetty `Server` object on the port provided by Heroku (the
`PORT` env variable is picked up in [WebWordsConfig.scala][webwordsconfig]):

    val server = new Server(config.port.getOrElse(8080))
    val handler = new ServletContextHandler(ServletContextHandler.SESSIONS)
    handler.setContextPath("/")
    handler.addServlet(new ServletHolder(new AkkaMistServlet()), "/*");
    server.setHandler(handler)
    server.start()

`ServletContextHandler` is a handler for HTTP requests that supports the
standard [Java servlet API][servlet]. Web Words needs a servlet context to
add `AkkaMistServlet` to it. (Akka HTTP is also known as Akka Mist, for
historical reasons.) `AkkaMistServlet` forwards HTTP requests to a special
actor known as the `RootEndpoint`, which is also created in
[WebServer.scala][webwordswebserver].

By the way, the use of Jetty here is yet another example of seamlessly using
a Java API from Scala.

### Akka HTTP

The `AkkaMistServlet` from Akka HTTP suspends incoming requests using
[Jetty Continuations][jettycontinuations] and forwards each request as a
message to the `RootEndpoint` actor.

In [WebActors.scala][webwordswebactors], Web Words defines its own actors to
handle requests, registering them with `RootEndpoint` in the form of the
following `handlerFactory` partial function:

    private val handlerFactory: PartialFunction[String, ActorRef] = {
        case path if handlers.contains(path) =>
            handlers(path)
        case "/" =>
            handlers("/words")
        case path: String =>
            custom404
    }

    private val handlers = Map(
        "/hello" -> actorOf[HelloActor],
        "/words" -> actorOf(new WordsActor(config)))

    private val custom404 = actorOf[Custom404Actor]

Request messages sent from Akka HTTP are subclasses of `RequestMethod`;
`RequestMethod` wraps the standard `HttpServletRequest` and
`HttpServletResponse`, and you can access the request and response directly
if you like. There are some convenience methods on `RequestMethod` for
common actions such as returning an `OK` status:

    class HelloActor extends Actor {
        override def receive = {
            case get: Get =>
                get OK "hello!"
            case request: RequestMethod =>
                request NotAllowed "unsupported request"
        }
    }

Here `OK` and `NotAllowed` are methods on `RequestMethod` that set a
status code and write out a string as the body of the response.

The action begins in [WordsActor][webwordswordsactor] which generates HTML
for the main `/words` page of the application, after getting an `Index`
object from a [ClientActor][webwordsclientactor] instance:

    val futureGotIndex = client ? GetIndex(url.get.toExternalForm, skipCache)

    futureGotIndex foreach {
        // now we're in another thread, so we just send ourselves
        // a message, don't touch actor state
        case GotIndex(url, indexOption, cacheHit) =>
            self ! Finish(get, url, indexOption, cacheHit, startTime)
    }

[ClientActor.scala][webwordsclientactor] contains the logic to check the
MongoDB cache via [IndexStorageActor][webwordsindexeractor] and kick off an
indexer job when there's a cache miss. When the `ClientActor` replies, the
`WordsActor` sends itself a `Finish` message with the information necessary
to complete the HTTP request.

To handle the `Finish` message, `WordsActor` generates HTML:

    private def handleFinish(finish: Finish) = {
        val elapsed = System.currentTimeMillis - finish.startTime
        finish match {
            case Finish(request, url, Some(index), cacheHit, startTime) =>
                val html = wordsPage(form(url, skipCache = false), results(url, index, cacheHit, elapsed))

                completeWithHtml(request, html)

            case Finish(request, url, None, cacheHit, startTime) =>
                request.OK("Failed to index url in " + elapsed + "ms (try reloading)")
        }
    }

A couple more nice Scala features are illustrated in `handleFinish()`!

 - keywords are allowed for parameters: `form(url, skipCache = false)` is
   much clearer than `form(url, false)`
 - pattern matching lets the code distinguish a `Finish` message with
   `Some(index)` from one with `None`, while simultaneously unpacking the
   fields in the `Finish` message

## Connecting the web process to the indexer with AMQP

Separating Web Words into two processes, a web frontend and a worker process
called `indexer`, makes it easier to manage the deployed application. The
web frontend could in principle serve something useful (at least an error
page) while the indexer is down. On a more complex site, some worker
processes might be optional. You can also scale the two processes separately
as you learn which one will be the bottleneck.

However, having two processes creates a need for communication between
them. [RabbitMQ][rabbitmq], an implementation of the [AMQP standard][amqp],
is conveniently available as a Heroku add-on. AMQP stands for "Advanced
Message Queuing Protocol" and that's what it does: queues messages.

Web Words encapsulates AMQP in two actors,
[WorkQueueClientActor][webwordsworkqueueclient] and
[WorkQueueWorkerActor][webwordsworkqueueworker]. The client actor is used in
the web process and the worker actor in the indexer process. Both are
subclasses of [AbstractWorkQueueActor][webwordsabstractworkqueue] which
contains some shared implementation.

### Akka AMQP module

Akka's AMQP module contains a handy `akka.amqp.rpc` package, which layers a
request-response remote procedure call on top of AMQP. On the server
(worker) side, it creates an "RPC server" which replies to requests:

    override def createRpc(connectionActor: ActorRef) = {
        val serializer =
            new RPC.RpcServerSerializer[WorkQueueRequest, WorkQueueReply](WorkQueueRequest.fromBinary, WorkQueueReply.toBinary)
        def requestHandler(request: WorkQueueRequest): WorkQueueReply = {
            // having to block here is not ideal
            // https://www.assembla.com/spaces/akka/tickets/1217
            (self ? request).as[WorkQueueReply].get
        }
        // the need for poolSize>1 is an artifact of having to block in requestHandler above
        rpcServer = Some(RPC.newRpcServer(connectionActor, rpcExchangeName, serializer, requestHandler, poolSize = 8))
    }

While on the client (web) side, it creates an "RPC client" which sends
requests and receives replies:

    override def createRpc(connectionActor: ActorRef) = {
        val serializer =
            new RPC.RpcClientSerializer[WorkQueueRequest, WorkQueueReply](WorkQueueRequest.toBinary, WorkQueueReply.fromBinary)
        rpcClient = Some(RPC.newRpcClient(connectionActor, rpcExchangeName, serializer))
    }

[WorkQueueClientActor][webwordsworkqueueclient] and
[WorkQueueWorkerActor][webwordsworkqueueworker] are thin wrappers around
these server and client objects.

Akka's AMQP module offers several abstractions in addition to the
`akka.amqp.rpc` package, appropriate for different uses of AMQP.

In Web Words, the web process does not heavily rely on getting a reply to
RPC requests; the idea is that the web process retrieves results directly
from the MongoDB cache. The reply to the RPC request just kicks the web
process and tells it to check the cache immediately. If an RPC request times
out for some reason, but an indexer process did cache a result, a user
pressing reload in their browser should see the newly-cached result.

With multiple indexer processes, RPC requests should be load-balanced across
them.

### Message serialization

To send messages over AMQP you need some kind of serialization; you can use
anything - Java serialization, Google protobufs, or in the Web Words case, a
[cheesy hand-rolled approach][webwordstobinary] that happens to show off
some neat Scala features:

    def toBinary: Array[Byte] = {
        val fields = this.productIterator map { _.toString }
        WorkQueueMessage.packed(this.getClass.getSimpleName :: fields.toList)
    }

Scala case classes automatically extend a trait called `Product`, which is
also extended by tuples (pairs, triples, and so on are tuples). You can walk
over the fields in a case class with `productIterator`, so the above code
serializes a case class by converting all its fields to strings and
prepending the class name to the list. (To be clear, in "real" code you
might want to use a more robust approach.)

On the deserialization side, you can see another nice use of Scala's pattern
matching:

    override def fromBinary(bytes: Array[Byte]) = {
        WorkQueueMessage.unpacked(bytes).toList match {
            case "SpiderAndCache" :: url :: Nil =>
                SpiderAndCache(url)
            case whatever =>
                throw new Exception("Bad message: " + whatever)
        }
    }

The `::` operator ("cons" for the Lisp crowd) joins elements in a list, so
we're matching a list with two elements, where the first one is the string
`"SpiderAndCache"`. You could also write this as:

    case List("SpiderAndCache", url) =>

### Checking AMQP connectivity

In [AMQPCheck.scala][webwordsamqpcheck] you'll find some code that uses
[RabbitMQ's Java API][rabbitmqjava] directly, rather than Akka AMQP. This
code exists for two reasons:

 - it verifies that the AMQP broker exists and is properly configured; once
   Akka AMQP starts, you'll get a deluge of backtraces if the broker is
   missing as Akka continuously tries to recover. The code in
   [AMQPCheck.scala][webwordsamqpcheck] gives you one nice error message.
 - it lets the web process block on startup until the indexer starts up,
   so startup proceeds cleanly without any backtraces.

### More on AMQP

AMQP is an involved topic. RabbitMQ has a
[nice tutorial][amqptutorial] that's worth checking out. You can use the
message queue in many flexible ways.

Heroku makes it simple to experiment and see what happens if you run
multiple instances of the same process, as you architect the relationships
among your processes.

## Caching results in MongoDB

Web Words uses AMQP as a "control channel" to kick the indexer process to
index a new site, and tell a web process when indexing is completed. Actual
data doesn't go via AMQP, however. Instead, it's stored in MongoDB by the
indexer process and retrieved by the web process.

[MongoDB][mongodb] is a convenient solution for caching object-like data. It
stores collections of JSON-like objects (the format is called [BSON][bson]
since it's a compact binary version of JSON). A special feature of MongoDB
called a [capped collection][cappedcollection] is ideal for a cache of such
objects. Capped collections use a fixed amount of storage, or store a fixed
number of objects. When the collection fills up, the least-recently-inserted
objects are discarded, that is, it keeps whatever is newest. Perfect for a
cache! MongoDB is pretty fast, too.

### IndexStorageActor

[IndexStorageActor][webwordsindexstorageactor] encapsulates MongoDB for Web
Words. An `IndexStorageActor` stores `Index` objects: simple.

`IndexStorageActor` uses the [Casbah library][casbah], a Scala-friendly
wrapper around MongoDB's Java driver.

Much of the code in [IndexStorageActor.scala][webwordsindexstorageactor]
deals with converting `Index` objects to `DBObject` objects. This code could
be replaced with a library such as [Salat][salat], but it's done by hand in
Web Words to show how you'd do it manually (and avoid another dependency).

`IndexStorageActor` is an actor pool, extending
[IOBoundActorPool][webwordsioboundpool]. Because Casbah is a blocking API,
each worker in the pool will tie up a thread for the duration of the request
to MongoDB. This can be dangerous; by default, Akka has a maximum number of
threads, and running out of threads could lead to deadlock. At the same
time, you don't want to have too few threads in your IO-bound pool because
you can do quite a bit of IO at once (since it doesn't use up CPU). Tuning
this is an application-specific exercise.

`IndexStorageActor` could override the `upperBound` method to adjust the
maximum size of its actor pool and thus the maximum number of simultaneous
outstanding MongoDB requests.

An asynchronous API would be a better match for Akka, and there's one in
development called [Hammersmith][hammersmith].

### Using a capped collection

[IndexStorageActor][webwordsindexstorageactor]'s use of MongoDB may be
pretty self-explanatory.

To set up a capped collection:

    db.createCollection(cacheName,
                MongoDBObject("capped" -> true,
                    "size" -> sizeBytes,
                    "max" -> maxItems))

To add a new `Index` object to the collection:

    cache.insert(MongoDBObject("url" -> url,
                    "time" -> System.currentTimeMillis().toDouble,
                    "index" -> indexAsDBObject(index)))

To look up an old `Index` object:

    val cursor =
        cache.find(MongoDBObject("url" -> url))
                    .sort(MongoDBObject("$natural" -> -1))
                    .limit(1)

The special key `"$natural"` in the above "sort object" refers to the order
in which objects are naturally positioned on disk. For capped collections,
this is guaranteed to be the order in which objects were inserted. The `-1`
means reverse natural order, so the sort retrieves the newest object first.

## Build and deploy: SBT, the start-script plugin, and ScalaTest

The build for Web Words illustrates:

 - SBT 0.11
    - https://github.com/harrah/xsbt/wiki/
 - xsbt-start-script-plugin
    - https://github.com/typesafehub/xsbt-start-script-plugin
 - testing with ScalaTest
    - http://www.scalatest.org/

Here's a quick tour of each one, as applied to Web Words.

### Simple Build Tool (SBT)

SBT build configurations are themselves written in Scala; you can find the
Web Words build in [project/Build.scala][webwordsbuild]. This is an example
of a ["full" configuration][sbtfull]; there's another (more concise but less
flexible) build file format called ["basic" configuration][sbtbasic]. Full
configurations are `.scala` files while basic configurations are in special
`.sbt` files. While full configurations require more typing, basic
configurations have the downside that you need to start over with a full
configuration if you discover a need for more flexibility.

SBT build files are concerned with lists of _settings_ that control the build.
An SBT build will have a tree of projects, where each project will have its
own list of settings.

In [project/Build.scala][webwordsbuild], you can see there are four
projects; the project called `root` is an aggregation of the `web`,
`indexer`, and `common` projects that contain the actual code.

Here's the definition of the `common` project, which is a library shared
between the other two projects:

    lazy val common = Project("webwords-common",
                           file("common"),
                           settings = projectSettings ++
                           Seq(libraryDependencies ++= Seq(akka, akkaAmqp, asyncHttp, casbahCore)))

According to this configuration,

 - The project is named "webwords-common"; this name will be used to name the
jar if you run `sbt package`, so the prefix `webwords-` is intended to avoid
a jar called `common.jar`.
 - The project will be in the directory `common` (each project directory should
contain a `src/main/scala`, `src/test/scala`, etc. for a Scala project, or
`src/main/java`, `src/main/resources`, and so on).
 - The project's settings will include `projectSettings` (a list of settings
defined earlier in the file to be included in all projects), plus some
library dependencies.

Settings are defined with some special operators. In
[project/Build.scala][webwordsbuild] you will see:

 - `:=` sets a setting to a value
 - `+=` adds a value to a list-valued setting
 - `++=` concatenates a list of values to a list-valued setting

### xsbt-start-script-plugin

Have a look at the [Procfile for Web Words][webwordsprocfile], you'll see it
contains the following:

    web: web/target/start
    indexer: indexer/target/start

The format is trivial:

    NAME OF PROCESS: SHELL CODE TO EXECUTE

Heroku will run the given shell code to create each process. In this case,
the `Procfile` launches a script called `start` created by SBT for each
process.

These scripts are generated by [xsbt-start-script-plugin][startscript] as
part of its `stage` task. `stage` is a naming convention that could be
shared by other plugins and means "prepare the project to be run, in an
environment that deploys source trees rather than packages." In other words,
`stage` does what you want in order to compile and run the application
in-place, using the class files generated during the compilation. While `sbt
package` (built in to SBT) creates a `.jar` and `sbt package-war` (provided
by [xsbt-web-plugin][webplugin]) creates a `.war`, `sbt stage` gives
you something you can execute (from `Procfile` or its non-Heroku equivalent).

If you run `sbt stage` and have a look at the generated `start` script,
you'll see that it's setting up a classpath and specifying which main
class the JVM should run.

The [xsbt-start-script-plugin README][startscript] explains how to use it in
a project, in brief you add its settings to your project, for example
the `indexer` project in Web Words:

    lazy val indexer = Project("webwords-indexer",
                              file("indexer"),
                              settings = projectSettings ++
                              StartScriptPlugin.startScriptForClassesSettings ++
                              Seq(libraryDependencies ++= Seq(jsoup))) dependsOn(common % "compile->compile;test->test")

`startScriptForClassesSettings` defines `stage` to run a main method found
in the project's `.class` files. The plugin can also generate a script to
run `.war` files and `.jar` files, if you'd rather package the project and
launch from a package.

### ScalaTest

It's possible to use `JUnit` with a Scala project, but there are a few
popular Scala-based test frameworks (you can use them for Java projects too,
by the way). Web Words uses [ScalaTest][scalatest], two other options are
[Specs2][specs2] and [ScalaCheck][scalacheck].

[ScalaTest][scalatest] gives you a few choices for how to write test
files. An [example from Web Words][webwordsindexstoragespec] looks something
like this:

    it should "store and retrieve an index" in {
        val storage = newActor
        cacheIndex(storage, exampleUrl, sampleIndex)
        val fetched = fetchIndex(storage, exampleUrl)
        fetched should be(sampleIndex)
        storage.stop
    }

ScalaTest provides a ["domain-specific language" or DSL][dsl] for
testing. The idea is to use Scala's flexibility to define a set of objects
and methods that map naturally to a problem domain, without having to give
up type-safety or write a custom parser. (SBT's configuration API is another
example of a DSL.)

The ScalaTest DSL lets you write:

    it should "store and retrieve an index" in

or

    fetched should be(sampleIndex)

rather than something more stilted.

There are quite a few tests in Web Words, illustrating one way to go
about testing an application. You may find
[TestHttpServer.scala][webwordstesthttp] useful: it embeds Jetty to run a
web server locally. Use this to test HTTP client code.

If you declare a project dependency with `"compile->compile;test->test"`,
then the tests in that project can use code from the dependency's tests.
For example, in [Build.scala][webwordsbuild], the line

    dependsOn(common % "compile->compile;test->test")

enables the `web` and `indexer` projects to use
[TestHttpServer.scala][webwordstesthttp] located in the `common` project.

Often it's useful to define tests in the same package as the code you're
testing. This allows tests to access types and fields that aren't accessible
outside the package.

## Summing it up

A real application has quite a few moving parts. In Web Words, some of those
are traditional Java libraries ([JSoup][jsoup], [Jetty][jetty],
[RabbitMQ Java client][rabbitmqjava], [AsyncHttpClient][asynchttpclient])
while others are shiny new Scala libraries ([Akka][akka], [Casbah][casbah],
[ScalaTest][scalatest]).

Scala and Akka are pragmatic tools to pull the JVM ecosystem together and
write horizontally scalable code, without the dangers of rolling your own
approach to concurrency. Programming in functional style with the actor
model naturally scales out, making these approaches a great fit for cloud
platforms such as Heroku.

### About Typesafe

Typesafe, a company founded by the creators of Scala and Akka, offers the
commercially-supported [Typesafe Stack][stack].  To learn more, or if you
just want to hang out and talk Scala, don't hesitate to look us up at
[typesafe.com][typesafe].


[actorref]: http://akka.io/api/akka/1.2/#akka.actor.ActorRef "ActorRef API docs"

[actors]: http://en.wikipedia.org/wiki/Actor_model "Actor model on Wikipedia"

[akka]: http://akka.io/ "Akka home page"

[amqp]: http://www.amqp.org/confluence/display/AMQP/About+AMQP "About AMQP"

[amqptutorial]: http://www.rabbitmq.com/tutorials/amqp-concepts.html "AMQP concepts tutorial"

[asynchttpclient]: https://github.com/sonatype/async-http-client
"AsyncHttpClient on GitHub"

[bson]: http://bsonspec.org/ "BSON Spec"

[cappedcollection]: http://www.mongodb.org/display/DOCS/Capped+Collections "Capped Collections"

[casbah]: https://github.com/mongodb/casbah "Casbah on GitHub"

[caseclasses]: http://www.scala-lang.org/node/107 "Case Classes intro"

[dsl]: http://www.scala-lang.org/node/1403 "DSLs - a powerful Scala feature"

[erlang]: http://en.wikipedia.org/wiki/Erlang_%28programming_language%29
"Erlang on Wikipedia"

[functional]: http://en.wikipedia.org/wiki/Functional_programming "Functional programming on Wikipedia"

[hammersmith]: https://github.com/bwmcadams/hammersmith "Hammersmith on GitHub"

[herokucommand]: http://devcenter.heroku.com/articles/heroku-command "How to install Heroku"

[imperative]: http://en.wikipedia.org/wiki/Imperative_programming "Imperative programming on Wikipedia"

[jetty]: http://www.eclipse.org/jetty/ "Jetty home page"

[jettycontinuations]: http://wiki.eclipse.org/Jetty/Feature/Continuations  "Jetty Continuations"

[jsoup]: http://jsoup.org "JSoup home page"

[mongodb]: http://www.mongodb.org/ "MongoDB home page"

[mongohq]: http://mongohq.com/ "MongoHQ"

[mongoquick]: http://www.mongodb.org/display/DOCS/Quickstart "MongoDB Quick Start"

[par]:
http://www.scala-lang.org/api/current/scala/collection/parallel/package.html "scala.collection.parallel API docs"

[pimpmylibrary]: http://www.artima.com/weblogs/viewpost.jsp?thread=179766
"Pimp my Library blog post"

[rabbitmq]: http://www.rabbitmq.com/getstarted.html "RabbitMQ Getting Started"

[rabbitmqinstall]: http://www.rabbitmq.com/server.html "RabbitMQ server install"

[rabbitmqjava]: http://www.rabbitmq.com/java-client.html "RabbitMQ Java client home page"

[repo]: https://github.com/typesafehub/webwords/tree/heroku-devcenter "webwords on GitHub"

[salat]: https://github.com/novus/salat "Salat on GitHub"

[sbtbasic]: https://github.com/harrah/xsbt/wiki/Basic-Configuration "Basic Configuration (SBT Wiki)"

[sbteclipse]: https://github.com/typesafehub/sbteclipse "SBT Eclipse integration"

[sbtfull]: https://github.com/harrah/xsbt/wiki/Full-Configuration "Full Configuration (SBT Wiki)"

[scala]: http://www.scala-lang.org/ "Scala home page"

[scalabook]:
http://www.amazon.com/Programming-Scala-Comprehensive-Step---Step/dp/0981531644
"Programming in Scala, Second Edition"

[scalacheck]: https://github.com/rickynils/scalacheck "ScalaCheck on GitHub"

[scalaide]: http://www.scala-ide.org/ "Scala Eclipse Plugin"

[scalatest]: http://www.scalatest.org/ "ScalaTest home page"

[servlet]: http://en.wikipedia.org/wiki/Java_Servlet "Java Servlet on Wikipedia"

[specs2]: http://etorreborre.github.com/specs2/ "Specs2 on GitHub"

[stack]: http://typesafe.com/stack "Typesafe Stack"

[startscript]: https://github.com/typesafehub/xsbt-start-script-plugin "xsbt-start-script-plugin on GitHub"

[toolsupport]:
http://lampsvn.epfl.ch/trac/scala/browser/scala-tool-support/trunk/src "Scala Tool Support SVN repo"

[typesafe]: http://typesafe.com/ "Typesafe"

[webplugin]: https://github.com/siasia/xsbt-web-plugin "xsbt-web-plugin on GitHub"

[webwordsabstractworkqueue]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/AbstractWorkQueueActor.scala
"AbstractWorkQueueActor.scala"

[webwordsakkaconf]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/web/src/main/resources/akka.conf
"web process akka.conf"

[webwordsamqpcheck]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/AMQPCheck.scala "AMQPCheck.scala"

[webwordsbuild]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/project/Build.scala "Build.scala"

[webwordsclientactor]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/ClientActor.scala
"ClientActor.scala"

[webwordscommonpackage]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/package.scala
"webwords-common package.scala"

[webwordsconfig]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/WebWordsConfig.scala
"WebWordsConfig.scala"

[webwordscpuboundpool]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/CPUBoundActorPool.scala "CPUBoundActorPool.scala"

[webwordsindex]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/Index.scala "Index.scala"

[webwordsindexeractor]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/indexer/src/main/scala/com/typesafe/webwords/indexer/IndexerActor.scala "IndexerActor.scala"

[webwordsindexstorageactor]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/IndexStorageActor.scala "IndexStorageActor.scala"

[webwordsindexstoragespec]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/test/scala/com/typesafe/webwords/common/IndexStorageActorSpec.scala "IndexStorageActorSpec.scala"

[webwordsioboundpool]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/IOBoundActorPool.scala "IOBoundActorPool.scala"

[webwordsprocfile]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/Procfile
"Procfile"

[webwordsspideractor]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/indexer/src/main/scala/com/typesafe/webwords/indexer/SpiderActor.scala "SpiderActor.scala"

[webwordstesthttp]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/test/scala/com/typesafe/webwords/common/TestHttpServer.scala "TestHttpServer.scala"

[webwordstobinary]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/AbstractWorkQueueActor.scala#L16
"WorkQueueMessage.toBinary"

[webwordsurlfetcher]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/URLFetcher.scala "URLFetcher.scala"

[webwordswebactors]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/web/src/main/scala/com/typesafe/webwords/web/WebActors.scala "WebActors.scala"

[webwordswebserver]: https://github.com/typesafehub/webwords/blob/heroku-devcenter/web/src/main/scala/com/typesafe/webwords/web/WebServer.scala "WebServer.scala"

[webwordswordsactor]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/web/src/main/scala/com/typesafe/webwords/web/WebActors.scala#L35 "WebServer.scala at class WordsActor"

[webwordsworkqueueclient]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/WorkQueueClientActor.scala "WorkQueueClientActor.scala"

[webwordsworkqueueworker]:
https://github.com/typesafehub/webwords/blob/heroku-devcenter/common/src/main/scala/com/typesafe/webwords/common/WorkQueueWorkerActor.scala
"WorkQueueWorkerActor.scala"

[xsbtsetup]: https://github.com/harrah/xsbt/wiki/Setup "SBT setup"

