/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.cst.factory.neo4j

import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.TokenFactory
import org.antlr.v4.runtime.TokenSource
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.misc.Pair
import org.neo4j.cypher.internal.cst.factory.neo4j.CypherTokenFactory.Src
import org.neo4j.cypher.internal.util.InputPosition

import java.util

trait CypherToken extends Token {
  def position(): InputPosition
  protected def source: Pair[TokenSource, CharStream]
  protected def text: String

  final override def getText: String = {
    if (text != null) return text
    val input = getInputStream
    if (input == null) null
    else if (getStopIndex < input.size) input.getText(Interval.of(getStartIndex, getStopIndex))
    else "<EOF>"
  }

  override def getTokenSource: TokenSource = if (source eq null) null else source.a
  override def getInputStream: CharStream = if (source eq null) null else source.b
  override def getTokenIndex: Int = -1
}

object CypherToken {

  def factory(offsets: OffsetTable): TokenFactory[CypherToken] = {
    if (offsets eq null) CypherTokenFactory else new OffsetCypherTokenFactory(offsets)
  }
}

case class OffsetTable(offsets: Array[Int], start: Int) {
  override def toString: String = s"OffsetTable(${util.Arrays.toString(offsets)}, $start)"
}

final private class DefaultCypherToken(
  val source: Pair[TokenSource, CharStream],
  val getType: Int,
  val text: String,
  val getChannel: Int,
  val getStartIndex: Int,
  val getStopIndex: Int,
  val getLine: Int,
  val getCharPositionInLine: Int
) extends CypherToken {
  override def position(): InputPosition = InputPosition(getStartIndex, getLine, getCharPositionInLine + 1)
}

final private class OffsetCypherToken(
  offsets: OffsetTable,
  val source: Pair[TokenSource, CharStream],
  val getType: Int,
  val text: String,
  val getChannel: Int,
  val getStartIndex: Int,
  val getStopIndex: Int,
  val getLine: Int,
  val getCharPositionInLine: Int
) extends CypherToken {

  override def position(): InputPosition = {
    val i = (getStartIndex - offsets.start) * 3
    val o = offsets.offsets
    InputPosition(o(i), o(i + 1), o(i + 2))
  }
}

object CypherTokenFactory extends TokenFactory[CypherToken] {
  type Src = Pair[TokenSource, CharStream]

  override def create(src: Src, typ: Int, txt: String, ch: Int, start: Int, stop: Int, line: Int, charPos: Int)
    : CypherToken = new DefaultCypherToken(src, typ, txt, ch, start, stop, line, charPos)

  override def create(typ: Int, text: String): CypherToken =
    new DefaultCypherToken(null, typ, text, -1, -1, -1, -1, -1)
}

class OffsetCypherTokenFactory(offsetTable: OffsetTable) extends TokenFactory[CypherToken] {
  private[this] val offsetStart = offsetTable.start

  override def create(src: Src, typ: Int, txt: String, ch: Int, start: Int, stop: Int, line: Int, charPos: Int)
    : CypherToken = {
    if (start < offsetStart) new DefaultCypherToken(src, typ, txt, ch, start, stop, line, charPos)
    else new OffsetCypherToken(offsetTable, src, typ, txt, ch, start, stop, line, charPos)
  }

  override def create(typ: Int, text: String): CypherToken =
    new DefaultCypherToken(null, typ, text, -1, -1, -1, -1, -1)

  override def toString: String = s"OffsetCypherTokenFactory($offsetTable)"
}