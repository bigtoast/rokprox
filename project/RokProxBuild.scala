import sbt._
import sbt.Keys._
import sbtassembly.Plugin._
import AssemblyKeys._


object BuildSettings {
  val buildOrganization = "com.github.bigtoast"

  val buildVersion = "0.2.2"

  val buildScalaVersion = "2.10.2"

  val buildSettings = Project.defaultSettings ++ Seq (
    organization := buildOrganization,
    version      := buildVersion,
    scalaVersion := buildScalaVersion,
    shellPrompt  := ShellPrompt.buildShellPrompt,
    resolvers    += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    resolvers    += "Ticketfly GitHub Repo" at "http://ticketfly.github.com/repo",
    publishTo    := Some(Resolver.file("bigtoast.github.com", file(Path.userHome + "/Projects/BigToast/bigtoast.github.com/repo")))
  )
}

// Shell prompt which show the current project, git branch and build version
// git magic from Daniel Sobral, adapted by Ivan Porto Carrero
object ShellPrompt {

  object devnull extends ProcessLogger {
    def info (s: => String) {}
    def error (s: => String) { }
    def buffer[T] (f: => T): T = f
  }

  val current = """\*\s+([^\s]+)""".r

  def gitBranches = ("git branch --no-color" lines_! devnull mkString)

  val buildShellPrompt = {
    (state: State) => {
      val currBranch = current findFirstMatchIn gitBranches map (_ group(1)) getOrElse "-"
      val currProject = Project.extract (state).currentProject.id
      "%s:%s:%s> ".format (currBranch, currProject, BuildSettings.buildVersion)
    }
  }

}

object RokProxBuild extends Build {

  import BuildSettings._

  val distTask    = TaskKey[Unit]("dist", "generate distribution archive")
  val distLibJars = TaskKey[Seq[File]]("dist-lib-jars", "jars to include in dist lib dir")
  val distScripts = TaskKey[File]("dist-script-dir", "scripts for the dist bin folder")
  val distFolder  = TaskKey[File]("dist-folder", "target folder to contain the dist")

  val deps = Seq (
        "com.ticketfly"     % "pillage-core"    % "56.0",
        "com.typesafe.akka" %% "akka-actor"      % "2.2.0",
        "com.typesafe.akka" %% "akka-remote"     % "2.2.0",
        "com.typesafe.akka" %% "akka-testkit"    % "2.2.0" % "test",
        "org.scalatest"     %% "scalatest"      % "1.9.1" % "test",
        "junit" % "junit"   % "4.9"             % "test",
        "com.novocode"      % "junit-interface" % "0.8"   % "test->default"
        )

  lazy val rokprox = Project(
    id       = "rokprox",
    base     = file("."),
    settings = buildSettings ).aggregate(core, client)

  lazy val core = Project(
    id       = "core",
    base     = file("core"),
    settings = buildSettings ++ assemblySettings ).settings(
      name      := "rokprox",
      mainClass := Some("com.github.bigtoast.rokprox.RokProx"),
      libraryDependencies ++= deps
    )

  lazy val client = Project( id = "client", base = file("client"), settings = buildSettings ++ assemblySettings ).settings(
      libraryDependencies += "org.scala-sbt" % "command"     % "0.12.0",
      libraryDependencies += "org.scala-sbt" % "task-system" % "0.12.0",
      mainClass := Some("com.github.bigtoast.rokprox.RokProxClient"),
      // task for creating distribution ( directory with jars, scripts etc.. )
      distLibJars <<= ( outputPath in assembly ) map { ( p :File ) => (p :: Nil) },
      distScripts := file( "script" ),
      distFolder <<= ( target ) map { ( trgt :File ) => trgt / ("rokprox-" + BuildSettings.buildVersion) },

      distTask <<= (assembly, distFolder, distScripts, distLibJars ) map {
        ( oneJar :File, folder :File, scripts :File , jars :Seq[File]) =>
          IO.createDirectory( folder )
          IO.createDirectory( folder / "lib" )
          IO.createDirectory( folder / "bin" )
          IO.createDirectory( folder / "doc" )
          IO.copy( (scripts * "*").get.map { f => (f , folder / "bin" / f.getName ) } )
          IO.copy( jars map { jar => (jar, folder / "lib" / jar.getName) } )
          ( folder / "bin" ).listFiles foreach { _.setExecutable( true, false ) }
      }
    ).dependsOn(core)

}
