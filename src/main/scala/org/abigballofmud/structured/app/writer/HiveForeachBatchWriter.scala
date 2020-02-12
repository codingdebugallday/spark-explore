package org.abigballofmud.structured.app.writer

import org.abigballofmud.redis.InternalRedisClient
import org.abigballofmud.structured.app.model.{SyncConfig, TopicInfo}
import org.apache.spark.sql.{DataFrame, Dataset, SaveMode, SparkSession}
import redis.clients.jedis.{Jedis, Pipeline}

import scala.collection.mutable

/**
 * <p>
 * description
 * </p>
 *
 * @author isacc 2020/02/11 11:37
 * @since 1.0
 */
//noinspection DuplicatedCode
object HiveForeachBatchWriter {

  def handle(syncConfig: SyncConfig, batchDF: DataFrame, batchId: Long, spark: SparkSession): Unit = {
    import spark.implicits._
    // hive表中加 op ts
    val columns: List[String] = syncConfig.syncSpark.columns.trim.split(",").toList
    var colList: List[String] = List()
    colList = columns :+ "op" :+ "ts"
    batchDF.persist()
    batchDF.show()
    val topicInfo: Dataset[TopicInfo] = batchDF.as[TopicInfo]
    val hiveTableName: String = syncConfig.syncSpark.hiveDatabaseName + "." + syncConfig.syncSpark.hiveTableName
    val df: DataFrame = batchDF.toDF().selectExpr(colList: _*)
    if (df.count() > 0) {
      df.write.mode(SaveMode.Append).format("hive").saveAsTable(hiveTableName)
    }
    val jedis: Jedis = InternalRedisClient.getResource
    val pipeline: Pipeline = jedis.pipelined()
    // 会阻塞redis
    pipeline.multi()
    val map: mutable.Map[String, String] = scala.collection.mutable.Map[String, String]()
    for (elem <- topicInfo.collect()) {
      // kafka offset缓存
      map.empty
      map += ("topic" -> elem.topic)
      map += ("partition" -> elem.partition)
      map += ("offset" -> elem.offset)
      map += ("appName" -> syncConfig.syncSpark.sparkAppName)
      pipeline.set(map("topic") + ":" + map("appName"), "{\"%s\":%s}".format(map("partition"), map("offset").toInt + 1))
    }
    // 执行，释放
    pipeline.exec()
    pipeline.sync()
    pipeline.close()
    InternalRedisClient.recycleResource(jedis)
    batchDF.unpersist()
  }
}
