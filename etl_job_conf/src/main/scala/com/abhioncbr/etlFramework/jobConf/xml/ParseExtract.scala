package com.abhioncbr.etlFramework.jobConf.xml

import com.abhioncbr.etlFramework.commons.common.file.FilePath
import com.abhioncbr.etlFramework.commons.common.query.QueryObject
import com.abhioncbr.etlFramework.commons.extract.{Extract, ExtractionType, ExtractFeed}

object ParseExtract {
  def fromXML(node: scala.xml.NodeSeq): Extract = {
    val extract: Extract = Extract(feeds = Array[ExtractFeed]((node \ "feed" ).toList map { s => ParseExtractFeed.fromXML(s) }: _*))
    extract
  }
}

object ParseExtractFeed {
  def fromXML(node: scala.xml.NodeSeq): ExtractFeed = {
    val feedName: String = (node \ "@feedName").text
    val extractionSubType: String = (node \ "_").head.attributes.value.text.toUpperCase
    val validateExtractedData: Boolean = ParseUtil.parseBoolean((node \ "@validateExtractedData").text)
    val extractionType: ExtractionType.valueType = ExtractionType.getValueType( valueTypeString = (node \ "_").head.label.toUpperCase)

    val query: Option[QueryObject] = ParseUtil.parseNode[QueryObject](node \ "jdbc" \ "query", None, ParseQuery.fromXML)
    val dataPath: Option[FilePath] = ParseUtil.parseNode[FilePath](node \ "fileSystem" \ "dataPath", None, ParseDataPath.fromXML)

    val feed: ExtractFeed = ExtractFeed(extractFeedName = feedName,
      extractionType = extractionType, extractionSubType = extractionSubType,
      dataPath = dataPath , query =query, validateExtractedData = validateExtractedData)
    feed
  }
}