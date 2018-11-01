package com.abhioncbr.etlFramework.core.validateData

import com.abhioncbr.etlFramework.commons.ContextConstantEnum._
import com.abhioncbr.etlFramework.commons.load.LoadFeedConf
import com.abhioncbr.etlFramework.commons.Context
import com.abhioncbr.etlFramework.core.transformData.TransformUtil
import com.typesafe.scalalogging.Logger
import org.apache.spark.SparkContext
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SQLContext}

class ValidateTransformedData extends ValidateData {
  private val logger = Logger(this.getClass)
  private val sparkContext: SparkContext = Context.getContextualObject[SparkContext](SPARK_CONTEXT)
  private val sqlContext: SQLContext = Context.getContextualObject[SQLContext](SQL_CONTEXT)


  private val tableName = Context.getContextualObject[LoadFeedConf](LOAD_CONF).attributesMap("tableName")
  private val databaseName = Context.getContextualObject[LoadFeedConf](LOAD_CONF).attributesMap("databaseName")
  val partitionColumns: List[String] = Context.getContextualObject[LoadFeedConf](LOAD_CONF).partitioningData.get.partitionColumns.map(column => column.paramName)


  def validateSchema(dataFrame: DataFrame): (Boolean, Option[StructType], Option[StructType]) = {
    logger.info("Validating data frame schema and hive table schema")

    val dataFrameSchema = dataFrame.schema


    var tableSchema = Context.getContextualObject[(Option[StructType], Option[StructType])](SCHEMA)
    if(tableSchema == null)
      tableSchema = TransformUtil.tableMetadata(tableName, databaseName, sqlContext, partitionColumns)

    val output = if(tableSchema._1.isDefined) tableSchema._1.get == dataFrameSchema else false
    (output, tableSchema._1, Some(dataFrameSchema))
  }

  def validateData(dataFrame: DataFrame, structType: StructType, first: Any, second: Any): Array[(DataFrame, DataFrame, Any, Any)] ={
    logger.info("Validating data frame row schema and hive table schema")

    //val temp1 = dataFrame.collect
    //val temp = temp1.partition(row => compareSchema( row, structType))
    //val validatedRdd = sparkContext.parallelize(temp._1)
    val validatedDataFrame = sqlContext.createDataFrame(dataFrame.rdd.filter(_.schema == structType), structType)

    //val nonValidatedRdd = sparkContext.parallelize(temp._2)
    val nonValidatedDataFrame = sqlContext.createDataFrame(dataFrame.rdd.filter(_.schema != structType), structType)

    Array((validatedDataFrame,nonValidatedDataFrame, first, second))
  }

  def compareSchema(row: Row, structType: StructType): Boolean = {
    try{ row.schema == structType }
    catch { case e: Throwable => println(row.mkString); false }
  }
}