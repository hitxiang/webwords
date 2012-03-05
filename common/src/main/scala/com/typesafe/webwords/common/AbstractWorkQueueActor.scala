package com.typesafe.webwords.common

import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import akka.actor._

sealed trait WorkQueueMessage {
    self: Product =>

    private[common] def toBinary: Array[Byte] = {
        val fields = this.productIterator map { _.toString }
        WorkQueueMessage.packed(this.getClass.getSimpleName :: fields.toList)
    }
}

object WorkQueueMessage {
    private def stringPack(args: Traversable[String]): String = {
        val encoded = for (a <- args)
            yield URLEncoder.encode(a, "UTF-8")
        encoded.mkString("", ":", "")
    }

    private def stringUnpack(s: String): Traversable[String] = {
        val encoded = s.split(":")
        for (e <- encoded)
            yield URLDecoder.decode(e, "UTF-8")
    }

    private[common] def unpacked(bytes: Array[Byte]): Traversable[String] = {
        stringUnpack(new String(bytes, "UTF-8"))
    }

    private[common] def packed(args: Traversable[String]): Array[Byte] = {
        stringPack(args).getBytes("UTF-8")
    }
}

sealed trait WorkQueueRequest extends WorkQueueMessage {
    self: Product =>
}
case class SpiderAndCache(url: String) extends WorkQueueRequest

object WorkQueueRequest {
/* TODO:ban
    private[common] val toBinary = new AMQP.ToBinary[WorkQueueRequest] {
        override def toBinary(request: WorkQueueRequest) = request.toBinary
    }

    private[common] val fromBinary = new AMQP.FromBinary[WorkQueueRequest] {
        override def fromBinary(bytes: Array[Byte]) = {
            WorkQueueMessage.unpacked(bytes).toList match {
                case "SpiderAndCache" :: url :: Nil =>
                    SpiderAndCache(url)
                case whatever =>
                    throw new Exception("Bad message: " + whatever)
            }
        }
    }
*/
}

sealed trait WorkQueueReply extends WorkQueueMessage {
    self: Product =>
}
case class SpideredAndCached(url: String) extends WorkQueueReply

object WorkQueueReply {
/* TODO:ban
    private[common] val toBinary = new AMQP.ToBinary[WorkQueueReply] {
        override def toBinary(reply: WorkQueueReply) = reply.toBinary
    }

    private[common] val fromBinary = new AMQP.FromBinary[WorkQueueReply] {
        override def fromBinary(bytes: Array[Byte]) = {
            WorkQueueMessage.unpacked(bytes).toList match {
                case "SpideredAndCached" :: url :: Nil =>
                    SpideredAndCached(url)
                case whatever =>
                    throw new Exception("Bad message: " + whatever)
            }
        }
    }
*/
}
