package controllers

import java.io.File
import java.net.URLDecoder
import java.util.UUID

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import notebook.NBSerializer.Metadata
import notebook._
import notebook.server._
import play.api.Play.current
import play.api._
import play.api.http.HeaderNames
import play.api.libs.iteratee.Concurrent.Channel
import play.api.libs.iteratee._
import play.api.libs.json._
import play.api.mvc._
import utils.AppUtils
import utils.Const.UTF_8

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.language.postfixOps
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

case class Crumb(url: String = "", name: String = "")

case class Breadcrumbs(home: String = "/", crumbs: List[Crumb] = Nil)


object Application extends Controller {

  private lazy val config = AppUtils.config
  private lazy val nbm = AppUtils.nbm
  private val kernelIdToCalcService = collection.mutable.Map[String, CalcWebSocketService]()
  private val clustersActor = kernelSystem.actorOf(Props(NotebookClusters(AppUtils.clustersConf)))

  private implicit def kernelSystem: ActorSystem = AppUtils.kernelSystem

  private implicit val GetClustersTimeout = Timeout(60 seconds)

  val project = nbm.name
  val base_project_url = current.configuration.getString("application.context").getOrElse("/")
  val base_kernel_url = "/"
  val base_observable_url = "observable"
  val read_only = false.toString
  //  TODO: Ugh...
  val terminals_available = false.toString // TODO

  def configTree() = Action {
    Ok(Json.obj())
  }

  def configCommon() = Action {
    Ok(Json.obj())
  }

  def configNotebook() = Action {
    Ok(Json.obj())
  }

  private val kernelDef = Json.parse(
    s"""
    |{
    |  "kernelspecs": {
    |    "spark": {
    |      "name": "spark",
    |      "resources": {},
    |      "spec" : {
    |        "language": "scala",
    |        "display_name": "Scala [${notebook.BuildInfo.scalaVersion}] Spark [${notebook.BuildInfo.xSparkVersion}] Hadoop [${notebook.BuildInfo.xHadoopVersion}] ${if (notebook.BuildInfo.xWithHive) " {Hive ✓}" else ""} ${if (notebook.BuildInfo.xWithParquet) " {Parquet ✓}" else ""}",
    |        "language_info": {
    |          "name" : "scala",
    |          "file_extension" : "scala",
    |          "codemirror_mode" : "text/x-scala"
    |        }
    |      }
    |    }
    |  }
    |}
    |""".stripMargin.trim
  )

  def kernelSpecs() = Action {
    Ok(kernelDef)
  }

  private[this] def newSession(kernelId: Option[String] = None,
    notebookPath: Option[String] = None) = {
    val existing = for {
      path <- notebookPath
      (id, kernel) <- KernelManager.atPath(path)
    } yield (id, kernel, kernelIdToCalcService(id))

    val (kId, kernel, service) = existing.getOrElse {
      Logger.info(s"Starting kernel/session because nothing for $kernelId and $notebookPath")

      val kId = kernelId.getOrElse(UUID.randomUUID.toString)
      val compilerArgs = config.kernel.compilerArgs.toList
      val initScripts = config.kernel.initScripts.toList

      val r = Reads.map[JsValue]

      // Load the notebook → get the metadata
      val md: Option[Metadata] = for {
        p <- notebookPath
        n <- nbm.load(p)
        m <- n.metadata
      } yield m

      val customLocalRepo: Option[String] = md.flatMap(_.customLocalRepo)

      val customRepos: Option[List[String]] = md.flatMap(_.customRepos)

      val customDeps: Option[List[String]] = md.flatMap(_.customDeps)

      val customImports: Option[List[String]] = md.flatMap(_.customImports)

      val customArgs: Option[List[String]] = md.flatMap(_.customArgs)

      val customSparkConf: Option[Map[String, String]] = for {
        m <- md
        c <- m.customSparkConf
        _ = Logger.info("customSparkConf >> " + c)
        map <- r.reads(c).asOpt
      } yield map.map {
        case (k, a@JsArray(v))   => k → a.toString
        case (k, JsBoolean(v))   => k → v.toString
        case (k, JsNull)         => k → "null"
        case (k, JsNumber(v))    => k → v.toString
        case (k, o@JsObject(v))  => k → o.toString
        case (k, JsString(v))    => k → v
        case (k, v:JsUndefined)  => k → s"Undefined: ${v.error}"
      }

      val kernel = new Kernel(config.kernel.config.underlying,
                              kernelSystem,
                              kId,
                              notebookPath,
                              customArgs)
      KernelManager.add(kId, kernel)

      val service = new CalcWebSocketService(kernelSystem,
        md.map(_.name).getOrElse("Spark Notebook"),
        customLocalRepo,
        customRepos,
        customDeps,
        customImports,
        customArgs,
        customSparkConf,
        initScripts,
        compilerArgs,
        kernel.remoteDeployFuture,
        config.tachyonInfo
      )
      kernelIdToCalcService += kId -> service
      (kId, kernel, service)
    }

    // todo add MD?
    Json.parse(
      s"""
         |{
         |"id": "$kId",
         |"name": "spark",
         |"language_info": {
         |  "name" : "Scala",
         |  "file_extension" : "scala",
         |  "codemirror_mode" : "text/x-scala"
         |}
         |}
         |""".stripMargin.trim
    )
  }

  def createSession() = Action(parse.tolerantJson) /* → posted as urlencoded form oO */ { request =>
    val json: JsValue = request.body
    val kernelId = Try((json \ "kernel" \ "id").as[String]).toOption
    val notebookPath = Try((json \ "notebook" \ "path").as[String]).toOption
    val k = newSession(kernelId, notebookPath)
    Ok(Json.obj("kernel" → k))
  }

  def sessions() = Action {
    Ok(JsArray(kernelIdToCalcService.keys
      .map { k =>
      KernelManager.get(k).map(l => (k, l))
    }.collect {
      case Some(x) => x
    }.map { case (k, kernel) =>
      val path: String = kernel.notebookPath.getOrElse(s"KERNEL '$k' SHOULD HAVE A PATH ACTUALLY!")
      Json.obj(
        "notebook" → Json.obj("path" → path),
        "id" → k
      )
    }.toSeq)
    )
  }


  def profiles() = Action.async {
    implicit val ec = kernelSystem.dispatcher
    (clustersActor ? NotebookClusters.Profiles).map { case all: List[JsObject] =>
      Ok(JsArray(all))
    }
  }

  def clusters() = Action.async {
    implicit val ec = kernelSystem.dispatcher
    (clustersActor ? NotebookClusters.All).map { case all: List[JsObject] =>
      Ok(JsArray(all))
    }
  }

  /**
   * add a spark cluster by json meta
   */
  def addCluster() = Action.async(parse.tolerantJson) { request =>
    val json = request.body
    implicit val ec = kernelSystem.dispatcher
    json match {
      case o: JsObject =>
        (clustersActor ? NotebookClusters.Add((json \ "name").as[String], o)).map { case cluster: JsObject =>
          Ok(cluster)
        }
      case _ => Future {
        BadRequest("Add cluster needs an object, got: " + json)
      }
    }
  }
  /**
   * add a spark cluster by json meta
   */
  def deleteCluster(clusterName:String) = Action.async { request =>
      Logger.debug("Delete a cluster")
      implicit val ec = kernelSystem.dispatcher
      (clustersActor ? NotebookClusters.Remove(clusterName, null)).map{ item => Ok(Json.obj("result" → s"Cluster $clusterName deleted"))}
  }

  def contents(tpe: String, uri: String = "/") = Action { request =>
    val path = URLDecoder.decode(uri, UTF_8)
    val lengthToRoot = config.notebooksDir.getAbsolutePath.length
    def dropRoot(f: java.io.File) = f.getAbsolutePath.drop(lengthToRoot).dropWhile(_ == '/')
    val baseDir = new java.io.File(config.notebooksDir, path)

    if (tpe == "directory") {
      val content = Option(baseDir.listFiles).getOrElse(Array.empty).map { f =>
        val n = f.getName
        if (f.isFile && n.endsWith(".snb")) {
          Json.obj(
            "type" -> "notebook",
            "name" -> n.dropRight(".snb".length),
            "path" -> dropRoot(f) //todo → build relative path
          )
        } else if (f.isFile) {
          Json.obj(
            "type" -> "file",
            "name" -> n,
            "path" -> dropRoot(f) //todo → build relative path
          )
        } else {
          Json.obj(
            "type" -> "directory",
            "name" -> n,
            "path" -> dropRoot(f) //todo → build relative path
          )
        }
      }
      Ok(Json.obj("content" → content))
    } else if (tpe == "notebook") {
      Logger.debug("content: " + path)
      val name = if (path.endsWith(".snb")) path.dropRight(".snb".length) else path
      getNotebook(name, path, "json")
    } else {
      BadRequest("Dunno what to do with contents for " + tpe + "at " + path)
    }
  }

  def createNotebook(p: String, custom: JsObject, name:Option[String]) = {
    val path = URLDecoder.decode(p, UTF_8)
    Logger.info(s"Creating notebook at $path")
    val customLocalRepo = Try((custom \ "customLocalRepo").as[String]).toOption.map(_.trim()).filterNot(_.isEmpty)
    val customRepos = Try((custom \ "customRepos").as[List[String]]).toOption.filterNot(_.isEmpty)
    val customDeps = Try((custom \ "customDeps").as[List[String]]).toOption.filterNot(_.isEmpty)
    val customImports = Try((custom \ "customImports").as[List[String]]).toOption.filterNot(_.isEmpty)
    val customArgs = Try((custom \ "customArgs").as[List[String]]).toOption.filterNot(_.isEmpty)

    val customMetadata = (for {
      j <- Try(custom \ "customSparkConf") if j.isInstanceOf[JsObject]
    } yield j.asInstanceOf[JsObject]).toOption

    val fpath = nbm.newNotebook(
      path,
      customLocalRepo orElse config.localRepo,
      customRepos orElse config.repos,
      customDeps orElse config.deps,
      customImports orElse config.imports,
      customArgs orElse config.args,
      customMetadata orElse config.sparkConf,
      name)
    Try(Redirect(routes.Application.contents("notebook", fpath)))
  }

  def copyingNb(fp: String) = {
    val fromPath = URLDecoder.decode(fp, UTF_8)
    Logger.info("Copying notebook:" + fromPath)
    val np = nbm.copyNotebook(fromPath)
    Try(Ok(Json.obj("path" → np)))
  }

  def newNotebook(path: String, tryJson: Try[JsValue]) = {
    def findkey[T](x: JsValue, k: String)(init: Option[T])(implicit m: ClassTag[T]): Try[T] =
      (x \ k) match {
        case j: JsUndefined => Failure(new IllegalArgumentException("No " + k))
        case JsNull => init.map(x => Success(x)).getOrElse(Failure(new IllegalStateException("Got JsNull ")))
        case o if m.runtimeClass == o.getClass => Success(o.asInstanceOf[T])
        case x => Failure(new IllegalArgumentException("Bad type: " + x))
      }

    lazy val custom = for {
      x <- tryJson
      t <- findkey[JsObject](x, "custom")(Some(Json.obj()))
      n <- createNotebook(path, t, findkey[JsString](x, "name")(None).toOption.map(_.value))
    } yield n

    lazy val copyFrom = for {
      x <- tryJson
      t <- findkey[JsString](x, "copy_from")(Some(JsString("")))
      n <- copyingNb(t.value)
    } yield n

    custom orElse copyFrom
  }

  def newDirectory(path: String, name:String) = {
    Logger.info("New dir: " + path)
    val base = new File(config.notebooksDir, path)
    val parent = base
    val newDir = new File(parent, name)
    newDir.mkdirs()
    Try(Ok(Json.obj("path" → newDir.getAbsolutePath.drop(parent.getAbsolutePath.length))))
  }

  def newFile(path: String) = {
    Logger.info("New file:" + path)
    val base = new File(config.notebooksDir, path)
    val parent = base.getParentFile
    val newF = new File(parent, "file")
    newF.createNewFile()
    Try(Ok(Json.obj("path" → newF.getAbsolutePath.drop(parent.getAbsolutePath.length))))
  }

  def newContent(p: String = "/") = Action(parse.tolerantText) { request =>
    val path = URLDecoder.decode(p, UTF_8)
    val text = request.body
    val tryJson = Try(Json.parse(request.body))

    tryJson.flatMap { json =>
      (json \ "type").as[String] match {
        case "directory" => newDirectory(path, (json \ "name").as[String])
        case "notebook" => newNotebook(path, tryJson)
        case "file" => newFile(path)
      }
    }.get
  }

  def openNotebook(p: String) = Action { implicit request =>
    val path = URLDecoder.decode(p, UTF_8)
    Logger.info(s"View notebook '$path'")
    val wsPath = base_project_url match {
      case "/" => "/ws"
      case x if x.endsWith("/") => x + "ws"
      case x => x + "/ws"
    }
    def ws_url(path: Option[String] = None) = {
      s"""
         |window.notebookWsUrl = function() {
         |return ((window.location.protocol=='https:') ? 'wss' : 'ws')+'://'+window.location.host+'$wsPath${path.map(x => "/" + x).getOrElse("")}'
         |};
      """.stripMargin.replaceAll("\n", " ")
    }

    Ok(views.html.notebook(
      project + ":" + path,
      project,
      Map(
        "base-url" -> base_project_url,
        "ws-url" -> ws_url(),
        "base-project-url" -> base_project_url,
        "base-kernel-url" -> base_kernel_url,
        "base-observable-url" -> ws_url(Some(base_observable_url)),
        "read-only" -> read_only,
        "notebook-name" -> nbm.name,
        "notebook-path" -> path,
        "notebook-writable" -> "true"
      ),
      Some("notebook")
    ))
  }

  private[this] def closeKernel(kernelId: String) = {
    kernelIdToCalcService -= kernelId

    KernelManager.get(kernelId).foreach { k =>
      Logger.info(s"Closing kernel $kernelId")
      k.shutdown()
      KernelManager.remove(kernelId)
    }
  }

  def openKernel(kernelId: String, sessionId: String) = ImperativeWebsocket.using[JsValue](
    onOpen = channel => WebSocketKernelActor.props(channel, kernelIdToCalcService(kernelId), sessionId),
    onMessage = (msg, ref) => ref ! msg,
    onClose = ref => {
      // try to not close the kernel to allow long live sessions
      // closeKernel(kernelId)
      Logger.info(s"Closing websockets for kernel $kernelId")
      ref ! akka.actor.PoisonPill
    }
  )

  def terminateKernel(kernelId: String) = Action { request =>
    closeKernel(kernelId)
    Ok(s"""{"$kernelId": "closed"}""")
  }

  def restartKernel(kernelId: String) = Action(parse.tolerantJson) { request =>
    val k = KernelManager.get(kernelId)
    closeKernel(kernelId)
    val p = (request.body \ "notebook_path").as[String]
    val path = URLDecoder.decode(p, UTF_8)
    val notebookPath = k.flatMap(_.notebookPath).getOrElse(p)
    Ok(newSession(notebookPath = Some(notebookPath)))
  }

  def listCheckpoints(snb: String) = Action { request =>
    Ok(Json.parse(
      """
        |[
        | { "id": "TODO", "last_modified": "2015-01-02T13:22:01.751Z" }
        |]
        | """.stripMargin.trim
    ))
  }

  def saveCheckpoint(snb: String) = Action { request =>
    //TODO
    Ok(Json.parse(
      """
        |[
        | { "id": "TODO", "last_modified": "2015-01-02T13:22:01.751Z" }
        |]
        | """.stripMargin.trim
    ))
  }

  def renameNotebook(p: String) = Action(parse.tolerantJson) { request =>
    val path = URLDecoder.decode(p, UTF_8)
    val notebook = (request.body \ "path").as[String]
    Logger.info("RENAME → " + path + " to " + notebook)
    try {
      val (newname, newpath) = nbm.rename(path, notebook)

      KernelManager.atPath(path).foreach { case (_, kernel) =>
        kernel.moveNotebook(newpath)
      }

      Ok(Json.obj(
        "type" → "notebook",
        "name" → newname,
        "path" → newpath
      ))
    } catch {
      case _: NotebookExistsException => Conflict
    }
  }

  def saveNotebook(p: String) = Action(parse.tolerantJson(config.maxBytesInFlight)) {
    request =>
      val path = URLDecoder.decode(p, UTF_8)
      Logger.info("SAVE → " + path)
      val notebook = NBSerializer.fromJson(request.body \ "content")
      try {
        val (name, savedPath) = nbm.save(path, notebook, overwrite = true)

        Ok(Json.obj(
          "type" → "notebook",
          "name" → name,
          "path" → savedPath
        ))
      } catch {
        case _: NotebookExistsException => Conflict
      }
  }

  def deleteNotebook(p: String) = Action { request =>
    val path = URLDecoder.decode(p, UTF_8)
    Logger.info("DELETE → " + path)
    try {
      nbm.deleteNotebook(path)

      Ok(Json.obj(
        "type" → "notebook",
        "path" → path
      ))
    } catch {
      case _: NotebookExistsException => Conflict
    }
  }

  def dlNotebookAs(p: String, format: String) = Action {
    val path = URLDecoder.decode(p, UTF_8)
    Logger.info("DL → " + path + " as " + format)
    getNotebook(path.dropRight(".snb".length), path, format, dl = true)
  }

  def dash(p: String = base_kernel_url) = Action {
    val path = URLDecoder.decode(p, UTF_8)
    Logger.debug("DASH → " + path)
    Ok(views.html.projectdashboard(
      nbm.name,
      project,
      Map(
        "project" → project,
        "base-project-url" → base_project_url,
        "base-kernel-url" → base_kernel_url,
        "read-only" → read_only,
        "base-url" → base_project_url,
        "notebook-path" → path,
        "terminals-available" → terminals_available
      ),
      Breadcrumbs(
        "/",
        path.split("/").toList.scanLeft(("", "")) {
          case ((accPath, accName), p) => (accPath + "/" + p, p)
        }.tail.map { case (p, x) =>
          Crumb(controllers.routes.Application.dash(p.tail).url, x)
        }
      ),
      Some("dashboard")
    ))
  }

  def openObservable(contextId: String) = ImperativeWebsocket.using[JsValue](
    onOpen = channel => WebSocketObservableActor.props(channel, contextId),
    onMessage = (msg, ref) => ref ! msg,
    onClose = ref => {
      Logger.info(s"Closing observable $contextId")
      ref ! akka.actor.PoisonPill
    }
  )

  def getNotebook(name: String, path: String, format: String, dl: Boolean = false) = {
    try {
      Logger.debug(s"getNotebook: name is '$name', path is '$path' and format is '$format'")
      val response = nbm.getNotebook(path).map { case (lastMod, nbname, data, fpath) =>
        format match {
          case "json" =>
            val j = Json.parse(data)
            val json = if (!dl) {
              Json.obj(
                "content" → j,
                "name" → nbname,
                "path" → fpath, //FIXME
                "writable" -> true //TODO
              )
            } else {
              j
            }
            Ok(json).withHeaders(
              HeaderNames.CONTENT_DISPOSITION → s"""attachment; filename="$path" """,
              HeaderNames.CONTENT_ENCODING → "UTF-8",
              HeaderNames.LAST_MODIFIED → lastMod
            )
          case "scala" =>
            val nb = NBSerializer.fromJson(Json.parse(data))
            val code = nb.cells.map { cells =>
              val cs = cells.collect {
                case NBSerializer.CodeCell(md, "code", i, Some("scala"), _, _) => i
                case NBSerializer.CodeCell(md, "code", i, None, _, _) => i
              }
              val fc = cs.map(_.split("\n").map { s => s"  $s" }.mkString("\n")).mkString("\n\n  /* ... new cell ... */\n\n").trim
              val code = s"""
              |object Cells {
              |  $fc
              |}
              """.stripMargin
              code
            }.getOrElse("//NO CELLS!")

            Ok(code).withHeaders(
              HeaderNames.CONTENT_DISPOSITION → s"""attachment; filename="$name.scala" """,
              HeaderNames.LAST_MODIFIED → lastMod
            )
          case _ => InternalServerError(s"Unsupported format $format")
        }
      }

      response getOrElse NotFound(s"Notebook '$name' not found at $path.")
    } catch {
      case e: Exception =>
        Logger.error("Error accessing notebook [%s]".format(name), e)
        InternalServerError
    }
  }

  // docker
  val docker /*:Option[tugboat.Docker]*/ = None // SEE dockerlist branch! → still some issues due to tugboat

  def dockerAvailable = Action {
    Ok(Json.obj("available" → docker.isDefined)).withHeaders(
      HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN -> "*",
      HeaderNames.ACCESS_CONTROL_ALLOW_METHODS -> "GET, POST, PUT, DELETE, OPTIONS",
      HeaderNames.ACCESS_CONTROL_ALLOW_METHODS -> "Accept, Origin, Content-type",
      HeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS -> "true"
    )
  }

  def dockerList = TODO

  // SEE dockerlist branch! → still some isues due to tugboat
  // util
  object ImperativeWebsocket {

    def using[E: WebSocket.FrameFormatter](
      onOpen: Channel[E] => ActorRef,
      onMessage: (E, ActorRef) => Unit,
      onClose: ActorRef => Unit,
      onError: (String, Input[E]) => Unit = (e: String, _: Input[E]) => Logger.error(e)
    ): WebSocket[E, E] = {
      implicit val sys = kernelSystem.dispatcher

      val promiseIn = Promise[Iteratee[E, Unit]]()

      val out = Concurrent.unicast[E](
        onStart = channel => {
          val ref = onOpen(channel)
          val in = Iteratee.foreach[E] { message =>
            onMessage(message, ref)
          } map (_ => onClose(ref))
          promiseIn.success(in)
        },
        onError = onError
      )

      WebSocket.using[E](_ => (Iteratee.flatten(promiseIn.future), out))
    }
  }

}
