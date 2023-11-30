/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.interpreted.commands.showcommands

import org.neo4j.common.EntityType
import org.neo4j.cypher.internal.ast.AllConstraints
import org.neo4j.cypher.internal.ast.CommandResultItem
import org.neo4j.cypher.internal.ast.ExistsConstraints
import org.neo4j.cypher.internal.ast.KeyConstraints
import org.neo4j.cypher.internal.ast.NodeExistsConstraints
import org.neo4j.cypher.internal.ast.NodeKeyConstraints
import org.neo4j.cypher.internal.ast.NodePropTypeConstraints
import org.neo4j.cypher.internal.ast.NodeUniqueConstraints
import org.neo4j.cypher.internal.ast.PropTypeConstraints
import org.neo4j.cypher.internal.ast.RelExistsConstraints
import org.neo4j.cypher.internal.ast.RelKeyConstraints
import org.neo4j.cypher.internal.ast.RelPropTypeConstraints
import org.neo4j.cypher.internal.ast.RelUniqueConstraints
import org.neo4j.cypher.internal.ast.ShowColumn
import org.neo4j.cypher.internal.ast.ShowConstraintType
import org.neo4j.cypher.internal.ast.ShowConstraintsClause.createStatementColumn
import org.neo4j.cypher.internal.ast.ShowConstraintsClause.entityTypeColumn
import org.neo4j.cypher.internal.ast.ShowConstraintsClause.idColumn
import org.neo4j.cypher.internal.ast.ShowConstraintsClause.labelsOrTypesColumn
import org.neo4j.cypher.internal.ast.ShowConstraintsClause.nameColumn
import org.neo4j.cypher.internal.ast.ShowConstraintsClause.optionsColumn
import org.neo4j.cypher.internal.ast.ShowConstraintsClause.ownedIndexColumn
import org.neo4j.cypher.internal.ast.ShowConstraintsClause.propertiesColumn
import org.neo4j.cypher.internal.ast.ShowConstraintsClause.propertyTypeColumn
import org.neo4j.cypher.internal.ast.ShowConstraintsClause.typeColumn
import org.neo4j.cypher.internal.ast.UniqueConstraints
import org.neo4j.cypher.internal.runtime.ClosingIterator
import org.neo4j.cypher.internal.runtime.ConstraintInfo
import org.neo4j.cypher.internal.runtime.CypherRow
import org.neo4j.cypher.internal.runtime.interpreted.commands.showcommands.ShowConstraintsCommand.createConstraintStatement
import org.neo4j.cypher.internal.runtime.interpreted.commands.showcommands.ShowConstraintsCommand.getConstraintType
import org.neo4j.cypher.internal.runtime.interpreted.commands.showcommands.ShowSchemaCommandHelper.asEscapedString
import org.neo4j.cypher.internal.runtime.interpreted.commands.showcommands.ShowSchemaCommandHelper.colonStringJoiner
import org.neo4j.cypher.internal.runtime.interpreted.commands.showcommands.ShowSchemaCommandHelper.configAsString
import org.neo4j.cypher.internal.runtime.interpreted.commands.showcommands.ShowSchemaCommandHelper.escapeBackticks
import org.neo4j.cypher.internal.runtime.interpreted.commands.showcommands.ShowSchemaCommandHelper.extractOptionsMap
import org.neo4j.cypher.internal.runtime.interpreted.commands.showcommands.ShowSchemaCommandHelper.optionsAsString
import org.neo4j.cypher.internal.runtime.interpreted.commands.showcommands.ShowSchemaCommandHelper.pointConfigValueAsString
import org.neo4j.cypher.internal.runtime.interpreted.commands.showcommands.ShowSchemaCommandHelper.propStringJoiner
import org.neo4j.cypher.internal.runtime.interpreted.commands.showcommands.ShowSchemaCommandHelper.relPropStringJoiner
import org.neo4j.cypher.internal.runtime.interpreted.pipes.QueryState
import org.neo4j.internal.schema
import org.neo4j.internal.schema.ConstraintDescriptor
import org.neo4j.internal.schema.IndexConfig
import org.neo4j.values.AnyValue
import org.neo4j.values.storable.Values
import org.neo4j.values.virtual.MapValue
import org.neo4j.values.virtual.VirtualValues

import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters.SeqHasAsJava

// SHOW [
//   ALL
//   | NODE UNIQUE | RELATIONSHIP UNIQUE | UNIQUE
//   | NODE EXIST | RELATIONSHIP EXIST | EXIST
//   | NODE KEY | RELATIONSHIP KEY | KEY
//   | NODE PROPERTY TYPE | RELATIONSHIP PROPERTY TYPE | PROPERTY TYPE
// ] CONSTRAINT[S] [BRIEF | VERBOSE | WHERE clause | YIELD clause]
case class ShowConstraintsCommand(
  constraintType: ShowConstraintType,
  columns: List[ShowColumn],
  yieldColumns: List[CommandResultItem]
) extends Command(columns, yieldColumns) {

  override def originalNameRows(state: QueryState, baseRow: CypherRow): ClosingIterator[Map[String, AnyValue]] = {
    val ctx = state.query
    ctx.assertShowConstraintAllowed()
    val constraints = ctx.getAllConstraints()
    val indexIdToName = ctx.getAllIndexes()
      .map { case (descriptor, _) => descriptor.getId -> descriptor.getName }

    val predicate: ConstraintDescriptor => Boolean = constraintType match {
      case UniqueConstraints => c => c.`type`().equals(schema.ConstraintType.UNIQUE)
      case NodeUniqueConstraints =>
        c => c.`type`().equals(schema.ConstraintType.UNIQUE) && c.schema.entityType.equals(EntityType.NODE)
      case RelUniqueConstraints =>
        c => c.`type`().equals(schema.ConstraintType.UNIQUE) && c.schema.entityType.equals(EntityType.RELATIONSHIP)
      case KeyConstraints => c => c.`type`().equals(schema.ConstraintType.UNIQUE_EXISTS)
      case NodeKeyConstraints =>
        c => c.`type`().equals(schema.ConstraintType.UNIQUE_EXISTS) && c.schema.entityType.equals(EntityType.NODE)
      case RelKeyConstraints => c =>
          c.`type`().equals(schema.ConstraintType.UNIQUE_EXISTS) && c.schema.entityType.equals(EntityType.RELATIONSHIP)
      case _: ExistsConstraints => c => c.`type`().equals(schema.ConstraintType.EXISTS)
      case _: NodeExistsConstraints =>
        c => c.`type`().equals(schema.ConstraintType.EXISTS) && c.schema.entityType.equals(EntityType.NODE)
      case _: RelExistsConstraints =>
        c => c.`type`().equals(schema.ConstraintType.EXISTS) && c.schema.entityType.equals(EntityType.RELATIONSHIP)
      case PropTypeConstraints => c => c.`type`().equals(schema.ConstraintType.PROPERTY_TYPE)
      case NodePropTypeConstraints =>
        c => c.`type`().equals(schema.ConstraintType.PROPERTY_TYPE) && c.schema.entityType.equals(EntityType.NODE)
      case RelPropTypeConstraints =>
        c =>
          c.`type`().equals(schema.ConstraintType.PROPERTY_TYPE) && c.schema.entityType.equals(EntityType.RELATIONSHIP)
      case AllConstraints => _ => true // Should keep all and not filter away any constraints
      case c              => throw new IllegalStateException(s"Unknown constraint type: $c")
    }

    val relevantConstraints = constraints.filter {
      case (constraintDescriptor, _) => predicate(constraintDescriptor)
    }
    val sortedRelevantConstraints: ListMap[ConstraintDescriptor, ConstraintInfo] =
      ListMap(relevantConstraints.toSeq.sortBy(_._1.getName): _*)

    val rows = sortedRelevantConstraints.map {
      case (constraintDescriptor: ConstraintDescriptor, constraintInfo: ConstraintInfo) =>
        val propertyType =
          if (
            constraintDescriptor.isPropertyTypeConstraint &&
            // only fetch value if we need it
            (requestedColumnsNames.contains(propertyTypeColumn) ||
              requestedColumnsNames.contains(createStatementColumn))
          )
            Some(
              constraintDescriptor.asPropertyTypeConstraint().propertyType().userDescription()
            )
          else None
        // These don't really have a default/fallback and is used in multiple columns
        // so let's keep them as is regardless of if they are actually needed or not
        val entityType = constraintDescriptor.schema.entityType
        val constraintType = getConstraintType(constraintDescriptor.`type`, entityType)

        val (options, createString) =
          getOptionsAndCreateString(constraintDescriptor, constraintInfo, propertyType, constraintType)

        requestedColumnsNames.map {
          // The id of the constraint
          case `idColumn` => idColumn -> Values.longValue(constraintDescriptor.getId)
          // Name of the constraint, for example "myConstraint"
          case `nameColumn` => nameColumn -> Values.stringValue(constraintDescriptor.getName)
          // The ConstraintType of this constraint, one of "UNIQUENESS", "RELATIONSHIP_UNIQUENESS", "NODE_KEY", "RELATIONSHIP_KEY", "NODE_PROPERTY_EXISTENCE", "RELATIONSHIP_PROPERTY_EXISTENCE"
          case `typeColumn` => typeColumn -> Values.stringValue(constraintType.output)
          // Type of entities this constraint represents, either "NODE" or "RELATIONSHIP"
          case `entityTypeColumn` => entityTypeColumn -> Values.stringValue(entityType.name)
          // The labels or relationship types of this constraint, for example ["Label1", "Label2"] or ["RelType1", "RelType2"]
          case `labelsOrTypesColumn` => labelsOrTypesColumn -> VirtualValues.fromList(
              constraintInfo.labelsOrTypes.map(elem => Values.of(elem).asInstanceOf[AnyValue]).asJava
            )
          // The properties of this constraint, for example ["propKey", "propKey2"]
          case `propertiesColumn` => propertiesColumn -> VirtualValues.fromList(
              constraintInfo.properties.map(prop => Values.of(prop).asInstanceOf[AnyValue]).asJava
            )
          // The name of the index associated to the constraint
          case `ownedIndexColumn` =>
            val ownedIndex =
              if (constraintDescriptor.isIndexBackedConstraint)
                indexIdToName.get(constraintDescriptor.asIndexBackedConstraint().ownedIndexId())
                  .map(Values.stringValue)
                  .getOrElse(Values.NO_VALUE)
              else Values.NO_VALUE
            ownedIndexColumn -> ownedIndex
          // The Cypher type this constraint restricts its property to
          case `propertyTypeColumn` =>
            propertyTypeColumn -> propertyType.map(Values.stringValue).getOrElse(Values.NO_VALUE)
          // The options for this constraint, shows index provider and config of the backing index
          case `optionsColumn` => optionsColumn -> options
          // The statement to recreate the constraint
          case `createStatementColumn` => createStatementColumn -> createString
          case unknown                 =>
            // This match should cover all existing columns but we get scala warnings
            // on non-exhaustive match due to it being string values
            throw new IllegalStateException(s"Missing case for column: $unknown")
        }.toMap[String, AnyValue]
    }
    val updatedRows = updateRowsWithPotentiallyRenamedColumns(rows.toList)
    ClosingIterator.apply(updatedRows.iterator)
  }

  private def getOptionsAndCreateString(
    constraintDescriptor: ConstraintDescriptor,
    constraintInfo: ConstraintInfo,
    propertyType: Option[String],
    constraintType: ShowConstraintType
  ) = {
    // Options and create statement share lot of their values
    // let's fetch both together if at least one is used
    if (requestedColumnsNames.contains(optionsColumn) || requestedColumnsNames.contains(createStatementColumn)) {
      val name = constraintDescriptor.getName
      val (options, createString) = {
        if (constraintDescriptor.isIndexBackedConstraint) {
          val index = constraintInfo.maybeIndex.getOrElse(
            throw new IllegalStateException(s"Expected to find an index for index backed constraint $name")
          )
          val providerName = index.getIndexProvider.name
          val indexConfig = index.getIndexConfig
          val options: MapValue = extractOptionsMap(providerName, indexConfig)
          val createWithOptions = createConstraintStatement(
            name,
            constraintType,
            constraintInfo.labelsOrTypes,
            constraintInfo.properties,
            Some(providerName),
            Some(indexConfig)
          )
          (options, createWithOptions)
        } else {
          val createWithoutOptions = createConstraintStatement(
            name,
            constraintType,
            constraintInfo.labelsOrTypes,
            constraintInfo.properties,
            propertyType = propertyType
          )
          (Values.NO_VALUE, createWithoutOptions)
        }
      }
      (options, Values.stringValue(createString))
    } else (Values.NO_VALUE, Values.NO_VALUE)
  }
}

object ShowConstraintsCommand {

  private def createConstraintStatement(
    name: String,
    constraintType: ShowConstraintType,
    labelsOrTypes: List[String],
    properties: List[String],
    providerName: Option[String] = None,
    indexConfig: Option[IndexConfig] = None,
    propertyType: Option[String] = None
  ): String = {
    val labelsOrTypesWithColons = asEscapedString(labelsOrTypes, colonStringJoiner)
    val escapedName = escapeBackticks(name)
    constraintType match {
      case NodeUniqueConstraints =>
        val escapedProperties = asEscapedString(properties, propStringJoiner)
        val options = extractOptionsString(providerName, indexConfig, NodeUniqueConstraints.prettyPrint)
        s"CREATE CONSTRAINT `$escapedName` FOR (n$labelsOrTypesWithColons) REQUIRE ($escapedProperties) IS UNIQUE OPTIONS $options"
      case RelUniqueConstraints =>
        val escapedProperties = asEscapedString(properties, relPropStringJoiner)
        val options = extractOptionsString(providerName, indexConfig, RelUniqueConstraints.prettyPrint)
        s"CREATE CONSTRAINT `$escapedName` FOR ()-[r$labelsOrTypesWithColons]-() REQUIRE ($escapedProperties) IS UNIQUE OPTIONS $options"
      case NodeKeyConstraints =>
        val escapedProperties = asEscapedString(properties, propStringJoiner)
        val options = extractOptionsString(providerName, indexConfig, NodeKeyConstraints.prettyPrint)
        s"CREATE CONSTRAINT `$escapedName` FOR (n$labelsOrTypesWithColons) REQUIRE ($escapedProperties) IS NODE KEY OPTIONS $options"
      case RelKeyConstraints =>
        val escapedProperties = asEscapedString(properties, relPropStringJoiner)
        val options = extractOptionsString(providerName, indexConfig, RelKeyConstraints.prettyPrint)
        s"CREATE CONSTRAINT `$escapedName` FOR ()-[r$labelsOrTypesWithColons]-() REQUIRE ($escapedProperties) IS RELATIONSHIP KEY OPTIONS $options"
      case _: NodeExistsConstraints =>
        val escapedProperties = asEscapedString(properties, propStringJoiner)
        s"CREATE CONSTRAINT `$escapedName` FOR (n$labelsOrTypesWithColons) REQUIRE ($escapedProperties) IS NOT NULL"
      case _: RelExistsConstraints =>
        val escapedProperties = asEscapedString(properties, relPropStringJoiner)
        s"CREATE CONSTRAINT `$escapedName` FOR ()-[r$labelsOrTypesWithColons]-() REQUIRE ($escapedProperties) IS NOT NULL"
      case NodePropTypeConstraints =>
        val escapedProperties = asEscapedString(properties, propStringJoiner)
        val typeString = propertyType.getOrElse(
          throw new IllegalArgumentException(s"Expected a property type for $constraintType constraint.")
        )
        s"CREATE CONSTRAINT `$escapedName` FOR (n$labelsOrTypesWithColons) REQUIRE ($escapedProperties) IS :: $typeString"
      case RelPropTypeConstraints =>
        val escapedProperties = asEscapedString(properties, relPropStringJoiner)
        val typeString = propertyType.getOrElse(
          throw new IllegalArgumentException(s"Expected a property type for $constraintType constraint.")
        )
        s"CREATE CONSTRAINT `$escapedName` FOR ()-[r$labelsOrTypesWithColons]-() REQUIRE ($escapedProperties) IS :: $typeString"
      case _ => throw new IllegalArgumentException(
          s"Did not expect constraint type ${constraintType.prettyPrint} for constraint create command."
        )
    }
  }

  private def extractOptionsString(
    maybeProviderName: Option[String],
    maybeIndexConfig: Option[IndexConfig],
    constraintType: String
  ): String = {
    val providerName = maybeProviderName.getOrElse(
      throw new IllegalArgumentException(s"Expected a provider name for $constraintType constraint.")
    )
    val indexConfig = maybeIndexConfig.getOrElse(
      throw new IllegalArgumentException(s"Expected an index configuration for $constraintType constraint.")
    )
    val btreeOrEmptyConfig = configAsString(indexConfig, value => pointConfigValueAsString(value))
    optionsAsString(providerName, btreeOrEmptyConfig)
  }

  private def getConstraintType(
    internalConstraintType: schema.ConstraintType,
    entityType: EntityType
  ): ShowConstraintType = {
    (internalConstraintType, entityType) match {
      case (schema.ConstraintType.UNIQUE, EntityType.NODE)                => NodeUniqueConstraints
      case (schema.ConstraintType.UNIQUE, EntityType.RELATIONSHIP)        => RelUniqueConstraints
      case (schema.ConstraintType.UNIQUE_EXISTS, EntityType.NODE)         => NodeKeyConstraints
      case (schema.ConstraintType.UNIQUE_EXISTS, EntityType.RELATIONSHIP) => RelKeyConstraints
      case (schema.ConstraintType.EXISTS, EntityType.NODE)                => NodeExistsConstraints()
      case (schema.ConstraintType.EXISTS, EntityType.RELATIONSHIP)        => RelExistsConstraints()
      case (schema.ConstraintType.PROPERTY_TYPE, EntityType.NODE)         => NodePropTypeConstraints
      case (schema.ConstraintType.PROPERTY_TYPE, EntityType.RELATIONSHIP) => RelPropTypeConstraints
      case _ => throw new IllegalStateException(
          s"Invalid constraint combination: ConstraintType $internalConstraintType and EntityType $entityType."
        )
    }
  }
}
