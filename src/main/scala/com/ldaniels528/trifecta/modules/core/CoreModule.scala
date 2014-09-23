package com.ldaniels528.trifecta.modules.core

import java.io.{File, PrintStream}
import java.util.{Date, TimeZone}

import com.ldaniels528.trifecta.command._
import com.ldaniels528.trifecta.modules.Module
import com.ldaniels528.trifecta.modules.ModuleManager.ModuleVariable
import com.ldaniels528.trifecta.support.avro.AvroReading
import com.ldaniels528.trifecta.util.TxUtils._
import com.ldaniels528.trifecta.vscript.VScriptRuntime.ConstantValue
import com.ldaniels528.trifecta.vscript.{OpCode, Scope, Variable}
import com.ldaniels528.trifecta.{SessionManagement, TrifectaShell, TxConfig, TxRuntimeContext}
import org.apache.commons.io.IOUtils

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.Properties

/**
 * Core Module
 * @author Lawrence Daniels <lawrence.daniels@gmail.com>
 */
class CoreModule(config: TxConfig) extends Module with AvroReading {
  private val out: PrintStream = config.out

  // define the process parsing regular expression
  private val PID_MacOS_r = "^\\s*(\\d+)\\s*(\\d+)\\s*(\\d+)\\s*(\\S+)\\s*(\\S+)\\s*(\\S+)\\s*(\\S+)\\s*(.*)".r
  private val PID_Linux_r = "^\\s*(\\S+)\\s*(\\d+)\\s*(\\d+)\\s*(\\d+)\\s*(\\S+)\\s*(\\S+)\\s*(\\S+)\\s*(\\S+)\\s*(.*)".r
  private val NET_STAT_r = "^\\s*(\\S+)\\s*(\\S+)\\s*(\\S+)\\s*(\\S+)\\s*(\\S+)\\s*(\\S+)\\s*(.*)".r

  override def moduleName = "core"

  override def getCommands(implicit rt: TxRuntimeContext): Seq[Command] = Seq(
    Command(this, "!", executeHistory, UnixLikeParams(Seq("!" -> false, "?" -> false, "index|count" -> false), Nil), help = "Executes a previously issued command"),
    Command(this, "?", help, SimpleParams(Nil, Seq("search-term")), help = "Provides the list of available commands"),
    Command(this, "autoswitch", autoSwitch, SimpleParams(Nil, Seq("state")), help = "Automatically switches to the module of the most recently executed command"),
    Command(this, "avcat", avroCat, SimpleParams(required = Seq("variable")), help = "Displays the contents of a schema variable", promptAware = false),
    Command(this, "avload", avroLoadSchema, UnixLikeParams(Seq("variable" -> true, "schemaPath" -> true)), help = "Loads an Avro schema into memory", promptAware = false),
    Command(this, "cat", cat, SimpleParams(Seq("file"), Nil), help = "Dumps the contents of the given file", promptAware = true),
    Command(this, "cd", changeDir, SimpleParams(Seq("path"), Nil), help = "Changes the local file system path/directory", promptAware = true),
    Command(this, "charset", charSet, SimpleParams(Nil, Seq("encoding")), help = "Retrieves or sets the character encoding"),
    Command(this, "class", inspectClass, SimpleParams(Nil, Seq("action")), help = "Inspects a class using reflection"),
    Command(this, "columns", columnWidthGetOrSet, SimpleParams(Nil, Seq("columnWidth")), help = "Retrieves or sets the column width for message output"),
    Command(this, "debug", debug, UnixLikeParams(Seq("enabled" -> false)), help = "Switches debugging on/off", undocumented = true),
    Command(this, "exit", exit, UnixLikeParams(), help = "Exits the shell"),
    Command(this, "help", help, UnixLikeParams(), help = "Provides the list of available commands"),
    Command(this, "history", listHistory, UnixLikeParams(Seq("count" -> false)), help = "Returns a list of previously issued commands"),
    Command(this, "hostname", hostname, UnixLikeParams(), help = "Returns the name of the host system"),
    Command(this, "jobs", listJobs, UnixLikeParams(), help = "Returns the list of currently running jobs"),
    Command(this, "ls", listFiles, SimpleParams(Nil, Seq("path")), help = "Retrieves the files from the current directory", promptAware = true),
    Command(this, "modules", listModules, UnixLikeParams(), help = "Returns a list of configured modules"),
    Command(this, "pkill", processKill, SimpleParams(Seq("pid0"), Seq("pid1", "pid2", "pid3", "pid4", "pid5", "pid6")), help = "Terminates specific running processes"),
    Command(this, "ps", processList, SimpleParams(Nil, Seq("node", "timeout")), help = "Display a list of \"configured\" running processes (EXPERIMENTAL)"),
    Command(this, "pwd", printWorkingDirectory, SimpleParams(Nil, Nil), help = "Display current working directory"),
    Command(this, "resource", findResource, SimpleParams(Seq("resource-name"), Nil), help = "Inspects the classpath for the given resource"),
    Command(this, "runjava", executeJavaApp, SimpleParams(Seq("jarFile", "className"), (1 to 10).map(n => s"arg$n")), help = "Executes a Java class' main method"),
    Command(this, "scope", listScope, UnixLikeParams(), help = "Returns the contents of the current scope"),
    Command(this, "syntax", syntax, SimpleParams(Seq("command"), Nil), help = "Returns the syntax/usage for a given command"),
    Command(this, "systime", systemTime, UnixLikeParams(), help = "Returns the system time as an EPOC in milliseconds"),
    Command(this, "time", time, UnixLikeParams(), help = "Returns the system time"),
    Command(this, "timeutc", timeUTC, UnixLikeParams(), help = "Returns the system time in UTC"),
    Command(this, "undoc", listUndocumented, UnixLikeParams(), help = "Displays undocumented commands", undocumented = true),
    Command(this, "use", useModule, SimpleParams(Seq("module"), Nil), help = "Switches the active module"),
    Command(this, "version", version, UnixLikeParams(), help = "Returns the Verify application version"),
    Command(this, "wget", httpGet, SimpleParams(required = Seq("url")), help = "Retrieves remote content via HTTP"))

  override def getVariables: Seq[Variable] = Seq(
    Variable("autoSwitching", ConstantValue(Option(true))),
    Variable("columns", ConstantValue(Option(25))),
    Variable("cwd", ConstantValue(Option(new File(".").getCanonicalPath))),
    Variable("debugOn", ConstantValue(Option(false))),
    Variable("encoding", ConstantValue(Option("UTF8")))
  )

  override def prompt: String = cwd

  override def shutdown() = ()

  // load the commands from the modules
  private def commandSet(implicit rt: TxRuntimeContext): Map[String, Command] = rt.moduleManager.commandSet

  /**
   * Retrieves the current working directory
   */
  def cwd: String = config.getOrElse("cwd", ".")

  /**
   * Sets the current working directory
   * @param path the path to set
   */
  def cwd_=(path: String) = config.set("cwd", path)

  /**
   * Automatically switches to the module of the most recently executed command
   * @example autoswitch true
   */
  def autoSwitch(params: UnixLikeArgs): String = {
    params.args.headOption map (_.toBoolean) foreach (config.autoSwitching = _)
    s"auto switching is ${if (config.autoSwitching) "On" else "Off"}"
  }

  /**
   * Displays the contents of an Avro schema variable
   * @example avcat qschema
   */
  def avroCat(params: UnixLikeArgs)(implicit rt: TxRuntimeContext): Option[String] = {
    params.args.headOption map { name =>
      implicit val scope = config.scope
      val decoder = getAvroDecoder(name)
      decoder.schemaString
    }
  }

  /**
   * Loads an Avro schema into memory
   * @example avload qschema "avro/quotes.avsc"
   */
  def avroLoadSchema(params: UnixLikeArgs) {
    // get the variable name, schema and decoder
    val (name, decoder) = params.args match {
      case aName :: aSchemaPath :: Nil => (aName, loadAvroDecoder(aName, aSchemaPath))
      case _ => dieSyntax(params)
    }

    // create the variable and attach it to the scope
    config.scope += Variable(name, new OpCode {
      val value = Some(decoder)

      override def eval(implicit scope: Scope): Option[Any] = value

      override def toString = s"[$name]"
    })
    ()
  }

  /**
   * Displays the contents of the given file
   * @example cat "avro/schema1.avsc"
   */
  def cat(params: UnixLikeArgs): Seq[String] = {
    import scala.io.Source

    // get the file path
    params.args.headOption match {
      case Some(path) => Source.fromFile(expandPath(path)).getLines().toSeq
      case None => dieSyntax(params)
    }
  }

  /**
   * Changes the local file system path/directory
   * @example cd "/home/ldaniels/examples"
   */
  def changeDir(params: UnixLikeArgs): Option[String] = {
    params.args.headOption map {
      case path if path == ".." =>
        cwd.split("[/]") match {
          case a if a.length <= 1 => "/"
          case a =>
            val newPath = a.init.mkString("/")
            if (newPath.trim.length == 0) "/" else newPath
        }
      case path => setupPath(path)
    }
  }

  /**
   * Retrieves or sets the character encoding
   * @example charset "UTF-8"
   */
  def charSet(params: UnixLikeArgs): Either[Unit, String] = {
    params.args.headOption match {
      case Some(newEncoding) => Left(config.encoding = newEncoding)
      case None => Right(config.encoding)
    }
  }

  /**
   * Retrieves or sets the column width for message output
   * @example columns 30
   */
  def columnWidthGetOrSet(params: UnixLikeArgs): Either[Unit, Int] = {
    params.args.headOption match {
      case Some(arg) => Left(config.columns = parseInt("columnWidth", arg))
      case None => Right(config.columns)
    }
  }

  /**
   * Toggles the current debug state
   * @param params the given command line arguments
   * @return the current state ("On" or "Off")
   */
  def debug(params: UnixLikeArgs): String = {
    if (params.args.isEmpty) config.debugOn = !config.debugOn else config.debugOn = params.args.head.toBoolean
    s"debugging is ${if (config.debugOn) "On" else "Off"}"
  }

  /**
   * Executes a Java class' main method
   * @example runjava myJarFile.jar com.shocktrade.test.Tester
   * @return the program's output
   */
  def executeJavaApp(params: UnixLikeArgs): Iterator[String] = {
    val Seq(jarPath, className, _*) = params.args
    runJava(jarPath, className, params.args.drop(2): _*)
  }

  /**
   * Inspects the classpath for the given resource by name
   * Example: resource org/apache/http/message/BasicLineFormatter.class
   */
  def findResource(params: UnixLikeArgs): String = {
    // get the class name (with slashes)
    val path = params.args.head
    val index = path.lastIndexOf('.')
    val resourceName = path.substring(0, index).replace('.', '/') + path.substring(index)

    // determine the resource
    val classLoader = TrifectaShell.getClass.getClassLoader
    val resource = classLoader.getResource(resourceName)
    String.valueOf(resource)
  }

  /**
   * Provides the list of available commands
   * @example help
   */
  def help(params: UnixLikeArgs)(implicit rt: TxRuntimeContext): Seq[CommandItem] = {
    val args = params.args
    commandSet.toSeq filter {
      case (nameA, cmdA) => !cmdA.undocumented && (args.isEmpty || nameA.startsWith(args.head))
    } sortBy (_._1) map {
      case (nameB, cmdB) => CommandItem(nameB, cmdB.module.moduleName, cmdB.help)
    }
  }

  /**
   * "hostname" command - Returns the name of the current host
   */
  def hostname(params: UnixLikeArgs): String = {
    java.net.InetAddress.getLocalHost.getHostName
  }

  /**
   * "wget" command - Retrieves remote content via HTTP
   * @example wget "http://www.example.com/"
   */
  def httpGet(params: UnixLikeArgs): Option[Array[Byte]] = {
    import java.io.ByteArrayOutputStream
    import java.net._

    // get the URL string
    params.args.headOption map { urlString =>
      // download the content
      new URL(urlString).openConnection().asInstanceOf[HttpURLConnection] use { conn =>
        conn.getInputStream use { in =>
          val out = new ByteArrayOutputStream(1024)
          IOUtils.copy(in, out)
          out.toByteArray
        }
      }
    }
  }

  /**
   * Inspects a class using reflection
   * Example: class org.apache.commons.io.IOUtils -m
   */
  def inspectClass(params: UnixLikeArgs): Seq[String] = {
    val args = params.args
    val className = extract(args, 0).getOrElse(getClass.getName).replace('/', '.')
    val action = extract(args, 1) getOrElse "-m"
    val beanClass = Class.forName(className)

    action match {
      case "-m" => beanClass.getDeclaredMethods map (_.toString)
      case "-f" => beanClass.getDeclaredFields map (_.toString)
      case _ => beanClass.getDeclaredMethods map (_.toString)
    }
  }

  /**
   * "!" command - History execution command. This command can either executed a
   * previously executed command by its unique identifier, or list (!?) all previously
   * executed commands.
   * @example !123
   * @example !? 10
   * @example !?
   */
  def executeHistory(params: UnixLikeArgs)(implicit rt: TxRuntimeContext) {
    for {
      command <- params.args match {
        case Nil => SessionManagement.history.last
        case "!" :: Nil => SessionManagement.history.last
        case "?" :: Nil => Some("history")
        case "?" :: count :: Nil => Some(s"history $count")
        case index :: Nil if index.matches("\\d+") => SessionManagement.history(parseInt("history ID", index) - 1)
        case _ => dieSyntax(params)
      }
    } {
      out.println(s">> $command")
      val result = rt.interpret(command)
      rt.handleResult(result)
    }
  }

  /**
   * "exit" command - Exits the shell
   */
  def exit(params: UnixLikeArgs) {
    config.alive = false
    SessionManagement.history.store(config.historyFile)
  }

  /**
   * "ls" - Retrieves the files from the current directory
   */
  def listFiles(params: UnixLikeArgs): Option[Seq[String]] = {
    // get the optional path argument
    val path: String = params.args.headOption map expandPath map setupPath getOrElse cwd

    // perform the action
    Option(new File(path).list) map { files =>
      files map { file =>
        if (file.startsWith(path)) file.substring(path.length) else file
      }
    }
  }

  /**
   * "history" - Retrieves previously executed commands
   */
  def listHistory(params: UnixLikeArgs): Seq[HistoryItem] = {
    val count = params.args.headOption map (parseInt("count", _))
    val lines = SessionManagement.history.getLines(count.getOrElse(-1))
    ((1 to lines.size) zip lines) map {
      case (itemNo, command) => HistoryItem(itemNo, command)
    }
  }

  /**
   * "jobs" - Retrieves the queued jobs
   */
  def listJobs(params: UnixLikeArgs): Seq[JobDetail] = {
    (config.jobs map { case (id, job) =>
      JobDetail(
        jobId = job.jobId,
        status = if (job.task.isCompleted) "Completed" else "Running",
        started = new Date(job.startTime),
        elapsedTimeSecs = (System.currentTimeMillis() - job.startTime) / 1000L)
    }).toSeq
  }

  case class JobDetail(jobId: Int, status: String, started: Date, elapsedTimeSecs: Long)

  /**
   * "modules" command - Returns the list of modules
   * Example: modules
   * @return the list of modules
   */
  def listModules(params: UnixLikeArgs)(implicit rt: TxRuntimeContext): Seq[ModuleItem] = {
    val activeModule = rt.moduleManager.activeModule
    rt.moduleManager.modules.map(m =>
      ModuleItem(m.moduleName, m.getClass.getName, loaded = true, activeModule.exists(_.moduleName == m.moduleName)))
  }

  def listScope(params: UnixLikeArgs)(implicit rt: TxRuntimeContext): Seq[ScopeItem] = {
    implicit val scope = config.scope

    // get the variables (filter out duplicates)
    val varsA: Seq[ModuleVariable] = rt.moduleManager.variableSet
    val varsB: Seq[Variable] = {
      val names: Set[String] = varsA.map(_.variable.name).toSet
      scope.getVariables filterNot (v => names.contains(v.name))
    }

    // build the list of functions, variables, etc.
    (varsA map (v => ScopeItem(v.variable.name, v.moduleName, "variable", v.variable.eval))) ++
      (varsB map (v => ScopeItem(v.name, "", "variable", v.eval))) ++
      (scope.getFunctions map (f => ScopeItem(f.name, "", "function"))) sortBy (_.name)
  }

  /**
   * "undoc" - List undocumented commands
   * @example {{ undoc }}
   */
  def listUndocumented(params: UnixLikeArgs)(implicit rt: TxRuntimeContext): Seq[CommandItem] = {
    val args = params.args
    commandSet.toSeq filter {
      case (nameA, cmdA) => cmdA.undocumented && (args.isEmpty || nameA.startsWith(args.head))
    } sortBy (_._1) map {
      case (nameB, cmdB) => CommandItem(nameB, cmdB.module.moduleName, cmdB.help)
    }
  }

  case class ScopeItem(name: String, module: String, `type`: String, value: Option[_] = None)

  /**
   * "ps" command - Display a list of "configured" running processes
   */
  def processList(params: UnixLikeArgs): Seq[String] = {
    import scala.util.Properties

    // this command only works on Linux
    if (Properties.isMac || Properties.isWin) throw new IllegalStateException("Unsupported platform for this command")

    // get the node
    val args = params.args
    val node = extract(args, 0) getOrElse "."
    val timeout = extract(args, 1) map (_.toInt) getOrElse 60
    out.println(s"Gathering process info from host: ${if (node == ".") "localhost" else node}")

    // parse the process and port mapping data
    val outcome = for {
    // retrieve the process and port map data
      (psData, portMap) <- remoteData(node)

      // process the raw output
      lines = psData map (seq => if (Properties.isMac) seq.tail else seq)

      // filter the data, and produce the results
      result = lines filter (s => s.contains("mysqld") || s.contains("java") || s.contains("python")) flatMap {
        case PID_MacOS_r(user, pid, _, _, time1, _, time2, cmd, fargs) => Some(parsePSData(pid, cmd, fargs, portMap.get(pid)))
        case PID_Linux_r(user, pid, _, _, time1, _, time2, cmd, fargs) => Some(parsePSData(pid, cmd, fargs, portMap.get(pid)))
        case _ => None
      }
    } yield result

    // and let's wait for the result...
    Await.result(outcome, timeout.seconds)
  }

  /**
   * "pkill" command - Terminates specific running processes
   */
  def processKill(params: UnixLikeArgs): String = {
    import scala.sys.process._

    // get the PIDs -- ensure they are integers
    val pidList = params.args map (parseInt("PID", _))

    // kill the processes
    s"kill ${pidList mkString " "}".!!
  }

  /**
   * Parses process data produced by the UNIX "ps" command
   */
  private def parsePSData(pid: String, cmd: String, args: String, portCmd: Option[String]): String = {
    val command = cmd match {
      case s if s.contains("mysqld") => "MySQL Server"
      case s if s.endsWith("java") =>
        args match {
          case a if a.contains("cassandra") => "Cassandra"
          case a if a.contains("kafka") => "Kafka"
          case a if a.contains("mysqld") => "MySQLd"
          case a if a.contains("tesla-stream") => "Verify"
          case a if a.contains("storm nimbus") => "Storm Nimbus"
          case a if a.contains("storm supervisor") => "Storm Supervisor"
          case a if a.contains("storm ui") => "Storm UI"
          case a if a.contains("storm") => "Storm"
          case a if a.contains("/usr/local/java/zookeeper") => "Zookeeper"
          case _ => s"java [$args]"
        }
      case s =>
        args match {
          case a if a.contains("storm nimbus") => "Storm Nimbus"
          case a if a.contains("storm supervisor") => "Storm Supervisor"
          case a if a.contains("storm ui") => "Storm UI"
          case _ => s"$cmd [$args]"
        }
    }

    portCmd match {
      case Some(port) => f"$pid%6s $command <$port>"
      case _ => f"$pid%6s $command"
    }
  }

  /**
   * pwd - Print working directory
   * @param args the given arguments
   * @return the current working directory
   */
  def printWorkingDirectory(args: UnixLikeArgs) = {
    new File(cwd).getCanonicalPath
  }

  /**
   * Retrieves "netstat -ptln" and "ps -ef" data from a remote node
   * @param node the given remote node (e.g. "Verify")
   * @return a future containing the data
   */
  private def remoteData(node: String): Future[(Seq[String], Map[String, String])] = {
    import scala.io.Source
    import scala.sys.process._

    // asynchronously get the raw output from 'ps -ef'
    val psdataF: Future[Seq[String]] = Future {
      Source.fromString((node match {
        case "." => "ps -ef"
        case host => s"ssh -i /home/ubuntu/dev.pem ubuntu@$host ps -ef"
      }).!!).getLines().toSeq
    }

    // asynchronously get the port mapping
    val portmapF: Future[Map[String, String]] = Future {
      // get the lines of data from 'netstat'
      val netStat = Source.fromString((node match {
        case "." if Properties.isMac => "netstat -gilns"
        case "." => "netstat -ptln"
        case host => s"ssh -i /home/ubuntu/dev.pem ubuntu@$host netstat -ptln"
      }).!!).getLines().toSeq.tail

      // build the port mapping
      netStat flatMap {
        case NET_STAT_r(_, _, _, rawport, _, _, pidcmd, _*) =>
          if (pidcmd.contains("java")) {
            val port = rawport.substring(rawport.lastIndexOf(':') + 1)
            val Array(pid, cmd) = pidcmd.trim.split("[/]")
            Some((port, pid, cmd))
          } else None
        case _ => None
      } map {
        case (port, pid, cmd) => pid -> port
      } groupBy (_._1) map {
        case (pid, seq) => (pid, seq.sortBy(_._2).reverse map (_._2) mkString ", ")
      }
    }

    // let's combine the futures
    for {
      pasdata <- psdataF
      portmap <- portmapF
    } yield (pasdata, portmap)
  }

  def syntax(params: UnixLikeArgs)(implicit rt: TxRuntimeContext): Seq[String] = {
    val commandName = params.args.head

    rt.moduleManager.findCommandByName(commandName) match {
      case Some(command) => Seq(s"Description: ${command.help}", s"Usage: ${command.prototype}")
      case None =>
        throw new IllegalStateException(s"Command '$commandName' not found")
    }
  }

  /**
   * "systime" command - Returns the system time as an EPOC in milliseconds
   */
  def systemTime(args: UnixLikeArgs): Long = System.currentTimeMillis()

  /**
   * "time" command - Returns the time in the local time zone
   */
  def time(args: UnixLikeArgs): Date = new Date()

  /**
   * "timeutc" command - Returns the time in the GMT time zone
   */
  def timeUTC(args: UnixLikeArgs): String = {
    import java.text.SimpleDateFormat

    val fmt = new SimpleDateFormat("E MMM dd HH:mm:ss z yyyy")
    fmt.setTimeZone(TimeZone.getTimeZone("GMT"))
    fmt.format(new Date())
  }

  /**
   * "use" command - Switches the active module
   * Example: use kafka
   */
  def useModule(params: UnixLikeArgs)(implicit rt: TxRuntimeContext) {
    val moduleName = params.args.head
    rt.moduleManager.findModuleByName(moduleName) match {
      case Some(module) => rt.moduleManager.activeModule = module
      case None =>
        throw new IllegalArgumentException(s"Module '$moduleName' not found")
    }
  }

  private def setupPath(key: String): String = {
    key match {
      case s if s.startsWith("/") => key
      case s => (if (cwd.endsWith("/")) cwd else cwd + "/") + s
    }
  }

  /**
   * "version" - Returns the application version
   * @return the application version
   */
  def version(args: UnixLikeArgs): String = TrifectaShell.VERSION

  case class CommandItem(command: String, module: String, description: String)

  case class HistoryItem(uid: Int, command: String)

  case class ModuleItem(name: String, className: String, loaded: Boolean, active: Boolean)

}

