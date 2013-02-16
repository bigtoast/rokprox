import sbt._
import sbt.Keys._

object RokProxBuild extends Build {

  lazy val rokprox = Project(
    id = "rokprox",
    base = file("."),
    settings = Project.defaultSettings ++ Seq(
      name := "rokprox",
      organization := "com.github.bigtoast",
      version := "0.1",
      scalaVersion := "2.9.2",
      resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
      libraryDependencies ++= Seq (
        "com.typesafe.akka" % "akka-actor" % "2.0.5"
        ),
      publishTo := Some(Resolver.file("bigtoast.github.com", file(Path.userHome + "/Projects/BigToast/bigtoast.github.com/repo")))
    )
  )
}
