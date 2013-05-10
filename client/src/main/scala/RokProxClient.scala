package com.github.bigtoast.rokprox

import sbt._
import java.io.{ File }

class RokProxClient extends xsbti.AppMain {
	def run( configuration :xsbti.AppConfiguration ): xsbti.MainResult = 
		MainLoop.runLogged( initialState( configuration ) )

	def initialState(configuration: xsbti.AppConfiguration): State = {
    val commandDefinitions = hello +: BasicCommands.allBasicCommands
    val commandsToRun = Hello +: "iflast shell" +: configuration.arguments.map(_.trim)
    State( configuration, commandDefinitions, Set.empty, None, commandsToRun, State.newHistory,
       AttributeMap.empty, initialGlobalLogging, State.Continue )
  }

	// defines an example command.  see the Commands page for details.
  val Hello = "hello"
  val hello = Command.command(Hello) { s =>
  	s.log.info("Hello!")
    s
  }

   /** Configures logging to log to a temporary backing file as well as to the console.
   * An application would need to do more here to customize the logging level and
   * provide access to the backing file (like sbt's last command and logLevel setting).*/
   def initialGlobalLogging: GlobalLogging =
      GlobalLogging.initial(MainLogging.globalDefault _, File.createTempFile("hello", "log"))  
}