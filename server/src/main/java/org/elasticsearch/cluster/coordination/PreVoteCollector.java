/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.coordination;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.coordination.CoordinationState.VoteCollection;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.threadpool.ThreadPool.Names;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportResponseHandler;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import java.util.stream.StreamSupport;

import static org.elasticsearch.common.util.concurrent.ConcurrentCollections.newConcurrentMap;

public class PreVoteCollector {

    private static final Logger logger = LogManager.getLogger(PreVoteCollector.class);

    public static final String REQUEST_PRE_VOTE_ACTION_NAME = "internal:cluster/request_pre_vote";

    private final TransportService transportService;
    private final Runnable startElection;
    private final LongConsumer updateMaxTermSeen;
    private final ElectionStrategy electionStrategy;

    // Tuple for simple atomic updates. null until the first call to `update()`.
    private volatile Tuple<DiscoveryNode, PreVoteResponse> state; // DiscoveryNode component is null if there is currently no known leader.

    PreVoteCollector(final TransportService transportService, final Runnable startElection, final LongConsumer updateMaxTermSeen,
                     final ElectionStrategy electionStrategy) {
        this.transportService = transportService;
        this.startElection = startElection;
        this.updateMaxTermSeen = updateMaxTermSeen;
        this.electionStrategy = electionStrategy;

        // TODO does this need to be on the generic threadpool or can it use SAME?
        transportService.registerRequestHandler(REQUEST_PRE_VOTE_ACTION_NAME, Names.GENERIC, false, false,
            PreVoteRequest::new,
            (request, channel, task) -> channel.sendResponse(handlePreVoteRequest(request)));
    }

    /**
     * Start a new pre-voting round.
     *
     * @param clusterState   the last-accepted cluster state
     * @param broadcastNodes the nodes from whom to request pre-votes
     * @return the pre-voting round, which can be closed to end the round early.
     */
    public Releasable start(final ClusterState clusterState, final Iterable<DiscoveryNode> broadcastNodes) {
        PreVotingRound preVotingRound = new PreVotingRound(clusterState, state.v2().getCurrentTerm());
        preVotingRound.start(broadcastNodes);
        return preVotingRound;
    }

    // only for testing
    PreVoteResponse getPreVoteResponse() {
        return state.v2();
    }

    // only for testing
    @Nullable
    DiscoveryNode getLeader() {
        return state.v1();
    }

    public void update(final PreVoteResponse preVoteResponse, @Nullable final DiscoveryNode leader) {
        logger.trace("updating with preVoteResponse={}, leader={}", preVoteResponse, leader);
        state = new Tuple<>(leader, preVoteResponse);
    }

    /**
     *检查选举请求到版本
     * 1。当前节点不是主节点，直接返回当前节点的请求版本号，上次接收到到请求版本号，以及元数据到版本号
     * 2。如果当前节点是主节点，
     * 3。请求版本大于当前节点，如果当前节点没有同步数据到其他节点，会重新选举，如果已经同步，则拒绝请求
     */
    private PreVoteResponse handlePreVoteRequest(final PreVoteRequest request) {
        updateMaxTermSeen.accept(request.getCurrentTerm());

        Tuple<DiscoveryNode, PreVoteResponse> state = this.state;
        assert state != null : "received pre-vote request before fully initialised";

        final DiscoveryNode leader = state.v1();
        final PreVoteResponse response = state.v2();

        if (leader == null) {
            return response;
        }

        if (leader.equals(request.getSourceNode())) {
            // This is a _rare_ case where our leader has detected a failure and stepped down, but we are still a follower. It's possible
            // that the leader lost its quorum, but while we're still a follower we will not offer joins to any other node so there is no
            // major drawback in offering a join to our old leader. The advantage of this is that it makes it slightly more likely that the
            // leader won't change, and also that its re-election will happen more quickly than if it had to wait for a quorum of followers
            // to also detect its failure.
            return response;
        }

        throw new CoordinationStateRejectedException("rejecting " + request + " as there is already a leader");
    }

    @Override
    public String toString() {
        return "PreVoteCollector{" +
            "state=" + state +
            '}';
    }

    private class PreVotingRound implements Releasable {
        private final Map<DiscoveryNode, PreVoteResponse> preVotesReceived = newConcurrentMap();
        private final AtomicBoolean electionStarted = new AtomicBoolean();
        private final PreVoteRequest preVoteRequest;
        private final ClusterState clusterState;
        private final AtomicBoolean isClosed = new AtomicBoolean();

        PreVotingRound(final ClusterState clusterState, final long currentTerm) {
            this.clusterState = clusterState;
            preVoteRequest = new PreVoteRequest(transportService.getLocalNode(), currentTerm);
        }

        void start(final Iterable<DiscoveryNode> broadcastNodes) {
            assert StreamSupport.stream(broadcastNodes.spliterator(), false).noneMatch(Coordinator::isZen1Node) : broadcastNodes;
            logger.debug("{} requesting pre-votes from {}", this, broadcastNodes);
            broadcastNodes.forEach(n -> transportService.sendRequest(n, REQUEST_PRE_VOTE_ACTION_NAME, preVoteRequest,
                new TransportResponseHandler<PreVoteResponse>() {
                    @Override
                    public PreVoteResponse read(StreamInput in) throws IOException {
                        return new PreVoteResponse(in);
                    }

                    @Override
                    public void handleResponse(PreVoteResponse response) {
                        handlePreVoteResponse(response, n);
                    }

                    @Override
                    public void handleException(TransportException exp) {
                        logger.debug(new ParameterizedMessage("{} failed", this), exp);
                    }

                    @Override
                    public String executor() {
                        return Names.GENERIC;
                    }

                    @Override
                    public String toString() {
                        return "TransportResponseHandler{" + PreVoteCollector.this + ", node=" + n + '}';
                    }
                }));
        }

        /**
         *  1。更新版本信息为最新
         *  2。收集当前投票情况，满足条件则开始选举
         * @param response
         * @param sender
         */
        private void handlePreVoteResponse(final PreVoteResponse response, final DiscoveryNode sender) {
            if (isClosed.get()) {
                logger.debug("{} is closed, ignoring {} from {}", this, response, sender);
                return;
            }

            updateMaxTermSeen.accept(response.getCurrentTerm());

            //忽略比本节点新的
            if (response.getLastAcceptedTerm() > clusterState.term()
                || (response.getLastAcceptedTerm() == clusterState.term()
                && response.getLastAcceptedVersion() > clusterState.getVersionOrMetaDataVersion())) {
                logger.debug("{} ignoring {} from {} as it is fresher", this, response, sender);
                return;
            }

            preVotesReceived.put(sender, response);

            // create a fake VoteCollection based on the pre-votes and check if there is an election quorum
            final VoteCollection voteCollection = new VoteCollection();
            final DiscoveryNode localNode = clusterState.nodes().getLocalNode();
            final PreVoteResponse localPreVoteResponse = getPreVoteResponse();

            preVotesReceived.forEach((node, preVoteResponse) -> voteCollection.addJoinVote(
                new Join(node, localNode, preVoteResponse.getCurrentTerm(),
                preVoteResponse.getLastAcceptedTerm(), preVoteResponse.getLastAcceptedVersion())));

            /**
             * 当前节点信息持有的配置信息和即将参与投票节点的信息进行计算（假投票，用于流程检查）参与者是否满足配置的阈值
             */
            if (electionStrategy.isElectionQuorum(clusterState.nodes().getLocalNode(), localPreVoteResponse.getCurrentTerm(),
                localPreVoteResponse.getLastAcceptedTerm(), localPreVoteResponse.getLastAcceptedVersion(),
                clusterState.getLastCommittedConfiguration(), clusterState.getLastAcceptedConfiguration(), voteCollection) == false) {
                logger.debug("{} added {} from {}, no quorum yet", this, response, sender);
                return;
            }

            if (electionStarted.compareAndSet(false, true) == false) {
                logger.debug("{} added {} from {} but election has already started", this, response, sender);
                return;
            }
            //满足所有条件，开始执行选举操作
            logger.debug("{} added {} from {}, starting election", this, response, sender);
            startElection.run();
        }

        @Override
        public String toString() {
            return "PreVotingRound{" +
                "preVotesReceived=" + preVotesReceived +
                ", electionStarted=" + electionStarted +
                ", preVoteRequest=" + preVoteRequest +
                ", isClosed=" + isClosed +
                '}';
        }

        @Override
        public void close() {
            final boolean isNotAlreadyClosed = isClosed.compareAndSet(false, true);
            assert isNotAlreadyClosed;
        }
    }
}
