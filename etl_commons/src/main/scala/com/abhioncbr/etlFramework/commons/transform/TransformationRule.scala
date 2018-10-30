package com.abhioncbr.etlFramework.commons.transform


import com.abhioncbr.etlFramework.commons.Context
import com.abhioncbr.etlFramework.commons.ContextConstantEnum._
import com.abhioncbr.etlFramework.commons.common.FieldMapping
import com.abhioncbr.etlFramework.commons.load.LoadFeed
import com.abhioncbr.etlFramework.sql_parser.{Clause, SQLParser}
import com.typesafe.scalalogging.Logger
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.{DataType, StructType}
import org.apache.spark.sql.{Column, DataFrame, Row, SQLContext}

import scala.util.Try

trait TransformationRule {
  def getOrder: Int
  def getGroup: Int
  def condition(f: Int => DataFrame): Boolean
  def execute(f: Int => DataFrame): Either[Array[TransformationResult], String]
}

class NilRule(order:Int, group: Int = 0) extends TransformationRule {
  override def getOrder: Int = order
  override def getGroup: Int = group
  override def condition(f: Int => DataFrame): Boolean = true
  override def execute(f: Int => DataFrame): Either[Array[TransformationResult], String] = Left(Array(TransformationResult(f.apply(group), null, null) ))
  override def toString: String = { s"ruleName = nil, order = $order, group = $group"}
}

class MergeRule(order: Int, ruleCondition: String, mergeGroup: (Int,Int), group: Int) extends TransformationRule {
  def getMergeGroup: (Int, Int) = mergeGroup
  override def getOrder: Int = order
  override def getGroup: Int = group
  override def toString: String = { s"ruleName = merge, merge_group = '$mergeGroup', order = $order, group = $group, ruleCondition = '$ruleCondition'"}

  def condition(f: Int => DataFrame): Boolean = {
    Try(f.apply(mergeGroup._1)).isSuccess &&  Try(f.apply(mergeGroup._2)).isSuccess
  }

  def execute(f: Int => DataFrame): Either[Array[TransformationResult], String] = {
    val df1: DataFrame = f.apply(mergeGroup._1)
    val df2: DataFrame = f.apply(mergeGroup._2)
    if(df1.count > 0 && df2.count <= 0) Left(Array(TransformationResult(df1, null, null) ))
    if(df1.count <= 0 && df2.count > 0) Left(Array(TransformationResult(df2, null, null) ))

    if(df1.schema == df2.schema) Left(Array(TransformationResult(df1.union(df2), null, null) ))
    else Right(s"DataFrames of group $mergeGroup, schema is not equal")
  }
}

abstract class abstractTransformationRule(order:Int, group: Int = 0, condition: String) extends TransformationRule{
  private val logger = Logger(this.getClass)
  override def getOrder: Int = order
  override def getGroup: Int = group
  override def toString: String = { s"order = $order, group = $group, ruleCondition = '$condition'" }
  override def condition(f: Int => DataFrame): Boolean = condition(f.apply(group))
  override def execute(f: Int => DataFrame): Either[Array[TransformationResult], String] = execute(f.apply(group))

  def condition(inputDataFrame: DataFrame): Boolean
  def execute(inputDataFrame: DataFrame): Either[Array[TransformationResult], String]

  def analyzeWhereCondition(inputDataFrame: DataFrame): (Boolean, Seq[Clause]) ={
    try{
      val whereClauses = new SQLParser().getWhereClause(condition)

      var output = true
      whereClauses.get.foreach(clause => {
        val field = clause.getFields("field").toString
        if(!TransformUtil.hasColumn(inputDataFrame, field)) output = false
      })
      (output, whereClauses.get)
    } catch {
      case ex: Exception => logger.info(s"analyzeWhereCondition - returning false & null")
        logger.error(ex.getMessage)
        (false, null)
    }
  }

  def analyzeSelectCondition(inputDataFrame: DataFrame): (Boolean, Seq[String]) ={
    try{
      val selectClauses = new SQLParser().getSelectClause(condition)
      var output = true
      selectClauses.get.foreach(str => {
        if(!TransformUtil.hasColumn(inputDataFrame, str)) output = false
      })
      (output, selectClauses.get)
    } catch {
      case ex: Exception => (false, null)
    }
  }
}

//Added on Sept-17, for adding column in to dataframe(currently, column values is only of type partition column)
//TODO: Need to enhance in-future to support column values based on some logic.
class AddColumnRule(order:Int, group: Int = 0, columnName: String, columnValueType: String) extends TransformationRule {
  override def getOrder: Int = order
  override def getGroup: Int = group
  override def condition(f: Int => DataFrame): Boolean = true
  override def execute(f: Int => DataFrame): Either[Array[TransformationResult], String] = {
    import org.apache.spark.sql.functions.lit
    val value:Column = lit("")

    val inputDataFrame:DataFrame = f.apply(group)

    Left(Array(TransformationResult(inputDataFrame.withColumn(columnName,value), null, null) ))
  }
  override def toString: String = { s"ruleName = addColumn, order = $order, group = $group"}
}


class SimpleFunctionRule(functionType:String, order: Int, ruleCondition: String, group: Int)
  extends abstractTransformationRule(order, group, ruleCondition) {
  override def toString: String = { s"ruleName = SimpleFunctionRule, functionType= $functionType, " + super.toString}

  var whereClause: Seq[Clause] = _
  var selectColumns: Seq[String] = _
  def condition(inputDataFrame: DataFrame): Boolean = {
    functionType match {
      case "FILTER" => val output = analyzeWhereCondition(inputDataFrame)
        whereClause = output._2
        output._1
      case "SELECT" => val output = analyzeSelectCondition(inputDataFrame); selectColumns = output._2; output._1
      case "DROP" => val output = analyzeSelectCondition(inputDataFrame); selectColumns = output._2; output._1
      case "EXPLODE" =>  val output = analyzeSelectCondition(inputDataFrame); selectColumns = output._2; output._1
    }
  }

  def execute(inputDataFrame: DataFrame): Either[Array[TransformationResult], String] = {
    functionType match {
      case "FILTER" => Left(filter(inputDataFrame))
      case "SELECT" => Left(select(inputDataFrame))
      case "DROP" => Left(drop(inputDataFrame))
      case "EXPLODE" =>  Left(explode(inputDataFrame))
    }
  }

  def filter(inputDataFrame: DataFrame): Array[ TransformationResult ] = {
    Array(TransformationResult(inputDataFrame.filter(ruleCondition), null, null))
  }

  def select(inputDataFrame: DataFrame): Array[ TransformationResult ] = {
    val selectColumns = ruleCondition.split(",")
    //added condition for supporting column like 'http-agent'
    val selectCondition = selectColumns.map(str => if(str.contains(".")) str.trim else s"`${str.trim}`").mkString(", ")

    val sqlContext = Context.getContextualObject[SQLContext](SQL_CONTEXT)
    inputDataFrame.createOrReplaceTempView("temp")
    Array(TransformationResult(sqlContext.sql(s"select $selectCondition from temp"), null, null))
  }

  def drop(inputDataFrame: DataFrame): Array[ TransformationResult ] = {
    var outputDF = inputDataFrame
    selectColumns.foreach(str => outputDF = outputDF.drop(str))
    Array(TransformationResult(outputDF, null, null))
  }

  def explode(inputDataFrame: DataFrame): Array[ TransformationResult ] = {
    var outputDF = inputDataFrame

    val sqlContext: SQLContext = Context.getContextualObject[SQLContext](SQL_CONTEXT)
    import sqlContext.implicits._
    selectColumns.foreach(str => outputDF = outputDF.select(org.apache.spark.sql.functions.explode($"$str").alias(s"$str")))
    Array(TransformationResult(outputDF, null, null))
  }
}

class PartitionRule(order: Int, scope: String, ruleCondition: String, group: Int)
  extends abstractTransformationRule(order, group, ruleCondition) {
  private var condition: String = _
  private var notCondition: String = _

  override def toString: String = { s"ruleName = partition, scope = $scope, " + super.toString}

  def condition(inputDataFrame: DataFrame): Boolean = {
    val output = analyzeWhereCondition(inputDataFrame)
    setCondition(output._2)
    output._1
  }

  def execute(inputDataFrame: DataFrame): Either[Array[TransformationResult], String] = {
    val sqlContext = Context.getContextualObject[SQLContext](SQL_CONTEXT)
    inputDataFrame.createGlobalTempView("temp")

    val dataFrame = sqlContext.sql(s"select * from temp where $condition")
    val negateDataFrame = sqlContext.sql(s"select * from temp where $notCondition")
    Left(Array(TransformationResult(dataFrame, null, null), TransformationResult(negateDataFrame, null, null) ))
  }

  private def setCondition(conditions: Seq[Clause]): Unit = {
    conditions.foreach {
      case notNullClause: com.abhioncbr.etlFramework.sql_parser.NotNull =>
        condition = s"${notNullClause.getFields("field")} IS NOT NULL"
        notCondition = s"${notNullClause.getFields("field")} IS NULL"

      case nullClause: com.abhioncbr.etlFramework.sql_parser.Null =>
        condition = s"${nullClause.getFields("field")} IS NULL"
        notCondition = s"${nullClause.getFields("field")} IS NOT NULL"
    }
  }
}

//TODO: rule condition more dynamic
class SchemaTransformationRule(order: Int, ruleCondition: String, group: Int, fieldMapping: List[FieldMapping], failedFieldLimit: Int, failedRowLimit: Int)
  extends abstractTransformationRule(order, group, ruleCondition) {
  private val logger = Logger(this.getClass)
  private var tableSchema:StructType = _

  override def toString: String = {s"ruleName = schema_transformation, ruleCondition = $ruleCondition, " +
    s"failedFieldLimit = $failedFieldLimit, failedRowLimit = $failedRowLimit, fieldMapping = $fieldMapping, " + super.toString }

  def condition(inputDataFrame: DataFrame): Boolean = {
    val sqlContext: SQLContext = Context.getContextualObject[SQLContext](SQL_CONTEXT)
    val tableName = Context.getContextualObject[LoadFeed](LOAD).attributesMap("tableName")
    val databaseName = Context.getContextualObject[LoadFeed](LOAD).attributesMap("databaseName")
    val partitionColumns: List[String] = Context.getContextualObject[LoadFeed](LOAD).partitioningData.get.partitionColumns.map(column => column.paramName)

    val temp = TransformUtil.tableMetadata(tableName, databaseName, sqlContext, partitionColumns)

    //adding schema in to context for re-use in validation step.
    Context.addContextualObject[(Option[StructType], Option[StructType])](SCHEMA, temp)

    if(temp._1.isDefined) tableSchema = temp._1.get
    else logger.error(s"Table '$tableName' doesn't exist in database '$databaseName'")

    temp._1.isDefined
  }

  def execute(inputDataFrame: DataFrame): Either[Array[TransformationResult], String] = {
    val tableColumns: List[(Int, String, DataType)] = TransformUtil.getColumnsInfo(tableSchema,
      fieldMapping.map(mapping => (mapping.targetFieldName,mapping.sourceFieldName)).toMap)

    //TODO: pre append rule condition
    val preAppend = ruleCondition
    val temp: RDD[(Int, Row)] = inputDataFrame.rdd.map(row => TransformUtil.flattenStructType(row, preAppend)).map(row => {
      var failCount = 0
      val array:Array[Any] = tableColumns.map(ele => TransformUtil.selectColumn(row, ele._3, ele._2)).toArray.map(
        either => if(either.isLeft)
          either.left.get
        else {
          failCount =  failCount+1
          logger.error(either.right.get)
          null
        }
      )
      (failCount, Row.fromSeq(array))
    })

    val TRUE: Boolean = true
    val FALSE: Boolean = false

    val validRDD: RDD[(Int, Row)] = filterRdd(TRUE, failedFieldLimit, temp)
    val invalidRDD: RDD[(Int, Row)] = filterRdd(FALSE, failedFieldLimit, temp)
    val validCount: Int = validRDD.count.toInt
    val invalidCount: Int = invalidRDD.count.toInt

    //.filter(tuple => tuple._1 <= failedFieldLimit)
    //partition(tuple => tuple._1 <= failedFieldLimit)

    logger.info(s"failed schema transformation rows count: $invalidCount")
    logger.info(s"passed schema transformation rows count: $validCount")

    if(invalidCount > failedRowLimit)
      return Right(s"Aborting job because number of row failed($invalidCount) in schema transformation is greater then threshold($failedRowLimit)")

    val transformedRDD = validRDD.map(tuple => tuple._2)
    // val sparkContext: SparkContext = Context.getContextualObject[SparkContext](SPARK_CONTEXT)
    // val transformedRDD = sparkContext.makeRDD(transformedSequence)

    val sqlContext: SQLContext = Context.getContextualObject[SQLContext](SQL_CONTEXT)
    val outputDF = sqlContext.createDataFrame(transformedRDD, schema = tableSchema)

    Left(Array(TransformationResult(outputDF, validCount, invalidCount) ))
  }

  def filterRdd(valid: Boolean, failedFieldLimit: Int, temp: RDD[(Int, Row)] ): RDD[(Int, Row)] = {
    if(valid) temp.filter(_._1 <= failedFieldLimit) else temp.filter(_._1 > failedFieldLimit)
  }
}