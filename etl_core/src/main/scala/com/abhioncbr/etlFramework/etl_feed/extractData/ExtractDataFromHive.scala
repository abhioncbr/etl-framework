package com.abhioncbr.etlFramework.etl_feed.extractData

import java.io.{BufferedReader, InputStreamReader}

import com.abhioncbr.etlFramework.commons.Context
import com.abhioncbr.etlFramework.commons.ContextConstantEnum._
import com.abhioncbr.etlFramework.commons.extract.ExtractFeed
import com.abhioncbr.etlFramework.commons.common.GeneralParam
import com.abhioncbr.etlFramework.commons.common.query.{QueryObject, QueryParamTypeEnum}
import com.abhioncbr.etlFramework.commons.util.FileUtil
import com.typesafe.scalalogging.Logger
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.{DataFrame, SQLContext}

class ExtractDataFromHive(feed: ExtractFeed) extends AbstractExtractData{
  private val logger = Logger(this.getClass)
  val query: Option[QueryObject] = feed.query

  def getRawData: DataFrame = {
    lazy val fs = FileSystem.get(new Configuration())

    //reading query from the query file.
    val sqlQueryFile: String  = FileUtil.getFilePathString(query.get.queryFile.queryFile.get)
    val tableQueryReader = new BufferedReader(new InputStreamReader(fs.open(new Path(sqlQueryFile))))
    val rawQuery = Stream.continually(tableQueryReader.readLine()).takeWhile(_ != null).toArray[String].mkString.stripMargin

    val sqlQueryParams: Array[GeneralParam] = query.get.queryArgs.get
    val queryParams = QueryParamTypeEnum.getParamsValue(sqlQueryParams.toList)
    println("query param values" + queryParams.mkString(" , "))
    val tableQuery = String.format(rawQuery, queryParams:_*)
    logger.info(s"going to execute hive query  \\n: $tableQuery")

    val sqlContext = Context.getContextualObject[SQLContext](SQL_CONTEXT)
    sqlContext.sql(tableQuery)
  }
}
