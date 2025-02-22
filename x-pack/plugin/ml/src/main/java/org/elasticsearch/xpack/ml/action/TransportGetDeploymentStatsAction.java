/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.action.TaskOperationFailure;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.tasks.TransportTasksAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.action.util.ExpandedIdsMatcher;
import org.elasticsearch.xpack.core.ml.action.GetDeploymentStatsAction;
import org.elasticsearch.xpack.core.ml.inference.assignment.AssignmentState;
import org.elasticsearch.xpack.core.ml.inference.assignment.AssignmentStats;
import org.elasticsearch.xpack.core.ml.inference.assignment.RoutingInfo;
import org.elasticsearch.xpack.core.ml.inference.assignment.RoutingState;
import org.elasticsearch.xpack.core.ml.inference.assignment.TrainedModelAssignment;
import org.elasticsearch.xpack.ml.inference.assignment.TrainedModelAssignmentMetadata;
import org.elasticsearch.xpack.ml.inference.deployment.ModelStats;
import org.elasticsearch.xpack.ml.inference.deployment.TrainedModelDeploymentTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TransportGetDeploymentStatsAction extends TransportTasksAction<
    TrainedModelDeploymentTask,
    GetDeploymentStatsAction.Request,
    GetDeploymentStatsAction.Response,
    AssignmentStats> {

    @Inject
    public TransportGetDeploymentStatsAction(
        TransportService transportService,
        ActionFilters actionFilters,
        ClusterService clusterService
    ) {
        super(
            GetDeploymentStatsAction.NAME,
            clusterService,
            transportService,
            actionFilters,
            GetDeploymentStatsAction.Request::new,
            GetDeploymentStatsAction.Response::new,
            AssignmentStats::new,
            ThreadPool.Names.MANAGEMENT
        );
    }

    @Override
    protected GetDeploymentStatsAction.Response newResponse(
        GetDeploymentStatsAction.Request request,
        List<AssignmentStats> taskResponse,
        List<TaskOperationFailure> taskOperationFailures,
        List<FailedNodeException> failedNodeExceptions
    ) {
        // group the stats by model and merge individual node stats
        var mergedNodeStatsByModel = taskResponse.stream()
            .collect(Collectors.toMap(AssignmentStats::getModelId, Function.identity(), (l, r) -> {
                l.getNodeStats().addAll(r.getNodeStats());
                return l;
            }, TreeMap::new));

        List<AssignmentStats> bunchedAndSorted = new ArrayList<>(mergedNodeStatsByModel.values());

        return new GetDeploymentStatsAction.Response(
            taskOperationFailures,
            failedNodeExceptions,
            bunchedAndSorted,
            bunchedAndSorted.size()
        );
    }

    @Override
    protected void doExecute(
        Task task,
        GetDeploymentStatsAction.Request request,
        ActionListener<GetDeploymentStatsAction.Response> listener
    ) {
        final ClusterState clusterState = clusterService.state();
        final TrainedModelAssignmentMetadata assignment = TrainedModelAssignmentMetadata.fromState(clusterState);

        String[] tokenizedRequestIds = Strings.tokenizeToStringArray(request.getDeploymentId(), ",");
        ExpandedIdsMatcher.SimpleIdsMatcher idsMatcher = new ExpandedIdsMatcher.SimpleIdsMatcher(tokenizedRequestIds);

        List<String> matchedDeploymentIds = new ArrayList<>();
        Set<String> taskNodes = new HashSet<>();
        Map<TrainedModelAssignment, Map<String, RoutingInfo>> assignmentNonStartedRoutes = new HashMap<>();
        for (var assignmentEntry : assignment.modelAssignments().entrySet()) {
            String modelId = assignmentEntry.getKey();
            if (idsMatcher.idMatches(modelId)) {
                matchedDeploymentIds.add(modelId);

                taskNodes.addAll(Arrays.asList(assignmentEntry.getValue().getStartedNodes()));

                Map<String, RoutingInfo> routings = assignmentEntry.getValue()
                    .getNodeRoutingTable()
                    .entrySet()
                    .stream()
                    .filter(routingEntry -> RoutingState.STARTED.equals(routingEntry.getValue().getState()) == false)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

                assignmentNonStartedRoutes.put(assignmentEntry.getValue(), routings);
            }
        }

        if (matchedDeploymentIds.isEmpty()) {
            listener.onResponse(
                new GetDeploymentStatsAction.Response(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0L)
            );
            return;
        }

        request.setNodes(taskNodes.toArray(String[]::new));
        request.setExpandedIds(matchedDeploymentIds);

        ActionListener<GetDeploymentStatsAction.Response> addFailedListener = listener.delegateFailure((l, response) -> {
            var updatedResponse = addFailedRoutes(response, assignmentNonStartedRoutes, clusterState.nodes());
            // Set the allocation state and reason if we have it
            for (AssignmentStats stats : updatedResponse.getStats().results()) {
                TrainedModelAssignment trainedModelAssignment = assignment.getModelAssignment(stats.getModelId());
                if (trainedModelAssignment != null) {
                    stats.setState(trainedModelAssignment.getAssignmentState()).setReason(trainedModelAssignment.getReason().orElse(null));
                    if (trainedModelAssignment.getNodeRoutingTable()
                        .values()
                        .stream()
                        .allMatch(ri -> ri.getState().equals(RoutingState.FAILED))) {
                        stats.setState(AssignmentState.FAILED);
                        if (stats.getReason() == null) {
                            stats.setReason("All node routes are failed; see node route reason for details");
                        }
                    }
                    if (trainedModelAssignment.getAssignmentState().isAnyOf(AssignmentState.STARTED, AssignmentState.STARTING)) {
                        stats.setAllocationStatus(trainedModelAssignment.calculateAllocationStatus().orElse(null));
                    }
                }
            }
            l.onResponse(updatedResponse);
        });

        super.doExecute(task, request, addFailedListener);
    }

    /**
     * Update the collected task responses with the non-started
     * assignment information. The result is the task responses
     * merged with the non-started model assignments.
     *
     * Where there is a merge collision for the pair {@code <model_id, node_id>}
     * the non-started assignments are used.
     *
     * @param tasksResponse All the responses from the tasks
     * @param assignmentNonStartedRoutes Non-started routes
     * @param nodes current cluster nodes
     * @return The result of merging tasksResponse and the non-started routes
     */
    static GetDeploymentStatsAction.Response addFailedRoutes(
        GetDeploymentStatsAction.Response tasksResponse,
        Map<TrainedModelAssignment, Map<String, RoutingInfo>> assignmentNonStartedRoutes,
        DiscoveryNodes nodes
    ) {
        final Map<String, TrainedModelAssignment> modelToAssignmentWithNonStartedRoutes = assignmentNonStartedRoutes.keySet()
            .stream()
            .collect(Collectors.toMap(TrainedModelAssignment::getModelId, Function.identity()));

        final List<AssignmentStats> updatedAssignmentStats = new ArrayList<>();

        for (AssignmentStats stat : tasksResponse.getStats().results()) {
            if (modelToAssignmentWithNonStartedRoutes.containsKey(stat.getModelId())) {
                // there is merging to be done
                Map<String, RoutingInfo> nodeToRoutingStates = assignmentNonStartedRoutes.get(
                    modelToAssignmentWithNonStartedRoutes.get(stat.getModelId())
                );
                List<AssignmentStats.NodeStats> updatedNodeStats = new ArrayList<>();

                Set<String> visitedNodes = new HashSet<>();
                for (var nodeStat : stat.getNodeStats()) {
                    if (nodeToRoutingStates.containsKey(nodeStat.getNode().getId())) {
                        // conflict as there is both a task response for the model/node pair
                        // and we have a non-started routing entry.
                        // Prefer the entry from assignmentNonStartedRoutes as we cannot be sure
                        // of the state of the task - it may be starting, started, stopping, or stopped.
                        RoutingInfo routingInfo = nodeToRoutingStates.get(nodeStat.getNode().getId());
                        updatedNodeStats.add(
                            AssignmentStats.NodeStats.forNotStartedState(
                                nodeStat.getNode(),
                                routingInfo.getState(),
                                routingInfo.getReason()
                            )
                        );
                    } else {
                        updatedNodeStats.add(nodeStat);
                    }

                    visitedNodes.add(nodeStat.getNode().getId());
                }

                // add nodes from the failures that were not in the task responses
                for (var nodeRoutingState : nodeToRoutingStates.entrySet()) {
                    if (visitedNodes.contains(nodeRoutingState.getKey()) == false) {
                        updatedNodeStats.add(
                            AssignmentStats.NodeStats.forNotStartedState(
                                nodes.get(nodeRoutingState.getKey()),
                                nodeRoutingState.getValue().getState(),
                                nodeRoutingState.getValue().getReason()
                            )
                        );
                    }
                }

                updatedNodeStats.sort(Comparator.comparing(n -> n.getNode().getId()));
                updatedAssignmentStats.add(
                    new AssignmentStats(
                        stat.getModelId(),
                        stat.getThreadsPerAllocation(),
                        stat.getNumberOfAllocations(),
                        stat.getQueueCapacity(),
                        stat.getCacheSize(),
                        stat.getStartTime(),
                        updatedNodeStats
                    )
                );
            } else {
                updatedAssignmentStats.add(stat);
            }
        }

        // Merge any models in the non-started that were not in the task responses
        for (var nonStartedEntries : assignmentNonStartedRoutes.entrySet()) {
            final TrainedModelAssignment assignment = nonStartedEntries.getKey();
            final String modelId = assignment.getTaskParams().getModelId();
            if (tasksResponse.getStats().results().stream().anyMatch(e -> modelId.equals(e.getModelId())) == false) {

                // no tasks for this model so build the assignment stats from the non-started states
                List<AssignmentStats.NodeStats> nodeStats = new ArrayList<>();

                for (var routingEntry : nonStartedEntries.getValue().entrySet()) {
                    nodeStats.add(
                        AssignmentStats.NodeStats.forNotStartedState(
                            nodes.get(routingEntry.getKey()),
                            routingEntry.getValue().getState(),
                            routingEntry.getValue().getReason()
                        )
                    );
                }

                nodeStats.sort(Comparator.comparing(n -> n.getNode().getId()));

                updatedAssignmentStats.add(new AssignmentStats(modelId, null, null, null, null, assignment.getStartTime(), nodeStats));
            }
        }

        updatedAssignmentStats.sort(Comparator.comparing(AssignmentStats::getModelId));

        return new GetDeploymentStatsAction.Response(
            tasksResponse.getTaskFailures(),
            tasksResponse.getNodeFailures(),
            updatedAssignmentStats,
            updatedAssignmentStats.size()
        );
    }

    @Override
    protected void taskOperation(
        Task actionTask,
        GetDeploymentStatsAction.Request request,
        TrainedModelDeploymentTask task,
        ActionListener<AssignmentStats> listener
    ) {
        Optional<ModelStats> stats = task.modelStats();

        List<AssignmentStats.NodeStats> nodeStats = new ArrayList<>();

        if (stats.isPresent()) {
            var presentValue = stats.get();
            nodeStats.add(
                AssignmentStats.NodeStats.forStartedState(
                    clusterService.localNode(),
                    presentValue.timingStats().getCount(),
                    presentValue.timingStats().getAverage(),
                    presentValue.pendingCount(),
                    presentValue.errorCount(),
                    presentValue.rejectedExecutionCount(),
                    presentValue.timeoutCount(),
                    presentValue.lastUsed(),
                    presentValue.startTime(),
                    presentValue.threadsPerAllocation(),
                    presentValue.numberOfAllocations(),
                    presentValue.peakThroughput(),
                    presentValue.throughputLastPeriod(),
                    presentValue.avgInferenceTimeLastPeriod()
                )
            );
        } else {
            // if there are no stats the process is missing.
            // Either because it is starting or stopped
            nodeStats.add(AssignmentStats.NodeStats.forNotStartedState(clusterService.localNode(), RoutingState.STOPPED, ""));
        }

        TrainedModelAssignment assignment = TrainedModelAssignmentMetadata.fromState(clusterService.state())
            .getModelAssignment(task.getModelId());

        listener.onResponse(
            new AssignmentStats(
                task.getModelId(),
                task.getParams().getThreadsPerAllocation(),
                assignment == null ? task.getParams().getNumberOfAllocations() : assignment.getTaskParams().getNumberOfAllocations(),
                task.getParams().getQueueCapacity(),
                task.getParams().getCacheSize().orElse(null),
                TrainedModelAssignmentMetadata.fromState(clusterService.state()).getModelAssignment(task.getModelId()).getStartTime(),
                nodeStats
            )
        );
    }
}
