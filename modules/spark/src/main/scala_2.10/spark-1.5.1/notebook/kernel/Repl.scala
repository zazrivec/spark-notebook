package notebook.kernel

import java.io.{StringWriter, PrintWriter, ByteArrayOutputStream}
import java.net.{URLDecoder, JarURLConnection}
import java.util.ArrayList

import scala.collection.JavaConversions
import scala.collection.JavaConversions._
import scala.xml.{NodeSeq, Text}
import scala.util.control.NonFatal
import scala.util.Try

import tools.nsc.Settings
import tools.nsc.interpreter._
import tools.nsc.interpreter.Completion.{Candidates, ScalaCompleter}
import tools.nsc.interpreter.Results.{Incomplete => ReplIncomplete, Success => ReplSuccess, Error}

import tools.jline.console.completer.{ArgumentCompleter, Completer}

import org.apache.spark.repl._

import notebook.front.Widget
import notebook.util.Match
import notebook.kernel._

class Repl(val compilerOpts: List[String], val jars:List[String]=Nil) {
  val LOG = org.slf4j.LoggerFactory.getLogger(classOf[Repl])

  def this() = this(Nil)

  class MyOutputStream extends ByteArrayOutputStream {
    var aop: String => Unit = x => ()

    override def write(i: Int): Unit = {
      // CY: Not used...
      //      orig.value ! StreamResponse(i.toString, "stdout")
      super.write(i)
    }

    override def write(bytes: Array[Byte]): Unit = {
      // CY: Not used...
      //      orig.value ! StreamResponse(bytes.toString, "stdout")
      super.write(bytes)
    }

    override def write(bytes: Array[Byte], off: Int, length: Int): Unit = {
      val data = new String(bytes, off, length)
      aop(data)
      //      orig.value ! StreamResponse(data, "stdout")
      super.write(bytes, off, length)
    }
  }


  private lazy val stdoutBytes = new MyOutputStream
  private lazy val stdout = new PrintWriter(stdoutBytes)

  private var loop:HackSparkILoop = _

  var classServerUri:Option[String] = None

  val interp:org.apache.spark.repl.SparkIMain = {
    val settings = new Settings

    settings.embeddedDefaults[Repl]

    if (!compilerOpts.isEmpty) settings.processArguments(compilerOpts, false)

    // fix for #52
    settings.usejavacp.value = false


    // fix for #52
    val urls: IndexedSeq[String] = {
      import java.net.URLClassLoader
      import java.io.File
      def urls(cl:ClassLoader, acc:IndexedSeq[String]=IndexedSeq.empty):IndexedSeq[String] = {
        if (cl != null) {
          val us = if (!cl.isInstanceOf[URLClassLoader]) {
            acc
          } else {
            acc ++ (cl.asInstanceOf[URLClassLoader].getURLs map { u =>
              val f = new File(u.getFile)
              URLDecoder.decode(f.getAbsolutePath, "UTF8")
            })
          }
          urls(cl.getParent, us)
        } else {
          acc
        }
      }
      val loader = getClass.getClassLoader
      val gurls = urls(loader).distinct//.filter(!_.contains("logback-classic"))//.filter(!_.contains("sbt/"))
      gurls
    }

    val classpath = urls// map {_.toString}
    settings.classpath.value = classpath.distinct.mkString(java.io.File.pathSeparator)

    //bootclasspath → settings.classpath.isDefault = false → settings.classpath is used
    settings.bootclasspath.value += scala.tools.util.PathResolver.Environment.javaBootClassPath
    settings.bootclasspath.value += java.io.File.pathSeparator + settings.classpath.value

    // LOG the classpath
    // debug the classpath → settings.Ylogcp.value = true

    //val i = new HackIMain(settings, stdout)
    loop = new HackSparkILoop(stdout)

    loop.addCps(jars)

    loop.process(settings)
    val i = {
      val l:HackSparkILoop = loop.asInstanceOf[HackSparkILoop]
      l.interpreter
    }
    //i.initializeSynchronous()
    classServerUri = Some(i.classServerUri)
    i.asInstanceOf[org.apache.spark.repl.SparkIMain]
  }

  private lazy val completion = {
    //new JLineCompletion(interp)
    new SparkJLineCompletion(interp)
  }

  private def scalaToJline(tc: ScalaCompleter): Completer = new Completer {
    def complete(_buf: String, cursor: Int, candidates: JList[CharSequence]): Int = {
      val buf   = if (_buf == null) "" else _buf
      val Candidates(newCursor, newCandidates) = tc.complete(buf, cursor)
      newCandidates foreach (candidates add _)
      newCursor
    }
  }

  private lazy val argCompletor = {
    val arg = new ArgumentCompleter(new JLineDelimiter, scalaToJline(completion.completer()))
    // turns out this is super important a line
    arg.setStrict(false)
    arg
  }

  private lazy val stringCompletor = StringCompletorResolver.completor

  private def getCompletions(line: String, cursorPosition: Int) = {
    val candidates = new ArrayList[CharSequence]()
    argCompletor.complete(line, cursorPosition, candidates)
    candidates map { _.toString } toList
  }

  /**
   * Evaluates the given code.  Swaps out the `println` OutputStream with a version that
   * invokes the given `onPrintln` callback everytime the given code somehow invokes a
   * `println`.
   *
   * Uses compile-time implicits to choose a renderer.  If a renderer cannot be found,
   * then just uses `toString` on result.
   *
   * I don't think this is thread-safe (largely because I don't think the underlying
   * IMain is thread-safe), it certainly isn't designed that way.
   *
   * @param code
   * @param onPrintln
   * @return result and a copy of the stdout buffer during the duration of the execution
   */
  def evaluate(code: String, onPrintln: String => Unit = _ => ()): (EvaluationResult, String) = {
    stdout.flush()
    stdoutBytes.reset()

    // capture stdout if the code the user wrote was a println, for example
    stdoutBytes.aop = onPrintln
    val res = Console.withOut(stdoutBytes) {
      interp.interpret(code)
    }
    stdout.flush()
    stdoutBytes.aop = _ => ()

    val result = res match {
      case ReplSuccess =>
        val request:interp.Request = interp.getClass.getMethods.find(_.getName == "prevRequestList").map(_.invoke(interp)).get.asInstanceOf[List[interp.Request]].last
        //val request:Request = interp.prevRequestList.last

        val lastHandler/*: interp.memberHandlers.MemberHandler*/ = request.handlers.last

        try {
          val evalValue = if (lastHandler.definesValue) { // This is true for def's with no parameters, not sure that executing/outputting this is desirable
            // CY: So for whatever reason, line.evalValue attemps to call the $eval method
            // on the class...a method that does not exist. Not sure if this is a bug in the
            // REPL or some artifact of how we are calling it.
            // RH: The above comment may be going stale given the shenanigans I'm pulling below.
            val line = request.lineRep
            val renderObjectCode =
              """object $rendered {
                |  %s
                |  val rendered: _root_.notebook.front.Widget = { %s }
                |  %s
                |}""".stripMargin.format(
                  request.importsPreamble,
                  request.fullPath(lastHandler.definesTerm.get),
                  request.importsTrailer
                )
            if (line.compile(renderObjectCode)) {
              try {
                val classLoader = interp.getClass.getMethods.find(_.getName == "classLoader").map(_.invoke(interp)).get.asInstanceOf[java.lang.ClassLoader]

                val renderedClass2 = Class.forName(
                  line.pathTo("$rendered")+"$", true, classLoader
                )

                val o = renderedClass2.getDeclaredField(interp.global.nme.MODULE_INSTANCE_FIELD.toString).get()

                def iws(o:Any):NodeSeq = {
                  val iw = o.getClass.getMethods.find(_.getName == "$iw")
                  val o2 = iw map { m =>
                    m.invoke(o)
                  }
                  o2 match {
                    case Some(o3) =>
                      iws(o3)
                    case None =>
                      val r = o.getClass.getDeclaredMethod("rendered").invoke(o)
                      val h = r.asInstanceOf[Widget].toHtml
                      h
                  }
                }
                iws(o)
              } catch {
                case e =>
                  e.printStackTrace
                  LOG.error("Ooops, exception in the cell", e)
                  <span style="color:red;">Ooops, exception in the cell: {e.getMessage}</span>
              }
            } else {
              // a line like println(...) is technically a val, but returns null for some reason
              // so wrap it in an option in case that happens...
              Option(line.call("$result")) map { result => Text(try { result.toString } catch { case e => "Fail to `toString` the result: " + e.getMessage }) } getOrElse NodeSeq.Empty
            }
          } else {
            NodeSeq.Empty
          }

          Success(evalValue)
        }
        catch {
          case NonFatal(e) =>
            val ex = new StringWriter()
            e.printStackTrace(new PrintWriter(ex))
            Failure(ex.toString)
        }

      case ReplIncomplete => Incomplete
      case Error          => Failure(stdoutBytes.toString)
    }

    (result, stdoutBytes.toString)
  }

  def addCp(newJars:List[String]) = {
    val requests = interp.getClass.getMethods.find(_.getName == "prevRequestList").map(_.invoke(interp)).get.asInstanceOf[List[interp.Request]]

    val prevCode = requests.map(_.originalLine)
    val jarList = newJars:::jars
    val r = new Repl(compilerOpts, jarList)
    (r, () => prevCode.dropWhile(_.trim != "\"END INIT\"") foreach (c => r.evaluate(c, _ => ())))
  }

  def complete(line: String, cursorPosition: Int): (String, Seq[Match]) = {
    def literalCompletion(arg: String) = {
      val LiteralReg = """.*"([\w/]+)""".r
      arg match {
        case LiteralReg(literal) => Some(literal)
        case _ => None
      }
    }

    // CY: Don't ask to explain why this works. Look at JLineCompletion.JLineTabCompletion.complete.mkDotted
    // The "regularCompletion" path is the only path that is (likely) to succeed
    // so we want access to that parsed version to pull out the part that was "matched"...
    // ...just...trust me.
    val delim = argCompletor.getDelimiter
    val list = delim.delimit(line, cursorPosition)
    val bufferPassedToCompletion = list.getCursorArgument
    val actCursorPosition = list.getArgumentPosition
    val parsed = Parsed.dotted(bufferPassedToCompletion, actCursorPosition) // withVerbosity verbosity
    val matchedText = bufferPassedToCompletion.takeRight(actCursorPosition - parsed.position)

    literalCompletion(bufferPassedToCompletion) match {
      case Some(literal) =>
        // strip any leading quotes
        stringCompletor.complete(literal)
      case None =>
        val candidates = getCompletions(line, cursorPosition)

        (matchedText, if (candidates.size > 0 && candidates.head.isEmpty) {
          List()
        } else {
          candidates.map(Match(_))
        })
    }
  }

  def objectInfo(line: String, position:Int): Seq[String] = {
    // CY: The REPL is stateful -- it isn't until you ask to complete
    // the thing twice does it give you the method signature (i.e. you
    // hit tab twice).  So we simulate that here... (nutty, I know)
    getCompletions(line, position)
    val candidates = getCompletions(line, position)

    if (candidates.size >= 2 && candidates.head.isEmpty) {
      candidates.tail
    } else {
      Seq.empty
    }
  }
}