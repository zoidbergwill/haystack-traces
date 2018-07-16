/*
 *  Copyright 2017 Expedia, Inc.
 *
 *       Licensed under the Apache License, Version 2.0 (the "License");
 *       you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *       Unless required by applicable law or agreed to in writing, software
 *       distributed under the License is distributed on an "AS IS" BASIS,
 *       WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *       See the License for the specific language governing permissions and
 *       limitations under the License.
 */

package com.expedia.www.haystack.trace.reader.stores

import com.expedia.open.tracing.api._
import com.expedia.www.haystack.trace.commons.clients.cassandra.{CassandraClusterFactory, CassandraSession}
import com.expedia.www.haystack.trace.commons.clients.es.document.TraceIndexDoc
import com.expedia.www.haystack.trace.commons.config.entities.{CassandraConfiguration, WhitelistIndexFieldConfiguration}
import com.expedia.www.haystack.trace.reader.config.entities.{ElasticSearchConfiguration, ServiceMetadataReadConfiguration}
import com.expedia.www.haystack.trace.reader.metrics.{AppMetricNames, MetricsSupport}
import com.expedia.www.haystack.trace.reader.stores.readers.ServiceMetadataReader
import com.expedia.www.haystack.trace.reader.stores.readers.cassandra.CassandraTraceReader
import com.expedia.www.haystack.trace.reader.stores.readers.es.query.{FieldValuesQueryGenerator, TraceCountsQueryGenerator, TraceSearchQueryGenerator}
import com.expedia.www.haystack.trace.reader.stores.readers.es.{ElasticSearchReader, ElasticSearchResult, FailedEsResult, SuccessEsResult}
import io.searchbox.core.SearchResult
import org.elasticsearch.index.IndexNotFoundException
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

class CassandraEsTraceStore(cassandraConfig: CassandraConfiguration,
                            serviceMetadataConfig: ServiceMetadataReadConfiguration,
                            esConfig: ElasticSearchConfiguration,
                            indexConfig: WhitelistIndexFieldConfiguration)(implicit val executor: ExecutionContextExecutor)
  extends TraceStore with MetricsSupport with ResponseParser {

  private val LOGGER = LoggerFactory.getLogger(classOf[ElasticSearchReader])
  private val traceRejected = metricRegistry.meter(AppMetricNames.SEARCH_TRACE_REJECTED)
  private val countRejected = metricRegistry.meter(AppMetricNames.COUNT_BUCKET_REJECTED)

  private val cassandraSession = new CassandraSession(cassandraConfig, new CassandraClusterFactory)
  private val cassandraReader: CassandraTraceReader = new CassandraTraceReader(cassandraSession, cassandraConfig)
  private val esReader: ElasticSearchReader = new ElasticSearchReader(esConfig)
  private val serviceMetadataReader: ServiceMetadataReader = new ServiceMetadataReader(cassandraSession, serviceMetadataConfig)

  private val traceSearchQueryGenerator = new TraceSearchQueryGenerator(esConfig, ES_NESTED_DOC_NAME, indexConfig)
  private val traceCountsQueryGenerator = new TraceCountsQueryGenerator(esConfig, ES_NESTED_DOC_NAME, indexConfig)
  private val fieldValuesQueryGenerator = new FieldValuesQueryGenerator(esConfig, ES_NESTED_DOC_NAME, indexConfig)

  override def searchTraces(request: TracesSearchRequest): Future[Seq[Trace]] = {
    def handleResult(result: Future[ElasticSearchResult], failOnAnyError: Boolean = false): Future[SearchResult] = {
      result.flatMap {
        case SuccessEsResult(res) => Future.successful(res)
        case FailedEsResult(error) =>
          if (!failOnAnyError && error.isInstanceOf[IndexNotFoundException]) {
            handleResult(esReader.search(traceSearchQueryGenerator.generate(request, useSpecificIndices = false)), failOnAnyError = true)
          }
          else {
            Future.failed(error)
          }
      }
    }

    val esResponse = esReader.search(traceSearchQueryGenerator.generate(request))
    handleResult(esResponse).flatMap(rs => extractTraces(rs))
  }

  private def extractTraces(result: SearchResult): Future[Seq[Trace]] = {

    // go through each hit and fetch trace for parsed traceId
    val sourceList = result.getSourceAsStringList
    if (sourceList != null && sourceList.size() > 0) {
      val traceFutures = sourceList
        .asScala
        .map(source => extractTraceIdFromSource(source))
        .filter(!_.isEmpty)
        .toSet[String]
        .toSeq
        .map(id => getTrace(id))

      // wait for all Futures to complete and then map them to Traces
      Future
        .sequence(liftToTry(traceFutures))
        .map(_.flatMap(retrieveTriedTrace))
    } else {
      Future.successful(Nil)
    }
  }

  override def getTrace(traceId: String): Future[Trace] = cassandraReader.readTrace(traceId)

  private def retrieveTriedTrace(mayBeTrace: Try[Trace]): Option[Trace] = {
    mayBeTrace match {
      case Success(trace) =>
        Some(trace)
      case Failure(ex) =>
        LOGGER.warn("traceId not found in cassandra, rejected searched trace", ex)
        traceRejected.mark()
        None
    }
  }

  override def getFieldNames(): Future[Seq[String]] = {
    Future.successful(indexConfig.whitelistIndexFields.map(_.name).distinct.sorted)
  }

  private def readFromServiceMetadata(request: FieldValuesRequest): Option[Future[Seq[String]]] = {
    if (!serviceMetadataConfig.enabled) return None

    if (request.getFieldName.toLowerCase == TraceIndexDoc.SERVICE_KEY_NAME && request.getFiltersCount == 0) {
      Some(serviceMetadataReader.fetchAllServiceNames())
    } else if (request.getFieldName.toLowerCase == TraceIndexDoc.OPERATION_KEY_NAME
      && (request.getFiltersCount == 1)
      && request.getFiltersList.get(0).getName.toLowerCase == TraceIndexDoc.SERVICE_KEY_NAME) {
      Some(serviceMetadataReader.fetchServiceOperations(request.getFilters(0).getValue))
    } else {
      LOGGER.info("read from service metadata request isn't served by cassandra")
      None
    }
  }

  override def getFieldValues(request: FieldValuesRequest): Future[Seq[String]] = {
    readFromServiceMetadata(request).getOrElse(
      esReader
        .search(fieldValuesQueryGenerator.generate(request))
        flatMap {
        case SuccessEsResult(res) => Future.successful(extractFieldValues(res, request.getFieldName.toLowerCase))
        case FailedEsResult(error) => Future.failed(error)
      }
    )
  }

  override def getTraceCounts(request: TraceCountsRequest): Future[TraceCounts] = {
    val countSearchRequest = traceCountsQueryGenerator.generate(request)
    esReader
      .count(countSearchRequest)
      .map(result =>
        mapSearchResultToTraceCount(request.getStartTime, request.getEndTime, result))
  }

  // convert all Futures to Try to make sure they all complete
  private def liftToTry[T](futures: Seq[Future[T]]): Seq[Future[Try[T]]] = futures.map { f =>
    f.map(Try(_)).recover { case t: Throwable => Failure(t) }
  }

  override def close(): Unit = {
    cassandraReader.close()
    esReader.close()
    cassandraSession.close()
  }
}
