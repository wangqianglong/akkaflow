package com.kent.workflow.node.action

import com.kent.workflow.node.NodeInstance

import scala.sys.process.ProcessLogger
import com.kent.main.{Master, Worker}
import com.kent.pub.Event._

import scala.sys.process._
import java.util.Date
import java.io.PrintWriter
import java.io.File

import com.kent.util.{FileUtil, ParamHandler, Util}
import akka.pattern.{ask, pipe}

import scala.concurrent.ExecutionContext.Implicits.global
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.Await
import com.kent.workflow.node.action.DataMonitorNode.SourceType
import com.kent.workflow.node.action.DataMonitorNode.SourceType._
import java.sql.Connection
import java.sql.Statement
import java.sql.DriverManager

import com.kent.workflow.node.action.DataMonitorNode._
import java.sql.ResultSet

import com.kent.pub.db.DBLink.DatabaseType._
import com.kent.pub.db.MysqlOpera
import com.kent.pub.db.OracleOpera
import com.kent.pub.db.HiveOpera
import com.kent.pub.db.DBLink
import java.text.SimpleDateFormat

import com.kent.pub.actor.Daemon
import com.kent.pub.dao.DataMonitorDao

class DataMonitorNodeInstance(override val nodeInfo: DataMonitorNode) extends ActionNodeInstance(nodeInfo)  {
  val DATA_CHECK_INTERVAL = 10000
  implicit val timeout = Timeout(60 seconds)
  
  override def execute(): Boolean = {
    this.executedMsg = "告警留言：" + (if(nodeInfo.warnMsg == null) "未配置" else nodeInfo.warnMsg)
    var detectMsg = ""
    //是否执行成功
    val monitorData = getData(nodeInfo.source)
    val maxDataOpt = if(nodeInfo.maxThre != null) Some(getData(nodeInfo.maxThre)) else None
    val minDataOpt = if(nodeInfo.minThre != null) Some(getData(nodeInfo.minThre)) else None
    
    //双重检测
    //时间间隔内，如果数据发生了变化，则继续重试
    Thread.sleep(DATA_CHECK_INTERVAL)
    val monitorDataCheck = getData(nodeInfo.source)
    if(monitorData != monitorDataCheck) throw new Exception(s"数据源在规定时间间隔内数值检测不一致：${monitorData} != ${monitorDataCheck}")
    if(minDataOpt.isDefined){
      val minDataCheck = getData(nodeInfo.minThre)
      if(minDataOpt.get != minDataCheck) throw new Exception(s"下限在规定时间间隔内检测不一致：${minDataOpt.get} != ${minDataCheck}")
    }
    if(maxDataOpt.isDefined){
      val maxDataCheck = getData(nodeInfo.maxThre)
      if(maxDataOpt.get != maxDataCheck) throw new Exception(s"上限在规定时间间隔内检测不一致：${maxDataOpt.get} != ${maxDataCheck}")
    }
    
    //检测比较
    val max = if(maxDataOpt.isDefined) maxDataOpt.get else "未定义"
	  val min = if(minDataOpt.isDefined) minDataOpt.get else "未定义"
    val result = 
      if((maxDataOpt.isDefined && monitorData > maxDataOpt.get) 
        || (minDataOpt.isDefined && monitorData < minDataOpt.get)){
      detectMsg = s"检测值未在范围内，检测值:${monitorData}，下限:${min}，上限:${max}\n"
      detectMsg += "自定义信息："+ nodeInfo.warnMsg
  	  this.executedMsg = detectMsg
  	  false
    }else{
      true
    }
    infoLog(s"检测通过，检测值: ${monitorData}，下限: ${min}，上限: ${max}")

    val instanceInfo =  Await.result(this.actionActor.getInstanceShortInfo(), 10 second)
    
    //保存数据
    val df = new SimpleDateFormat("yyyy-MM-dd");
    val sdate = df.format(Util.nowDate)
    val dmr = DataMonitorRecord(sdate, instanceInfo.name, nodeInfo.name, monitorData, minDataOpt,maxDataOpt, detectMsg, this.id)

    if(Master.dbConnector == null) {
      val dbConnectorPath = Worker.activeMasterOpt.get.path / Daemon.DB_CONNECTOR
      val aPath = this.actionActor.context.actorSelection(dbConnectorPath)
      val afF = aPath.resolveOne()
      Master.dbConnector = Await.result(afF, 20 second)
    }
    DataMonitorDao.merge(dmr)

    result
  }
  /**
   * 获取数据
   */
  def getData(obj: Any):Double = {
    val (stype, content, dbLinkeNameOpt) = obj match {
      case Source(typ,cont,dbOpt) => (typ,cont, dbOpt)
      case MaxThreshold(typ,cont,dbOpt) => (typ,cont, dbOpt)
      case MinThreshold(typ,cont,dbOpt) => (typ,cont, dbOpt)
      case _ => throw new Exception("未找到匹配的类型")
    }
    if(stype == SQL){
      val dbLinkOptF = this.actionActor.getDBLink(dbLinkeNameOpt.get)
      val dbLinkOpt = Await.result(dbLinkOptF, 60 seconds)
      if(dbLinkOpt.isDefined) getRmdbData(content, dbLinkOpt.get) else throw new Exception
    }else if(stype == COMMAND) {  //COMMAND
      getCommandData(content)
    }else if(stype == NUM){
      getInputData(content)
    }else {
       throw new Exception("未找到适合的数据源类型")
    }
  }
  
    /**
     * 获取rmdb数据
     */
    private def getRmdbData(sql: String, dbLink: DBLink):Double = {
      def toNum(rs: ResultSet): Double = if(rs.next()) rs.getString(1).trim().toDouble else throw new Exception("无查询结果")
      dbLink.shortQuery[Double](sql, toNum).get
    }
    
    /**
     * 获取命令数据
     */
    private def getCommandData(content: String): Double = {
      //写入执行文件
      this.executeScript(content).trim().toDouble
    }
    /**
     * 获取直接输入的数据
     */
    private def getInputData(content: String):Double = {
      content.trim().toDouble
    }

  def kill(): Boolean = {
    true
  }
}