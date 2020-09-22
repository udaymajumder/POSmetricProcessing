package org.example.spark.POSmetricProcessing


import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import org.apache.hadoop.hbase.client.{HTable, Put}
import org.apache.hadoop.hbase.util.Bytes
import org.apache.spark.SparkConf
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.{ForeachWriter, Row, SparkSession}
import org.example.spark.POSmetricProcessing.POJO.EntityMapper._
import org.example.spark.POSmetricProcessing.hbaseUtils.hbaseOps._
import org.apache.spark.sql.functions._
import org.example.spark.POSmetricProcessing.sparkUdfUtils.MerchantBillingAggregator

import scala.util.Try
import org.example.spark.POSmetricProcessing.sparkUdfUtils.SparkUdfObject._

case class offsetMarker(topic:String,partition:Int,offset:Long)
case class billingKafka(topic:String,billing:SinkBillingDetail,partition:Int,offset:Long)


object MetricProcessMain {


  def main(args: Array[String]): Unit = {


    val spark = SparkSession.builder().master("local").config(new SparkConf()).getOrCreate()



    /* Invoice Processing Start*/
    /*val invoiceOffsetParam: Map[String, String] = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/SparkKafkaConfig.cfg")).getLines().filter(line=>line.startsWith("invoice")).map(line=>line.split("#")(1)).map(line=>(line.split("=")(0),line.split("=")(1))).toMap
    val invoice_subscribe_props = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/SparkKafkaConfig.cfg")).getLines().filter(line=>(line.startsWith("init#") || line.startsWith("invoice#"))).map(line=>line.split("#")(1)).map(line=>(line.split("=")(0),line.split("=")(1))).toMap

    val sDf=spark.readStream.format("kafka").options(invoice_subscribe_props).option("startingOffsets", parseOffset(invoiceOffsetParam)).load()
      .selectExpr("CAST(topic AS STRING)","CAST(value AS STRING)","CAST(partition AS INT)","CAST(offset AS LONG)")

    sDf.writeStream.foreach(new ForeachWriter[Row] {

      private var tbl: HTable = null
      private var offsetmarker:offsetMarker = _
      override def open(partitionId: Long, version: Long): Boolean = {
        println("Input Value PartitionId:" + partitionId + " Version:" + version)
        Try( if(chkTbl("INVOICE_TBL")) tbl = new HTable(hbaseConfiguration, "INVOICE_TBL")).isSuccess
      }

      override def process(value: Row): Unit = {

        val mapper = new ObjectMapper() with ScalaObjectMapper
        mapper.registerModule(DefaultScalaModule)
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val dSerValue = mapper.readValue[Invoice](value.getAs("value").toString)
        println(dSerValue)
        val put: Put = new Put(Bytes.toBytes(dSerValue.invoiceNum.toString))
        val enrPut = hbaseUtils.hbaseEntityUtils.splitAndFetchInvoiceDtl(dSerValue, put)
        tbl.put(enrPut)

        offsetmarker = offsetMarker(value.getAs("topic"),value.getAs("partition"),value.getAs("offset"))

      }

      override def close(errorOrNull: Throwable): Unit = {

        if(offsetmarker == null || offsetmarker.offset == -1) {

          println("NO BATCH PROCESSED!")

        }
        else {

            Try( if(chkTbl("POS_HBASE_OFFSET_TBL"))
            { tbl = new HTable(hbaseConfiguration, "POS_HBASE_OFFSET_TBL") }
            )

            println(offsetmarker)
            val offsetPut = new Put(Bytes.toBytes(offsetmarker.topic.toString + "_" + offsetmarker.partition.toString))
            offsetPut.addColumn(Bytes.toBytes(offsetmarker.topic.toString),Bytes.toBytes(offsetmarker.partition.toString),Bytes.toBytes(offsetmarker.offset.toString))
            tbl.put(offsetPut)
        }
      }

    }).start().awaitTermination()*/

    /* Invoice processing END */

    /* Billing Processing*/
    val billingOffsetParam: Map[String, String] = scala.io.Source.fromInputStream((this.asInstanceOf[Any]).getClass.getResourceAsStream("/SparkKafkaConfig.cfg")).getLines().filter(line=>line.startsWith("billing")).map(line=>line.split("#")(1)).map(line=>(line.split("=")(0),line.split("=")(1))).toMap
    val billing_subscribe_props = scala.io.Source.fromInputStream((this.asInstanceOf[Any]).getClass.getResourceAsStream("/SparkKafkaConfig.cfg")).getLines().filter(line=>(line.startsWith("init#") || line.startsWith("billing#"))).map(line=>line.split("#")(1)).map(line=>(line.split("=")(0),line.split("=")(1))).toMap

    import spark.implicits._
    val catAgg = new MerchantBillingAggregator()

    val billingDf=spark.readStream.format("kafka").options(billing_subscribe_props).option("startingOffsets", "earliest").load()
      //.selectExpr("CAST(topic AS STRING)","CAST(value AS STRING)","CAST(partition AS INT)","CAST(offset AS LONG)","CAST(timestamp AS timestamp")
      .select(col("topic").cast("string"),parseBill(col("value").cast("string")).as("value"),col("partition").cast("int"),col("offset").cast("long"),col("timestamp"))
        .groupBy(window(col("timestamp"),"1 minute","1 minute"),col("value.MRCH_CAT_CD")).agg(catAgg(col("value")))

    billingDf.printSchema()

    billingDf.writeStream.outputMode("console").start().awaitTermination()


  }

}
