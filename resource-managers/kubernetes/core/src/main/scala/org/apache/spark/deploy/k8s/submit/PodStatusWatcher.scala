/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.deploy.k8s.submit

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.{Watcher, WatcherException}
import io.fabric8.kubernetes.client.Watcher.Action

import org.apache.spark.deploy.k8s.Config._
import org.apache.spark.deploy.k8s.KubernetesDriverConf
import org.apache.spark.deploy.k8s.KubernetesUtils._
import org.apache.spark.internal.Logging
import org.apache.spark.launcher.{LauncherBackend, SparkAppHandle}

private[k8s] trait PodStatusWatcher extends Watcher[Pod] {
  def watchOrStop(submissionId: String): Boolean
  def reset(): Unit
  def registerLauncherBackend(launcherBackend: LauncherBackend): Unit
}

/**
 * A monitor for the running Kubernetes pod of a Spark application. Status logging occurs on
 * every state change and also at an interval for liveness.
 *
 * @param conf kubernetes driver conf.
 */
private[k8s] class PodStatusWatcherImpl(conf: KubernetesDriverConf)
  extends PodStatusWatcher with Logging {

  private val appId = conf.appId

  private var podCompleted = false

  private var resourceTooOldReceived = false

  private var pod = Option.empty[Pod]

  private var launcherBackend = Option.empty[LauncherBackend]

  private var latestPhase: String = _

  private def phase: String = pod.map(_.getStatus.getPhase).getOrElse("unknown")

  override def reset(): Unit = {
    resourceTooOldReceived = false
  }

  override def registerLauncherBackend(launcherBackend: LauncherBackend): Unit = {
    this.launcherBackend = Option(launcherBackend)
  }

  override def eventReceived(action: Action, pod: Pod): Unit = {
    this.pod = Option(pod)
    notifyStatusChanged()
    action match {
      case Action.DELETED | Action.ERROR =>
        closeWatch()

      case _ =>
        logLongStatus()
        if (hasCompleted) {
          closeWatch()
        }
    }
  }

  override def onClose(e: WatcherException): Unit = {
    logDebug(s"Stopping watching application $appId with last-observed phase $phase")
    if(e != null && e.isHttpGone) {
      resourceTooOldReceived = true
      logDebug(s"Got HTTP Gone code, resource version changed in k8s api: $e")
    } else {
      closeWatch()
    }
  }

  override def onClose(): Unit = {
    logDebug(s"Stopping watching application $appId with last-observed phase $phase")
    closeWatch()
  }

  private def notifyStatusChanged(): Unit = {
    if (phase != latestPhase) {
      latestPhase = phase
      latestPhase match {
        case "Pending" => reportStatusChanged(SparkAppHandle.State.SUBMITTED)
        case "Running" => reportStatusChanged(SparkAppHandle.State.RUNNING)
        case "Succeeded" => reportStatusChanged(SparkAppHandle.State.FINISHED)
        case "Failed" => reportStatusChanged(SparkAppHandle.State.FAILED)
        case "Unknown" => reportStatusChanged(SparkAppHandle.State.UNKNOWN)
        case _ => reportStatusChanged(SparkAppHandle.State.UNKNOWN)
      }
    }
  }

  def reportStatusChanged(state: SparkAppHandle.State): Unit = {
    launcherBackend.foreach(_.setState(state))
  }

  private def logLongStatus(): Unit = {
    logInfo("State changed, new state: " + pod.map(formatPodState).getOrElse("unknown"))
  }

  private def hasCompleted: Boolean = {
    phase == "Succeeded" || phase == "Failed"
  }

  private def closeWatch(): Unit = synchronized {
    podCompleted = true
    this.notifyAll()
  }

  override def watchOrStop(sId: String): Boolean = if (conf.get(WAIT_FOR_APP_COMPLETION)) {
    logInfo(s"Waiting for application ${conf.appName} with submission ID $sId to finish...")
    val interval = conf.get(REPORT_INTERVAL)
    synchronized {
      while (!podCompleted && !resourceTooOldReceived) {
        wait(interval)
        logInfo(s"Application status for $appId (phase: $phase)")
      }
    }

    if(podCompleted) {
      logInfo(
        pod.map { p => s"Container final statuses:\n\n${containersDescription(p)}" }
          .getOrElse("No containers were found in the driver pod."))
      logInfo(s"Application ${conf.appName} with submission ID $sId finished")
    }
    podCompleted
  } else {
    logInfo(s"Deployed Spark application ${conf.appName} with submission ID $sId into Kubernetes")
    // Always act like the application has completed since we don't want to wait for app completion
    true
  }
}