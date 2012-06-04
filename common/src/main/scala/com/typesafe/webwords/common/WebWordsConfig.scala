package com.typesafe.webwords.common

import akka.actor.{ActorPath, Address}
import com.typesafe.config.ConfigFactory


/**
 * This class represents our app configuration.
 */
case class WebWordsConfig(indexerPath: ActorPath, mongoURL: Option[String])

object WebWordsConfig {
    def apply(): WebWordsConfig = {
        val conf = ConfigFactory.load()
        val indexerPath = conf.getString("worker.indexer_path")
        val mongoURL = Option(conf.getString("worker.mongodb_url"))
        val config = WebWordsConfig(ActorPath.fromString(indexerPath), mongoURL)
        println("Configuration is: " + config)
        config
    }
}
