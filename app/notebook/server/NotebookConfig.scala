package notebook.server

import java.io.{File, InputStream}
import java.net.URL

import org.apache.commons.io.FileUtils
import play.api.libs.json._
import play.api.{Logger, _}

import scala.collection.JavaConverters._
import scala.util.control.Exception.allCatch


case class NotebookConfig(config: Configuration) {
  me =>

  import play.api.Play.current

  config.getString("notebooks.dir").foreach { confDir =>
    Logger.debug(s"Notebooks directory in the config is referring $confDir. Does it exist? ${new File(confDir).exists}")
  }

  val custom = config.getConfig("notebooks.custom")
  val localRepo = custom.flatMap(_.getString("localRepo"))
  val repos = custom.flatMap(_.getStringList("repos")).map(_.asScala.toList)
  val deps = custom.flatMap(_.getStringList("deps")).map(_.asScala.toList)
  val imports = custom.flatMap(_.getStringList("imports")).map(_.asScala.toList)
  val args = custom.flatMap(_.getStringList("args")).map(_.asScala.toList)
  val sparkConf = custom.flatMap(_.getConfig("sparkConf")).map { c =>
    JsObject(c.entrySet.map { case (k, v) => (k, JsString(v.unwrapped().toString)) }.toSeq)
  }

  val notebooksDir = config.getString("notebooks.dir").map(new File(_)).filter(_.exists)
    .orElse(Option(new File("./notebooks"))).filter(_.exists) // ./bin/spark-notebook
    .getOrElse(new File("../notebooks")) // ./spark-notebook
  Logger.info(s"Notebooks dir is $notebooksDir [at ${notebooksDir.getAbsolutePath}] ")

  val projectName = config.getString("name").getOrElse(notebooksDir.getPath)

  val serverResources = config.getStringList("resources").map(_.asScala).getOrElse(Nil).map(new File(_))

  val tachyonInfo = config.getBoolean("tachyon.enabled").filter(identity).map { _ =>
    TachyonInfo(config.getString("tachyon.url"), config.getString("tachyon.baseDir").getOrElse("/share"))
  }

  val maxBytesInFlight = config.underlying.getBytes("maxBytesInFlight").toInt

  object kernel {
    val config = me.config.getConfig("kernel").getOrElse(Configuration.empty)
    val defauldInitScript = config.getString("default.init").orElse(Some("init.sc")).flatMap {
      init => current.resource("scripts/" + init).map(i => ScriptFromURL(i).toSource)
    }
    val kernelInit = {
      val scripts = config.getStringList("init").map(_.asScala).getOrElse(Nil).map(
        url => ScriptFromURL(new URL(url)))
      defauldInitScript.map { s => s :: scripts.toList }.getOrElse(scripts)
    }
    val initScripts = kernelInit.map(x => (x.name, x.script))
    val compilerArgs = config.getStringList("compilerArgs").map(_.asScala).getOrElse(Nil)
    val vmConfig = config.underlying
  }
}


case class TachyonInfo(url: Option[String] = None, baseDir: String = "/share")

trait Script {
  def name: String

  def script: String
}

case class ScriptFromURL(url: URL) extends Script {
  val name = url.toExternalForm

  def script = {
    var is: InputStream = null
    allCatch.andFinally(if (is != null) is.close()).either {
      is = url.openStream()
      scala.io.Source.fromInputStream(is).getLines().mkString("\n")
    } match {
      case Right(s) => s
      case Left(e) => Logger.warn("Unable to read initscript from %s".format(url), e); ""
    }
  }

  def toSource = ScriptFromSource(url.toExternalForm, script)
}

case class ScriptFromFile(file: File) extends Script {
  val name = file.getAbsolutePath

  def script = {
    allCatch.either(FileUtils.readFileToString(file)) match {
      case Right(s) => s
      case Left(e) => Logger.warn("Unable to read initscript from %s".format(file), e); ""
    }
  }
}

case class ScriptFromSource(name: String, script: String) extends Script
