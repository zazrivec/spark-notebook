package org.apache.spark.ui.notebook.front.gadgets

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.util._

import org.apache.spark.SparkContext
import org.apache.spark.scheduler.StageInfo

import play.api.libs.json._


class SparkMonitor(sparkContext:SparkContext, checkInterval:Long = 1000) {

  val connection = notebook.JSBus.createConnection("jobsProgress")

  val listener = new org.apache.spark.ui.jobs.JobProgressListener(sparkContext.getConf)

  sparkContext.listenerBus.addListener(listener)

  def fetchMetrics = {
    listener.synchronized {
      val activeStages = listener.activeStages.values.toSeq
      val completedStages = listener.completedStages.reverse.toSeq
      val failedStages = listener.failedStages.reverse.toSeq
      val now = System.currentTimeMillis

      val activeStagesList = activeStages.sortBy(_.submissionTime).reverse
      val completedStagesList = completedStages.sortBy(_.submissionTime).reverse
      val failedStagesList = failedStages.sortBy(_.submissionTime).reverse

      val stageExtract = (s: StageInfo) => {
        val stageDataOption = listener.stageIdToData.get((s.stageId, s.attemptId))
        stageDataOption.map { stageData =>
          val started = stageData.numActiveTasks
          val completed = stageData.completedIndices.size
          val failed = stageData.numFailedTasks
          val total = s.numTasks
          //Json.obj(
          //  "name" -> s.name,
          //  "details" -> s.details,
          //  "completed" -> completed,
          //  "started" -> started,
          //  "total" -> total,
          //  "failed" -> failed,
          //  "progress" -> s"${completed.toDouble / total * 100}"
          //)
          Json.obj(
            "id" → s.stageId,
            "name" → s.name,
            "completed" → (completed.toDouble / total * 100),
            "time"  → (""+s.submissionTime.map(t => s.completionTime.getOrElse(System.currentTimeMillis) - t)
                                      .map(s => s+"ms")
                                      .getOrElse("N/A"))
          )
        }
      }

      //val mode: String = listener.schedulingMode.map(_.toString).getOrElse("Unknown")
      //val result = Json.obj(
      //  "duration" -> (now - sparkContext.startTime),
      //  "mode" -> mode,
      //  "activeNb" -> activeStages.size,
      //  "completedNb" -> completedStages.size,
      //  "failedNb" -> failedStages.size,
      //  "activeStages" -> (activeStagesList map stageExtract),
      //  "completedStages" -> (completedStagesList map stageExtract)
      //)
      val result = activeStagesList map stageExtract toList;
      val completed = completedStages map stageExtract toList;
      (result ::: completed).collect{case Some(x) => x}
    }
  }

  private[this] var t:Option[Thread] = None

  private[this] def newT ={
    new Thread(){
      override def run =
        while(true) {
          Thread.sleep(1000)
          val m = fetchMetrics
          connection <-- notebook.Connection.just(JsArray(m))
        }
    }
  }

  def start() = {
    t.foreach(_.stop)
    t = Some(newT)
    t.get.start
  }

  def stop() = {
    t.foreach(_.stop)
    t = None
  }

}