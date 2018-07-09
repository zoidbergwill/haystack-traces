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

package com.expedia.www.haystack.trace.reader.readers.utils

import com.expedia.open.tracing.Span

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class MutableSpanForest(private var spans: Seq[Span]) {
  private var forest: mutable.ListBuffer[SpanTree] = _
  private var needForestUpdate = true

  def getAllTrees: Seq[SpanTree] = {
    if (needForestUpdate) recreateForest()
    forest
  }

  def countTrees: Int = getAllTrees.size

  def treesWithLoopbackRoots: Seq[SpanTree] = getAllTrees.filter(tree => tree.span.getSpanId == tree.span.getParentSpanId)

  def getUnderlyingSpans: Seq[Span] = this.spans

  def updateUnderlyingSpans(spans: Seq[Span], triggerForestUpdate: Boolean = true): MutableSpanForest = {
    this.spans = spans
    if (triggerForestUpdate) needForestUpdate = true
    this
  }

  def orphanedTrees(): Seq[SpanTree] = getAllTrees.filter(_.span.getParentSpanId.nonEmpty)

  def addNewRoot(rootSpan: Span): MutableSpanForest = {
    val newTree = SpanTree(rootSpan)
    mergeTreesUnder(newTree)
    spans = spans :+ rootSpan
    forest = mutable.ListBuffer(newTree)
    needForestUpdate = false
    this
  }

  def mergeTreesUnder(root: SpanTree): MutableSpanForest = {
    val toBeMergedTrees = forest.filter(_ != root)

    val toBeUpdatedUnderlyingSpans = mutable.ListBuffer[(Span, Span)]()
    toBeMergedTrees.foreach(tree => {
      val originalSpan = tree.span
      val updatedSpan = Span.newBuilder(originalSpan).setParentSpanId(root.span.getSpanId).build()
      toBeUpdatedUnderlyingSpans += ((originalSpan, updatedSpan))
      tree.span = updatedSpan
      root.children += tree
    })

    updateUnderlyingSpanWith(toBeUpdatedUnderlyingSpans)
    this
  }

  def updateEachSpanTreeRoot(updateFunc: (Span) => Span): MutableSpanForest = {
    val toBeUpdatedUnderlyingSpans = mutable.ListBuffer[(Span, Span)]()

    for (tree <- getAllTrees) {
      val originalSpan = tree.span
      val updatedSpan = updateFunc(originalSpan)
      if (originalSpan != updatedSpan) {
        tree.span = updatedSpan
        toBeUpdatedUnderlyingSpans += ((originalSpan, updatedSpan))
      }
    }
    updateUnderlyingSpanWith(toBeUpdatedUnderlyingSpans)

    this
  }

  private def recreateForest() = {
    this.forest = mutable.ListBuffer[SpanTree]()
    if (this.spans.nonEmpty) {
      val spanIdTreeMap = mutable.HashMap[String, SpanTree]()
      spans.foreach(span => spanIdTreeMap.put(span.getSpanId, SpanTree(span)))

      val possibleRoots = mutable.HashSet[String]()
      spans.foreach(span => possibleRoots.add(span.getSpanId))

      for (span <- spans;
           parentTree <- spanIdTreeMap.get(span.getParentSpanId)) {
        val spanIdTree = spanIdTreeMap(span.getSpanId)
        if (parentTree != spanIdTree ) {
          parentTree.children += spanIdTree
          possibleRoots.remove(span.getSpanId)
        }
      }

      spanIdTreeMap.foreach {
        case (spanId, tree) => if (possibleRoots.contains(spanId)) this.forest += tree
      }
    }
    needForestUpdate = false
  }

  private def updateUnderlyingSpanWith(updateList: ListBuffer[(Span, Span)]) = {
    if (updateList.nonEmpty) {
      // update the underlying spans
      this.spans = this.spans.map(span => {
        updateList.find {
          case (curr, _) => curr == span
        } match {
          case Some((_, ne)) => ne
          case _ => span
        }
      })
    }
  }
}

case class SpanTree(var span: Span, children: mutable.ListBuffer[SpanTree] = mutable.ListBuffer[SpanTree]())