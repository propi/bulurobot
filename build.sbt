name := "bulurobot"

version := "0.1"

scalaVersion := "2.13.5"

val akkaV = "2.6.8"
val httpV = "10.2.4"

libraryDependencies += "com.typesafe.akka" %% "akka-actor-typed" % akkaV
//libraryDependencies += "com.typesafe.akka" %% "akka-http" % httpV
//libraryDependencies += "com.typesafe.akka" %% "akka-stream" % akkaV
//libraryDependencies += "com.typesafe.akka" %% "akka-stream-typed" % akkaV
//libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json" % httpV
libraryDependencies += "com.typesafe.akka" %% "akka-slf4j" % akkaV
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"
libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.7.25"
libraryDependencies += "com.github.kxbmap" %% "configs" % "0.5.0"
libraryDependencies += "com.sun.mail" % "javax.mail" % "1.6.2"

enablePlugins(PackPlugin)

packMain := Map("main" -> "bulurobot.Main")