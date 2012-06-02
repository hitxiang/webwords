resolvers += Classpaths.typesafeResolver

resolvers += "sbt-idea-repo" at "http://mpeltonen.github.com/maven/"

addSbtPlugin("com.typesafe.startscript" % "xsbt-start-script-plugin" % "0.5.2")

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.0.0")
