import sbt._
import sbt.Keys._
import sbtassembly.Plugin._
import AssemblyKeys._

object RokProxBuild extends Build {

  lazy val rokprox = Project(
    id = "rokprox",
    base = file("."),
    settings = Project.defaultSettings ++ assemblySettings ) settings(
      name := "rokprox",
      organization := "com.github.bigtoast",
      version := "0.2.1",
      scalaVersion := "2.9.2",
      resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
      libraryDependencies ++= Seq (
        "com.typesafe.akka" % "akka-actor" % "2.0.5",
        "com.typesafe.akka" % "akka-remote" % "2.0.5",
        "com.typesafe.akka" % "akka-testkit" % "2.0.5" % "test",
        "org.scalatest" %% "scalatest" % "1.9.1" % "test"
        ),
      mainClass := Some("com.github.bigtoast.rokprox.RokProx"),
      //mainClass in assembly := Some("com.github.bigtoast.RokProx"),
      publishTo := Some(Resolver.file("bigtoast.github.com", file(Path.userHome + "/Projects/BigToast/bigtoast.github.com/repo")))
  )
  
}
