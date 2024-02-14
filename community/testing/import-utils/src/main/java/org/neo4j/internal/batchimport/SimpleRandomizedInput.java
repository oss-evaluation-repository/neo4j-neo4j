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
package org.neo4j.internal.batchimport;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.neo4j.common.EntityType.NODE;
import static org.neo4j.common.EntityType.RELATIONSHIP;
import static org.neo4j.internal.helpers.collection.Iterators.single;
import static org.neo4j.internal.kernel.api.IndexQueryConstraints.unconstrained;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.common.EntityType;
import org.neo4j.csv.reader.Configuration;
import org.neo4j.csv.reader.Extractors;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.batchimport.input.Collector;
import org.neo4j.internal.batchimport.input.DataGeneratorInput;
import org.neo4j.internal.batchimport.input.Groups;
import org.neo4j.internal.batchimport.input.IdType;
import org.neo4j.internal.batchimport.input.Input;
import org.neo4j.internal.batchimport.input.InputChunk;
import org.neo4j.internal.batchimport.input.InputEntity;
import org.neo4j.internal.batchimport.input.PropertySizeCalculator;
import org.neo4j.internal.batchimport.input.ReadableGroups;
import org.neo4j.internal.batchimport.input.csv.Header.Entry;
import org.neo4j.internal.batchimport.input.csv.Type;
import org.neo4j.internal.kernel.api.TokenPredicate;
import org.neo4j.internal.kernel.api.TokenRead;
import org.neo4j.internal.kernel.api.exceptions.schema.IndexNotFoundKernelException;
import org.neo4j.internal.schema.SchemaDescriptors;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;

public class SimpleRandomizedInput implements Input {
    private static final String ID_KEY = "id";

    private final Input actual;
    private final long nodeCount;
    private final long relationshipCount;

    public SimpleRandomizedInput(
            long seed,
            long nodeCount,
            long relationshipCount,
            float factorBadNodeData,
            float factorBadRelationshipData) {
        this.nodeCount = nodeCount;
        this.relationshipCount = relationshipCount;
        var dataDistribution = DataGeneratorInput.data(nodeCount, relationshipCount)
                .withLabelCount(4)
                .withRelationshipTypeCount(4)
                .withFactorBadNodeData(factorBadNodeData)
                .withFactorBadRelationshipData(factorBadRelationshipData);
        var idType = IdType.INTEGER;
        var extractors = new Extractors(Configuration.COMMAS.arrayDelimiter());
        var groups = new Groups();
        var group = groups.getOrCreate(null);
        actual = new DataGeneratorInput(
                dataDistribution,
                idType,
                seed,
                DataGeneratorInput.bareboneNodeHeader(ID_KEY, idType, group, extractors),
                DataGeneratorInput.bareboneRelationshipHeader(
                        idType,
                        group,
                        extractors,
                        new Entry(SimpleRandomizedInput.ID_KEY, Type.PROPERTY, null, extractors.int_())),
                groups);
    }

    @Override
    public InputIterable nodes(Collector badCollector) {
        return actual.nodes(badCollector);
    }

    @Override
    public InputIterable relationships(Collector badCollector) {
        return actual.relationships(badCollector);
    }

    @Override
    public IdType idType() {
        return actual.idType();
    }

    @Override
    public ReadableGroups groups() {
        return actual.groups();
    }

    public void verify(GraphDatabaseService db) throws IOException {
        verify(db, false);
    }

    public void verifyWithTokenIndexes(GraphDatabaseService db) throws IOException {
        verify(db, true);
    }

    public void verify(GraphDatabaseService db, boolean verifyIndex) throws IOException {
        Map<Number, InputEntity> expectedNodeData = new HashMap<>();
        Map<Integer, List<Long>> expectedLabelIndexData = new HashMap<>();
        try (InputIterator nodes = nodes(Collector.EMPTY).iterator();
                InputChunk chunk = nodes.newChunk();
                Transaction tx = db.beginTx()) {
            Number lastId = null;
            InputEntity node;
            TokenRead tokenRead = ((InternalTransaction) tx).kernelTransaction().tokenRead();
            while (nodes.next(chunk)) {
                while (chunk.next(node = new InputEntity())) {
                    Number id = (Number) node.id();
                    if (lastId == null || id.longValue() > lastId.longValue()) {
                        expectedNodeData.put(id, node);
                        for (String label : node.labels()) {
                            int labelId = tokenRead.nodeLabel(label);
                            expectedLabelIndexData
                                    .computeIfAbsent(labelId, labelToken -> new ArrayList<>())
                                    .add((Long) id);
                        }
                        lastId = id;
                    }
                }
            }
        }
        Map<RelationshipKey, Set<InputEntity>> expectedRelationshipData = new HashMap<>();
        try (InputIterator relationships = relationships(Collector.EMPTY).iterator();
                InputChunk chunk = relationships.newChunk()) {
            while (relationships.next(chunk)) {
                InputEntity relationship;
                while (chunk.next(relationship = new InputEntity())) {
                    RelationshipKey key =
                            new RelationshipKey(relationship.startId(), relationship.stringType, relationship.endId());
                    expectedRelationshipData
                            .computeIfAbsent(key, k -> new HashSet<>())
                            .add(relationship);
                }
            }
        }

        try (Transaction tx = db.beginTx();
                ResourceIterable<Relationship> allRelationships = tx.getAllRelationships()) {
            long actualRelationshipCount = 0;
            for (Relationship relationship : allRelationships) {
                RelationshipKey key = keyOf(relationship);
                Set<InputEntity> matches = expectedRelationshipData.get(key);
                assertNotNull(matches);
                InputEntity matchingRelationship = relationshipWithId(matches, relationship);
                assertNotNull(matchingRelationship);
                assertTrue(matches.remove(matchingRelationship));
                if (matches.isEmpty()) {
                    expectedRelationshipData.remove(key);
                }
                actualRelationshipCount++;
            }
            if (!expectedRelationshipData.isEmpty()) {
                fail(format(
                        "Imported db is missing %d/%d relationships: %s",
                        expectedRelationshipData.size(), relationshipCount, expectedRelationshipData));
            }

            long actualNodeCount = 0;
            try (ResourceIterable<Node> allNodes = tx.getAllNodes()) {
                for (Node node : allNodes) {
                    assertNotNull(expectedNodeData.remove(node.getProperty(ID_KEY)));
                    actualNodeCount++;
                }
            }
            if (!expectedNodeData.isEmpty()) {
                fail(format(
                        "Imported db is missing %d/%d nodes: %s",
                        expectedNodeData.size(), nodeCount, expectedNodeData));
            }
            assertEquals(nodeCount, actualNodeCount);
            assertEquals(relationshipCount, actualRelationshipCount);
            tx.commit();
        }

        if (verifyIndex) {
            Map<Integer, List<Long>> expectedRelationshipIndexData = new HashMap<>();
            try (Transaction tx = db.beginTx();
                    ResourceIterable<Relationship> allRelationships = tx.getAllRelationships()) {
                TokenRead tokenRead =
                        ((InternalTransaction) tx).kernelTransaction().tokenRead();
                allRelationships.forEach(relationship -> {
                    int relTypeId =
                            tokenRead.relationshipType(relationship.getType().name());
                    expectedRelationshipIndexData
                            .computeIfAbsent(relTypeId, relToken -> new ArrayList<>())
                            .add(relationship.getId());
                });
            }
            verifyIndex(NODE, expectedLabelIndexData, db);
            verifyIndex(RELATIONSHIP, expectedRelationshipIndexData, db);
        }
    }

    private static void verifyIndex(EntityType entity, Map<Integer, List<Long>> expectedIds, GraphDatabaseService db) {
        try {
            try (var tx = db.beginTx()) {
                var internalTx = (InternalTransaction) tx;
                var ktx = internalTx.kernelTransaction();
                var schemaRead = ktx.schemaRead();
                var index = single(schemaRead.index(SchemaDescriptors.forAnyEntityTokens(entity)));
                var session = ktx.dataRead().tokenReadSession(index);
                try (var nodeCursor = ktx.cursors().allocateNodeLabelIndexCursor(CursorContext.NULL_CONTEXT);
                        var relationshipCursor =
                                ktx.cursors().allocateRelationshipTypeIndexCursor(CursorContext.NULL_CONTEXT)) {
                    expectedIds.forEach((tokenId, expectedEntities) -> {
                        try {
                            List<Long> actualEntities = new ArrayList<>();
                            if (entity == NODE) {
                                ktx.dataRead()
                                        .nodeLabelScan(
                                                session,
                                                nodeCursor,
                                                unconstrained(),
                                                new TokenPredicate(tokenId),
                                                CursorContext.NULL_CONTEXT);
                                while (nodeCursor.next()) {
                                    actualEntities.add(nodeCursor.nodeReference());
                                }
                            } else {
                                ktx.dataRead()
                                        .relationshipTypeScan(
                                                session,
                                                relationshipCursor,
                                                unconstrained(),
                                                new TokenPredicate(tokenId),
                                                CursorContext.NULL_CONTEXT);
                                while (relationshipCursor.next()) {
                                    actualEntities.add(relationshipCursor.relationshipReference());
                                }
                            }
                            expectedEntities.sort(Long::compareTo);
                            actualEntities.sort(Long::compareTo);
                            assertEquals(expectedEntities, actualEntities);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            }
        } catch (IndexNotFoundKernelException e) {
            throw new RuntimeException(e);
        }
    }

    private static InputEntity relationshipWithId(Set<InputEntity> matches, Relationship relationship) {
        Map<String, Object> dbProperties = relationship.getAllProperties();
        for (InputEntity candidate : matches) {
            if (dbProperties.equals(propertiesOf(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, Object> propertiesOf(InputEntity entity) {
        Map<String, Object> result = new HashMap<>();
        Object[] properties = entity.properties();
        for (int i = 0; i < properties.length; i++) {
            result.put((String) properties[i++], properties[i]);
        }
        return result;
    }

    private static class RelationshipKey {
        final Object startId;
        final String type;
        final Object endId;

        RelationshipKey(Object startId, String type, Object endId) {
            this.startId = startId;
            this.type = type;
            this.endId = endId;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((endId == null) ? 0 : endId.hashCode());
            result = prime * result + ((startId == null) ? 0 : startId.hashCode());
            result = prime * result + ((type == null) ? 0 : type.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            RelationshipKey other = (RelationshipKey) obj;
            if (endId == null) {
                if (other.endId != null) {
                    return false;
                }
            } else if (!endId.equals(other.endId)) {
                return false;
            }
            if (startId == null) {
                if (other.startId != null) {
                    return false;
                }
            } else if (!startId.equals(other.startId)) {
                return false;
            }
            if (type == null) {
                return other.type == null;
            } else {
                return type.equals(other.type);
            }
        }
    }

    private static RelationshipKey keyOf(Relationship relationship) {
        return new RelationshipKey(
                relationship.getStartNode().getProperty(ID_KEY),
                relationship.getType().name(),
                relationship.getEndNode().getProperty(ID_KEY));
    }

    @Override
    public Estimates calculateEstimates(PropertySizeCalculator valueSizeCalculator) throws IOException {
        return Input.knownEstimates(nodeCount, relationshipCount, 0, 0, 0, 0, 0);
    }
}
