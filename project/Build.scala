import sbt._
import Keys._
import com.typesafe.startscript.StartScriptPlugin

object BuildSettings {
    import Dependencies._
    import Resolvers._

    val buildOrganization = "com.typesafe"
    val buildVersion = "2.0-SNAPSHOT"
    val buildScalaVersion = "2.9.1"

    val globalSettings = Seq(
        organization := buildOrganization,
        version := buildVersion,
        scalaVersion := buildScalaVersion,
        scalacOptions += "-deprecation",
        fork in test := true,
        libraryDependencies ++= Seq(slf4jSimpleTest, scalatest),
        resolvers ++= Seq(typesafeRepo, typesafeSnapshotRepo))

    val projectSettings = Defaults.defaultSettings ++ globalSettings
}

object Resolvers {
    val typesafeRepo = "Typesafe Repository" at
        "http://repo.typesafe.com/typesafe/releases/"

    val typesafeSnapshotRepo = "Typesafe Snapshots Repository" at
        "http://repo.typesafe.com/typesafe/snapshots/"
}

object Dependencies {
    val scalatest = "org.scalatest" %% "scalatest" % "1.6.1" % "test"
    val slf4jSimple = "org.slf4j" % "slf4j-simple" % "1.6.2"
    val slf4jSimpleTest = slf4jSimple % "test"

    val playMini = "com.typesafe" %% "play-mini" % "2.0-SNAPSHOT"

    val akka = "com.typesafe.akka" % "akka-actor" % "2.0"

    val akkaRemote = "com.typesafe.akka" % "akka-remote" % "2.0"

    val asyncHttp = "com.ning" % "async-http-client" % "1.6.5"

    val jsoup = "org.jsoup" % "jsoup" % "1.6.1"

    val casbahCore = "com.mongodb.casbah" %% "casbah-core" % "2.1.5-1"
}

object WebWordsBuild extends Build {
    import BuildSettings._
    import Dependencies._

    override lazy val settings = super.settings ++ globalSettings

    lazy val root = Project("webwords",
        file("."),
        settings = projectSettings ++
            Seq(
                StartScriptPlugin.stage in Compile := Unit
            )) aggregate(common, web, indexer)

    lazy val web = Project("webwords-web",
        file("web"),
        settings = projectSettings ++
            Seq(libraryDependencies ++= Seq(playMini),
                mainClass in Compile := Some("play.core.server.NettyServer"),
                mainClass in run := Some("play.core.server.NettyServer")) ++
            StartScriptPlugin.startScriptForClassesSettings
    ) dependsOn(common % "compile->compile;test->test")

    lazy val indexer = Project("webwords-indexer",
        file("indexer"),
        settings = projectSettings ++
            Seq(libraryDependencies ++= Seq(jsoup, slf4jSimple)) ++
            StartScriptPlugin.startScriptForClassesSettings
    ) dependsOn(common % "compile->compile;test->test")

    lazy val common = Project("webwords-common",
        file("common"),
        settings = projectSettings ++
            Seq(libraryDependencies ++= Seq(akka, akkaRemote, asyncHttp, casbahCore)))
}

