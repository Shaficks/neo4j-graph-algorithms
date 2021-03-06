package org.neo4j.graphalgo;

import algo.Pools;
import org.neo4j.graphalgo.core.ProcedureConfiguration;
import org.neo4j.graphalgo.core.sources.BothRelationshipAdapter;
import org.neo4j.graphalgo.core.sources.BufferedWeightMap;
import org.neo4j.graphalgo.core.sources.LazyIdMapper;
import org.neo4j.graphalgo.core.utils.ProgressTimer;
import org.neo4j.graphalgo.core.utils.container.RelationshipContainer;
import org.neo4j.graphalgo.impl.MSTPrim;
import org.neo4j.graphalgo.impl.MSTPrimExporter;
import org.neo4j.graphalgo.results.MSTPrimResult;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.Log;
import org.neo4j.procedure.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

/**
 * @author mknblch
 */
public class MSTPrimProc {

    public static final String CONFIG_WRITE_RELATIONSHIP = "writeProperty";
    public static final String CONFIG_WRITE_RELATIONSHIP_DEFAULT = "mst";

    @Context
    public GraphDatabaseAPI api;

    @Context
    public Log log;

    @Procedure(value = "algo.mst", mode = Mode.WRITE)
    @Description("CALL algo.mst(node:Node, property:String, {nodeLabelOrQuery:String, relationshipTypeOrQuery:String, " +
            "write:boolean, writeProperty:String stats:boolean}) " +
            "YIELD loadDuration, evalDuration, writeDuration, weightSum, weightMin, weightMax, relationshipCount")
    public Stream<MSTPrimResult> mst(
            @Name("startNode") Node startNode,
            @Name(value = "property") String propertyName,
            @Name(value = "config", defaultValue = "{}") Map<String, Object> config) {

        ProcedureConfiguration configuration = ProcedureConfiguration.create(config);

        LazyIdMapper idMapper = LazyIdMapper.importer(api)
                .withWeightsFromProperty(propertyName, 1.0)
                .withOptionalLabel(configuration.getNodeLabelOrQuery())
                .withOptionalRelationshipType(configuration.getRelationshipOrQuery())
                .build();

        MSTPrimResult.Builder builder = MSTPrimResult.builder();

        CompletableFuture<BufferedWeightMap> weightMap = BufferedWeightMap.importer(api)
                .withIdMapping(idMapper)
                .withAnyDirection(true)
                .withWeightsFromProperty(propertyName, 1.0)
                .withOptionalLabel(configuration.getNodeLabelOrQuery())
                .withOptionalRelationshipType(configuration.getRelationshipOrQuery())
                .buildDelayed(Pools.DEFAULT);

        CompletableFuture<RelationshipContainer> relationshipContainer = RelationshipContainer.importer(api)
                .withIdMapping(idMapper)
                .withDirection(Direction.BOTH)
                .withOptionalLabel(configuration.getNodeLabelOrQuery())
                .withOptionalRelationshipType(configuration.getRelationshipOrQuery())
                .buildDelayed(Pools.DEFAULT);

        int startNodeId = idMapper.toMappedNodeId(startNode.getId());

        RelationshipContainer container;
        BufferedWeightMap weights;
        try(ProgressTimer timer = builder.timeLoad()) {
            container = relationshipContainer.get();
            weights = weightMap.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }

        final MSTPrim mstPrim = new MSTPrim(
                idMapper,
                new BothRelationshipAdapter(container),
                weights);

        builder.timeEval(() -> {
            mstPrim.compute(startNodeId);
            if (configuration.isStatsFlag()) {
                MSTPrim.MinimumSpanningTree.Aggregator aggregator =
                        mstPrim.getMinimumSpanningTree().aggregate();
                builder.withWeightMax(aggregator.getMax())
                        .withWeightMin(aggregator.getMin())
                        .withWeightSum(aggregator.getSum())
                        .withRelationshipCount(aggregator.getCount());
            }
        });

        if (configuration.isWriteFlag()) {
            builder.timeWrite(() -> {
                new MSTPrimExporter(api)
                        .withIdMapping(idMapper)
                        .withWriteRelationship(configuration.get(CONFIG_WRITE_RELATIONSHIP, CONFIG_WRITE_RELATIONSHIP_DEFAULT))
                        .write(mstPrim.getMinimumSpanningTree());
            });
        }

        return Stream.of(builder.build());
    }
}
