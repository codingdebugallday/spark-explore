package org.abigballofmud.structured

import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.Logger
import org.abigballofmud.streaming.redis.InternalRedisClient
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.streaming.{StreamingQuery, Trigger}
import org.apache.spark.sql.types.{StringType, StructType}
import org.slf4j.LoggerFactory
import redis.clients.jedis.{Jedis, Pipeline}

import scala.collection.mutable

/**
 * <p>
 * description
 * </p>
 *
 * @author isacc 2019/11/04 15:33
 * @since 1.0
 */
//noinspection DuplicatedCode
object SyncApp {

  private val log = Logger(LoggerFactory.getLogger(SyncApp.getClass))

  def main(args: Array[String]): Unit = {

    System.setProperty("HADOOP_USER_NAME", "hive")
    System.setProperty("user.name", "hive")

    val conf: SparkConf = new SparkConf()
      .setMaster("local[2]")
      .setAppName("test_structured_streaming")
      // 加这个配置访问集群中的hive
      // https://stackoverflow.com/questions/39201409/how-to-query-data-stored-in-hive-table-using-sparksession-of-spark2
      .set("spark.sql.warehouse.dir", "/warehouse/tablespace/managed/hive")
      .set("metastore.catalog.default", "hive")
      .set("hive.metastore.uris", "thrift://hdsp001:9083")
      // spark调优 http://www.imooc.com/article/262032
      // 以下设置是为了减少hdfs小文件的产生
      //    https://www.cnblogs.com/dtmobile-ksw/p/11254294.html
      //    https://www.cnblogs.com/dtmobile-ksw/p/11293891.html
      .set("spark.sql.adaptive.enabled", "true")
      // 默认值64M
      .set("spark.sql.adaptive.shuffle.targetPostShuffleInputSize", "67108864")
      .set("spark.sql.adaptive.join.enabled", "true")
      // 20M
      .set("spark.sql.autoBroadcastJoinThreshold", "20971520")

    // redis
    val redisHost: String = "hdsp004"
    val redisPort: Int = 6379
    val redisPassword: String = "hdsp_dev"

    // kafka
    val topic: String = "hdsp_spark_sync.hdsp_core.test_userinfo"
    val brokers: String = "hdsp001:6667,hdsp002:6667,hdsp003:6667"

    // 创建redis
    InternalRedisClient.makePool(redisHost, redisPort, redisPassword)

    var partitionOffset: String = getLastTopicOffset(topic)
    if (partitionOffset == null) {
      partitionOffset = "latest"
    } else {
      partitionOffset = "{\"%s\":%s}".format(topic, partitionOffset).trim
    }
    // 创建StreamingSession
    val spark: SparkSession = getOrCreateSparkSession(conf)
    import spark.implicits._
    val df: DataFrame = spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", brokers)
      .option("subscribe", topic)
      .option("failOnDataLoss", value = false)
      .option("startingOffsets", partitionOffset)
      .load()

    val payloadSchema_o: StructType = new StructType()
      .add("payload",
        new StructType().add("op", StringType)
          .add("before", new StructType()
            .add("id", StringType, nullable = false)
            .add("username", StringType, nullable = false)
            .add("password", StringType, nullable = false)
            .add("age", StringType, nullable = false)
            .add("sex", StringType, nullable = false)
            .add("address", StringType, nullable = false)
            , nullable = false)
          .add("after",
            new StructType()
              .add("id", StringType, nullable = false)
              .add("username", StringType, nullable = false)
              .add("password", StringType, nullable = false)
              .add("age", StringType, nullable = false)
              .add("sex", StringType, nullable = false)
              .add("address", StringType, nullable = false)
            , nullable = false)
      )

    StructType(payloadSchema_o)
    val nestTimestampFormat = "yyyy-MM-dd'T'HH:mm:ss.sss'Z'"
    val df_c_u: DataFrame = df.select(
      functions.from_json(functions.col("value").cast("string"), payloadSchema_o).alias("event"),
      functions.col("timestamp").cast("string").alias("ts"),
      functions.col("topic").cast("string").alias("topic"),
      functions.col("partition").cast("string").alias("partition"),
      functions.col("offset").cast("string").alias("offset"))
      .filter($"event.payload.op".===("c") || $"event.payload.op".===("u"))
      .select("event.payload.op",
        "event.payload.after.id",
        "event.payload.after.username",
        "event.payload.after.password",
        "event.payload.after.age",
        "event.payload.after.sex",
        "event.payload.after.address",
        "ts",
        "topic",
        "partition",
        "offset")

    val df_d: DataFrame = df.select(
      functions.from_json(functions.col("value").cast("string"), payloadSchema_o).alias("event"),
      functions.col("timestamp").cast("string").alias("ts"),
      functions.col("topic").cast("string").alias("topic"),
      functions.col("partition").cast("string").alias("partition"),
      functions.col("offset").cast("string").alias("offset"))
      .filter($"event.payload.op".===("d"))
      .select("event.payload.op",
        "event.payload.before.id",
        "event.payload.before.username",
        "event.payload.before.password",
        "event.payload.before.age",
        "event.payload.before.sex",
        "event.payload.before.address",
        "ts",
        "topic",
        "partition",
        "offset")
    val ds: Dataset[Row] = df_c_u.union(df_d)

    //    val query = ds.repartition(1)
    //      .writeStream
    //      .trigger(Trigger.ProcessingTime(10, TimeUnit.SECONDS))
    //      .option("checkpointLocation", "check/path03")
    //      .format("parquet")
    //      .outputMode("append")
    //      .option("path", "hdfs://hdsp001:8020/warehouse/tablespace/spark/userinfo_parquet")
    //      .start()
    // 直接写到表
    var pipeline: Pipeline = null
    var jedis: Jedis = null
    val query: StreamingQuery = ds.repartition(1)
      .repartition(1)
      .writeStream
      .trigger(Trigger.ProcessingTime(10, TimeUnit.SECONDS))
      .foreach(writer = new ForeachWriter[Row] {
        override def open(partitionId: Long, version: Long): Boolean = {
          jedis = InternalRedisClient.getResource
          pipeline = jedis.pipelined()
          // 会阻塞redis
          pipeline.multi()
          true
        }

        override def process(row: Row): Unit = {
          log.info("row: {}", row)
          val map: mutable.Map[String, String] = scala.collection.mutable.Map[String, String]()
          for (i <- row.schema.fields.indices) {
            map += (row.schema.fields.apply(i).name -> row.getString(i))
          }
          val sparkSession: SparkSession = getOrCreateSparkSession(conf)
          val rowRDD: RDD[Row] = sparkSession.sparkContext.makeRDD(Seq(row))
          val data: DataFrame = sparkSession.createDataFrame(rowRDD, row.schema).toDF()
            .select("op",
              "id",
              "username",
              "password",
              "age",
              "sex",
              "address",
              "ts")
          data.show()
          if (data.count() > 0) {
            data.write.mode(SaveMode.Append).format("hive").saveAsTable("test.userinfo_parquet")
          }
          // 记录offset
          pipeline.set(map("topic"), "{\"%s\":%s}".format(map("partition"), map("offset")))
        }

        override def close(errorOrNull: Throwable): Unit = {
          // 执行，释放
          pipeline.exec()
          pipeline.sync()
          pipeline.close()
          InternalRedisClient.recycleResource(jedis)
        }
      })
      .start()

    query.awaitTermination()
  }

  /**
   * 获取或创建SparkSession
   *
   * @return SparkSession
   */
  def getOrCreateSparkSession(conf: SparkConf): SparkSession = {
    val spark: SparkSession = SparkSession
      .builder()
      .config(conf)
      .enableHiveSupport()
      .getOrCreate()
    spark
  }

  /**
   * 获取topic上次消费的最新offset
   *
   * @return offset
   */
  def getLastTopicOffset(topic: String): String = {
    val jedis: Jedis = InternalRedisClient.getResource
    val partitionOffset: String = jedis.get(topic)
    jedis.close()
    partitionOffset
  }

}