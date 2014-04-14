/**
 * Copyright 2011-2013 Gatling (gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.jsonpath

import java.util.{ List => JList, Map => JMap }

import scala.annotation.tailrec
import scala.collection.JavaConversions.{ asScalaBuffer, asScalaIterator }
import scala.math.abs

import io.gatling.jsonpath.AST._

case class JPError(reason: String)

object JsonPath {
  val parser = new ThreadLocal[Parser]() {
    override def initialValue() = new Parser
  }

  def compile(query: String): Either[JPError, JsonPath] =
    parser.get.compile(query) match {
      case Parser.Success(q, _) => Right(new JsonPath(q))
      case ns: Parser.NoSuccess => Left(JPError(ns.msg))
    }

  def query(query: String, jsonObject: Any): Either[JPError, Iterator[Any]] =
    compile(query).right.map(_.query(jsonObject))
}

class JsonPath(path: List[PathToken]) {
  def query(jsonObject: Any) = new JsonPathWalker(jsonObject, path).walk
}

class JsonPathWalker(rootNode: Any, fullPath: List[PathToken]) {

  def walk(): Iterator[Any] = walk(rootNode, fullPath)

  //@tailrec
  private[this] def walk(node: Any, path: List[PathToken]): Iterator[Any] =
    path match {
      case Nil          => Iterator.single(node)
      case head :: tail => walk1(node, head).flatMap(walk(_, tail))
    }

  private[this] def walk1(node: Any, query: PathToken): Iterator[Any] = {
    query match {
      case RootNode    => Iterator.single(rootNode)

      case CurrentNode => Iterator.single(node)

      case Field(name) => node match {
        case obj: JMap[_, _] if obj.containsKey(name) =>
          Iterator.single(obj.get(name))
        case _ => Iterator.empty
      }

      case RecursiveField(name) => recFieldFilter(node, name)

      case MultiField(fieldNames) => node match {
        case obj: JMap[_, _] =>
          fieldNames.iterator.collect { case fieldName if obj.containsKey(fieldName) => obj.get(fieldName) }
        case _ => Iterator.empty
      }

      case AnyField => node match {
        case obj: JMap[_, _] => obj.values.iterator
        case _               => Iterator.empty
      }

      case ArraySlice(None, None, 1) => node match {
        case array: JList[_] => array.iterator
        case _               => Iterator.empty
      }

      case ArraySlice(start, stop, step) => node match {
        case array: JList[_] => sliceArray(array, start, stop, step)
        case _               => Iterator.empty
      }

      case ArrayRandomAccess(indices) => node match {
        case array: JList[_] =>
          indices.iterator
            .collect {
              case i if i >= 0 && i < array.size  => array.get(i)
              case i if i < 0 && i >= -array.size => array.get(i + array.size)
            }
        case _ => Iterator.empty
      }

      case filterToken: FilterToken => applyFilter(filterToken, node)

      case RecursiveAnyField        => recFieldExplorer(node)
    }
  }

  private[this] def applyFilter(filterToken: FilterToken, node: Any): Iterator[Any] = {

      def resolveSubQuery(node: Any, q: List[AST.PathToken], nextOp: Any => Boolean): Boolean = {
        val it = walk(node, q)
        it.hasNext && nextOp(it.next)
      }

      def applyBinaryOpWithResolvedLeft(node: Any, op: ComparisonOperator, lhsNode: Any, rhs: FilterValue): Boolean =
        rhs match {
          case direct: FilterDirectValue => op(lhsNode, direct.value)
          case SubQuery(q)               => resolveSubQuery(node, q, op(lhsNode, _))
        }

      def applyBinaryOp(node: Any, op: ComparisonOperator, lhs: FilterValue, rhs: FilterValue): Boolean =
        lhs match {
          case direct: FilterDirectValue => applyBinaryOpWithResolvedLeft(node, op, direct.value, rhs)
          case SubQuery(q)               => resolveSubQuery(node, q, applyBinaryOpWithResolvedLeft(node, op, _, rhs))
        }

      def elementsToFilter(node: Any): Iterator[Any] =
        node match {
          case array: JList[_] => array.iterator
          case obj: JMap[_, _] => obj.values.iterator
          case _               => Iterator.empty
        }

      def evaluateFilter(filterToken: FilterToken): Any => Boolean =
        filterToken match {
          case HasFilter(subQuery) =>
            (node: Any) => walk(node, subQuery.path).hasNext

            case ComparisonFilter(op, lhs, rhs) =>
            (node: Any) => applyBinaryOp(node, op, lhs, rhs)

            case BooleanFilter(op, filter1, filter2) =>
            val f1 = evaluateFilter(filter1)
            val f2 = evaluateFilter(filter2)
            (node: Any) => op(f1(node), f2(node))
        }

    val filterFunction = evaluateFilter(filterToken)
    elementsToFilter(node).filter(filterFunction)
  }

  def recFieldFilter(node: Any, name: String): Iterator[Any] = {
      //@tailrec
      def _recFieldFilter(node: Any): Iterator[Any] =
        node match {
          case obj: JMap[_, _] =>
            obj.entrySet.iterator.flatMap(e => e.getKey match {
              case `name` => Iterator.single(e.getValue)
              case key    => _recFieldFilter(e.getValue)
            })
          case list: JList[_] => list.iterator.flatMap(_recFieldFilter)
          case _              => Iterator.empty
        }

    _recFieldFilter(node)
  }

  def recFieldExplorer(node: Any): Iterator[Any] =
    node match {
      case obj: JMap[_, _] =>
        val values = obj.values
        values.iterator ++ values.iterator.flatMap(recFieldExplorer)
      case list: JList[_] =>
        list.iterator.flatMap(recFieldExplorer)
      case _ => Iterator.empty
    }

  private[this] def sliceArray(array: JList[_], start: Option[Int], stop: Option[Int], step: Int): Iterator[Any] = {
    val size = array.size

      def lenRelative(x: Int) = if (x >= 0) x else size + x
      def stepRelative(x: Int) = if (step >= 0) x else -1 - x
      def relative(x: Int) = lenRelative(stepRelative(x))

    val absStart = start match {
      case Some(v) => relative(v)
      case _       => 0
    }
    val absEnd = stop match {
      case Some(v) => relative(v)
      case _       => size
    }
    val absStep = abs(step)

    val elts: Iterator[Any] = if (step < 0) array.reverseIterator else array.iterator
    val fromStartToEnd = elts.slice(absStart, absEnd)

    if (absStep != 1)
      fromStartToEnd.grouped(absStep).map(_.head)
    else
      fromStartToEnd
  }
}
