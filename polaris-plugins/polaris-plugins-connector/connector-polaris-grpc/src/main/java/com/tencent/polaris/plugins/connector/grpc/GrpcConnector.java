/*
 * Tencent is pleased to support the open source community by making Polaris available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.polaris.plugins.connector.grpc;

import static com.tencent.polaris.specification.api.v1.model.CodeProto.Code.ExecuteSuccess;
import static com.tencent.polaris.specification.api.v1.model.CodeProto.Code.InvalidDiscoverResource;

import com.google.protobuf.StringValue;
import com.google.protobuf.TextFormat;
import com.google.protobuf.UInt32Value;
import com.tencent.polaris.api.config.global.ClusterType;
import com.tencent.polaris.api.config.global.ServerConnectorConfig;
import com.tencent.polaris.api.config.plugin.DefaultPlugins;
import com.tencent.polaris.api.exception.ErrorCode;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.exception.RetriableException;
import com.tencent.polaris.api.exception.ServerCodes;
import com.tencent.polaris.api.plugin.PluginType;
import com.tencent.polaris.api.plugin.common.InitContext;
import com.tencent.polaris.api.plugin.common.PluginTypes;
import com.tencent.polaris.api.plugin.compose.Extensions;
import com.tencent.polaris.api.plugin.server.CommonProviderRequest;
import com.tencent.polaris.api.plugin.server.CommonProviderResponse;
import com.tencent.polaris.api.plugin.server.ReportClientRequest;
import com.tencent.polaris.api.plugin.server.ReportClientResponse;
import com.tencent.polaris.api.plugin.server.ServerConnector;
import com.tencent.polaris.api.plugin.server.ServerEvent;
import com.tencent.polaris.api.plugin.server.ServiceEventHandler;
import com.tencent.polaris.api.plugin.server.TargetServer;
import com.tencent.polaris.api.pojo.ServiceEventKey;
import com.tencent.polaris.api.pojo.ServiceEventKey.EventType;
import com.tencent.polaris.api.pojo.ServiceKey;
import com.tencent.polaris.api.utils.StringUtils;
import com.tencent.polaris.api.utils.ThreadPoolUtils;
import com.tencent.polaris.client.util.NamedThreadFactory;
import com.tencent.polaris.logging.LoggerFactory;
import com.tencent.polaris.plugins.connector.common.DestroyableServerConnector;
import com.tencent.polaris.plugins.connector.common.ServiceUpdateTask;
import com.tencent.polaris.plugins.connector.common.constant.ServiceUpdateTaskConstant.Status;
import com.tencent.polaris.plugins.connector.common.constant.ServiceUpdateTaskConstant.Type;
import com.tencent.polaris.plugins.connector.grpc.Connection.ConnID;
import com.tencent.polaris.specification.api.v1.model.ModelProto;
import com.tencent.polaris.specification.api.v1.service.manage.ClientProto;
import com.tencent.polaris.specification.api.v1.service.manage.ClientProto.Client;
import com.tencent.polaris.specification.api.v1.service.manage.ClientProto.StatInfo;
import com.tencent.polaris.specification.api.v1.service.manage.PolarisGRPCGrpc;
import com.tencent.polaris.specification.api.v1.service.manage.RequestProto;
import com.tencent.polaris.specification.api.v1.service.manage.RequestProto.DiscoverRequest;
import com.tencent.polaris.specification.api.v1.service.manage.ResponseProto;
import com.tencent.polaris.specification.api.v1.service.manage.ResponseProto.DiscoverResponse;
import com.tencent.polaris.specification.api.v1.service.manage.ServiceProto;
import com.tencent.polaris.specification.api.v1.service.manage.ServiceProto.Service;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

/**
 * An implement of {@link ServerConnector} to connect to Polaris server.
 * It provides methods to manage resources relate to a service:
 * 1. registerEventHandler/deRegisterEventHandler to subscribe instance/config for a service.
 * 2. registerInstance/deregisterInstance to register/deregister an instance.
 * 3. heartbeat to send heartbeat manually.
 *
 * @author andrewshan, Haotian Zhang
 */
public class GrpcConnector extends DestroyableServerConnector {

    private static final Logger LOG = LoggerFactory.getLogger(GrpcConnector.class);

    private final Map<ClusterType, AtomicReference<SpecStreamClient>> streamClients = new HashMap<>();
    private long messageTimeoutMs;
    private ConnectionManager connectionManager;
    private long connectionIdleTimeoutMs;
    private boolean initialized = false;
    private boolean standalone = true;
    private String id;
    private boolean isRegisterEnable = true;
    private boolean isDiscoveryEnable = true;
    private String clientInstanceId;

    /**
     * 发送消息的线程池
     */
    private ScheduledThreadPoolExecutor sendDiscoverExecutor;

    /**
     * 用于往埋点服务发送消息的线程池，高优先处理
     */
    private ScheduledThreadPoolExecutor buildInExecutor;

    /**
     * 定时更新的线程池
     */
    private ScheduledThreadPoolExecutor updateServiceExecutor;

    private CompletableFuture<String> readyFuture;

    private final Object lock = new Object();

    private final Map<EventType, Boolean> supportedResourcesType = new ConcurrentHashMap<>();

    private static TargetServer connectionToTargetNode(Connection connection) {
        ConnID connID = connection.getConnID();
        return new TargetServer(connID.getServiceKey(), connID.getHost(), connID.getPort(), connID.getProtocol());
    }

    @Override
    public void init(InitContext ctx) throws PolarisException {
        if (!initialized) {
            supportedResourcesType.put(EventType.INSTANCE, true);
            supportedResourcesType.put(EventType.ROUTING, true);
            supportedResourcesType.put(EventType.SERVICE, true);
            supportedResourcesType.put(EventType.RATE_LIMITING, true);
            if (getName().equals(ctx.getValueContext().getServerConnectorProtocol())) {
                standalone = true;
                initActually(ctx, ctx.getConfig().getGlobal().getServerConnector());
            } else {
                standalone = false;
                ServerConnectorConfig serverConnectorConfig = null;
                for (ServerConnectorConfig c : ctx.getConfig().getGlobal().getServerConnectors()) {
                    if (DefaultPlugins.SERVER_CONNECTOR_GRPC.equals(c.getProtocol())) {
                        serverConnectorConfig = c;
                    }
                }
                if (serverConnectorConfig != null) {
                    initActually(ctx, serverConnectorConfig);
                }
            }
        }
    }

    private void initActually(InitContext ctx, ServerConnectorConfig connectorConfig) {
        readyFuture = new CompletableFuture<>();
        Map<ClusterType, CompletableFuture<String>> futures = new HashMap<>();
        futures.put(ClusterType.SERVICE_DISCOVER_CLUSTER, readyFuture);
        id = connectorConfig.getId();
        if (ctx.getConfig().getProvider().getRegisterConfigMap().containsKey(id)) {
            isRegisterEnable = ctx.getConfig().getProvider().getRegisterConfigMap().get(id).isEnable();
        }
        if (ctx.getConfig().getConsumer().getDiscoveryConfigMap().containsKey(id)) {
            isDiscoveryEnable = ctx.getConfig().getConsumer().getDiscoveryConfigMap().get(id).isEnable();
        }
        connectionManager = new ConnectionManager(ctx, connectorConfig, futures);
        connectionIdleTimeoutMs = connectorConfig.getConnectionIdleTimeout();
        messageTimeoutMs = connectorConfig.getMessageTimeout();
        sendDiscoverExecutor = new ScheduledThreadPoolExecutor(1,
                new NamedThreadFactory(getName() + "-send-discovery"), new CallerRunsPolicy());
        sendDiscoverExecutor.setMaximumPoolSize(1);
        buildInExecutor = new ScheduledThreadPoolExecutor(0,
                new NamedThreadFactory(getName() + "-builtin-discovery"), new CallerRunsPolicy());
        buildInExecutor.setMaximumPoolSize(1);
        streamClients.put(ClusterType.BUILTIN_CLUSTER, new AtomicReference<>());
        streamClients.put(ClusterType.SERVICE_DISCOVER_CLUSTER, new AtomicReference<>());
        updateServiceExecutor = new ScheduledThreadPoolExecutor(1,
                new NamedThreadFactory(getName() + "-update-service"));
        updateServiceExecutor.setMaximumPoolSize(1);
        clientInstanceId = UUID.randomUUID().toString();
        initialized = true;
    }

    private void waitDiscoverReady() {
        try {
            readyFuture.get(messageTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        } catch (TimeoutException e) {
            throw new RetriableException(ErrorCode.API_TIMEOUT, "discover service not ready");
        }
    }

    @Override
    public void registerServiceHandler(ServiceEventHandler handler) {
        checkDestroyed();
        ServiceEventKey serviceEventKey = handler.getServiceEventKey();
        if (!checkEventSupported(serviceEventKey.getEventType())) {
            LOG.info("[ServerConnector] not supported event type for {}", serviceEventKey);
            handler.getEventHandler()
                    .onEventUpdate(new ServerEvent(serviceEventKey, buildEmptyResponse(serviceEventKey), null));
            return;
        }
        ServiceUpdateTask serviceUpdateTask = new GrpcServiceUpdateTask(handler, this);
        submitServiceHandler(serviceUpdateTask, 0);
    }

    private DiscoverResponse buildEmptyResponse(ServiceEventKey serviceEventKey) {
        DiscoverResponse.Builder builder = DiscoverResponse.newBuilder();
        builder.setService(
                Service.newBuilder().setName(StringValue.newBuilder().setValue(serviceEventKey.getService()).build())
                        .setNamespace(
                                StringValue.newBuilder().setValue(serviceEventKey.getNamespace()).build()));
        builder.setCode(UInt32Value.newBuilder().setValue(ExecuteSuccess.getNumber()).build());
        builder.setType(GrpcUtil.buildDiscoverResponseType(serviceEventKey.getEventType()));
        return builder.build();
    }

    private boolean checkEventSupported(EventType eventType) {
        Boolean aBoolean = supportedResourcesType.get(eventType);
        if (null != aBoolean) {
            return aBoolean;
        }
        synchronized (lock) {
            aBoolean = supportedResourcesType.get(eventType);
            if (null != aBoolean) {
                return aBoolean;
            }
            LOG.info("[ServerConnector] start to check compatible for event type {}", eventType);
            Connection connection = null;
            try {
                connection = connectionManager.getConnection(GrpcUtil.OP_KEY_CHECK_COMPATIBLE, ClusterType.BUILTIN_CLUSTER);
                String reqId = GrpcUtil.nextGetInstanceReqId();
                PolarisGRPCGrpc.PolarisGRPCStub namingStub = PolarisGRPCGrpc.newStub(connection.getChannel());
                GrpcUtil.attachRequestHeader(namingStub, reqId);
                CountDownLatch countDownLatch = new CountDownLatch(1);
                StreamObserver<DiscoverRequest> discoverClient = namingStub
                        .discover(new StreamObserver<DiscoverResponse>() {
                            @Override
                            public void onNext(DiscoverResponse value) {
                                int code = value.getCode().getValue();
                                boolean supported = true;
                                if (code == InvalidDiscoverResource.getNumber()) {
                                    supported = false;
                                }
                                supportedResourcesType.put(eventType, supported);
                                LOG.info("[ServerConnector] success to check compatible for event type {}, result {}",
                                        eventType, supported);
                                countDownLatch.countDown();
                            }

                            @Override
                            public void onError(Throwable t) {
                                countDownLatch.countDown();
                                LOG.error("[ServerConnector] fail to acquire check event type {}", eventType, t);
                                throw new PolarisException(ErrorCode.NETWORK_ERROR,
                                        "[ServerConnector] fail to acquire check event type " + eventType, t);
                            }

                            @Override
                            public void onCompleted() {
                                countDownLatch.countDown();
                            }
                        });
                RequestProto.DiscoverRequest.Builder req = RequestProto.DiscoverRequest.newBuilder();
                req.setType(GrpcUtil.buildDiscoverRequestType(eventType));
                discoverClient.onNext(req.build());
                try {
                    countDownLatch.await();
                } catch (InterruptedException e) {
                    LOG.error("[ServerConnector] fail to wait check event type {}", eventType, e);
                }
                aBoolean = supportedResourcesType.get(eventType);
                if (null != aBoolean) {
                    return aBoolean;
                }
                LOG.error("[ServerConnector] timeout to wait check event type {}", eventType);
                throw new PolarisException(ErrorCode.API_TIMEOUT,
                        "[ServerConnector] timeout to check compatible for event type " + eventType);
            } finally {
                if (null != connection) {
                    connection.release(GrpcUtil.OP_KEY_CHECK_COMPATIBLE);
                }
            }
        }
    }

    @Override
    protected void submitServiceHandler(ServiceUpdateTask updateTask, long delayMs) {
        ClusterType targetCluster = updateTask.getTargetClusterType();
        if (updateTask.setStatus(Status.READY, Status.RUNNING)) {
            if (targetCluster == ClusterType.BUILTIN_CLUSTER) {
                LOG.info("[ServerConnector]task for service {} has been scheduled builtin", updateTask);
                buildInExecutor.schedule(updateTask, delayMs, TimeUnit.MILLISECONDS);
            } else {
                LOG.debug("[ServerConnector]task for service {} has been scheduled discover", updateTask);
                sendDiscoverExecutor.schedule(updateTask, delayMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public void deRegisterServiceHandler(ServiceEventKey eventKey) throws PolarisException {
        checkDestroyed();
        ServiceUpdateTask serviceUpdateTask = updateTaskSet.get(eventKey);
        if (null != serviceUpdateTask) {
            boolean result = serviceUpdateTask.setType(Type.LONG_RUNNING, Type.TERMINATED);
            LOG.info("[ServerConnector]success to deRegister updateServiceTask {}, result is {}", eventKey, result);
        }
    }

    @Override
    public CommonProviderResponse registerInstance(CommonProviderRequest req, Map<String, String> customHeader)
            throws PolarisException {
        if (!isRegisterEnable()) {
            return null;
        }
        checkDestroyed();
        Connection connection = null;
        ServiceKey serviceKey = new ServiceKey(req.getNamespace(), req.getService());
        try {
            waitDiscoverReady();
            connection = connectionManager
                    .getConnection(GrpcUtil.OP_KEY_REGISTER_INSTANCE, ClusterType.SERVICE_DISCOVER_CLUSTER);
            req.setTargetServer(connectionToTargetNode(connection));
            PolarisGRPCGrpc.PolarisGRPCBlockingStub stub = PolarisGRPCGrpc.newBlockingStub(connection.getChannel());
            GrpcUtil.attachRequestHeader(stub, GrpcUtil.nextInstanceRegisterReqId());
            GrpcUtil.attachRequestHeader(stub, customHeader);
            ResponseProto.Response registerInstanceResponse = stub.registerInstance(buildRegisterInstanceRequest(req));
            GrpcUtil.checkResponse(registerInstanceResponse);
            if (!registerInstanceResponse.hasInstance()) {
                throw new PolarisException(ErrorCode.SERVER_USER_ERROR,
                        "invalid register response: missing instance");
            }
            CommonProviderResponse resp = new CommonProviderResponse();
            resp.setInstanceID(registerInstanceResponse.getInstance().getId().getValue());
            resp.setExists(registerInstanceResponse.getCode().getValue() == ServerCodes.EXISTED_RESOURCE);
            return resp;
        } catch (Throwable t) {
            if (t instanceof PolarisException) {
                throw t;
            }
            if (null != connection) {
                connection.reportFail(ErrorCode.NETWORK_ERROR);
            }
            throw new RetriableException(ErrorCode.NETWORK_ERROR,
                    String.format("fail to register host %s:%d service %s", req.getHost(), req.getPort(), serviceKey),
                    t);
        } finally {
            if (null != connection) {
                connection.release(GrpcUtil.OP_KEY_REGISTER_INSTANCE);
            }
        }
    }

    private ServiceProto.Instance buildRegisterInstanceRequest(CommonProviderRequest req) {
        ServiceProto.Instance.Builder instanceBuilder = ServiceProto.Instance.newBuilder();
        instanceBuilder.setService(StringValue.newBuilder().setValue(req.getService()).build());
        instanceBuilder.setNamespace(StringValue.newBuilder().setValue(req.getNamespace()).build());
        if (StringUtils.isNotBlank(req.getToken())) {
            instanceBuilder.setServiceToken(StringValue.newBuilder().setValue(req.getToken()).build());
        }
        instanceBuilder.setHost(StringValue.newBuilder().setValue(req.getHost()).build());
        instanceBuilder.setPort(UInt32Value.newBuilder().setValue(req.getPort()).build());
        if (StringUtils.isNotBlank(req.getProtocol())) {
            instanceBuilder.setProtocol(StringValue.newBuilder().setValue(req.getProtocol()).build());
        }
        if (StringUtils.isNotBlank(req.getVersion())) {
            instanceBuilder.setVersion(StringValue.newBuilder().setValue(req.getVersion()).build());
        }
        if (null != req.getWeight()) {
            instanceBuilder.setWeight(UInt32Value.newBuilder().setValue(req.getWeight()).build());
        }
        if (null != req.getPriority()) {
            instanceBuilder.setPriority(UInt32Value.newBuilder().setValue(req.getPriority()).build());
        }
        if (null != req.getMetadata()) {
            instanceBuilder.putAllMetadata(req.getMetadata());
        }
        if (null != req.getTtl()) {
            ServiceProto.HealthCheck.Builder healthCheckBuilder = ServiceProto.HealthCheck.newBuilder();
            healthCheckBuilder.setType(ServiceProto.HealthCheck.HealthCheckType.HEARTBEAT);
            healthCheckBuilder.setHeartbeat(
                    ServiceProto.HeartbeatHealthCheck.newBuilder().setTtl(
                            UInt32Value.newBuilder().setValue(req.getTtl()).build()).build());
            instanceBuilder.setHealthCheck(healthCheckBuilder.build());
        }

        ModelProto.Location.Builder locationBuilder = ModelProto.Location.newBuilder();
        if (StringUtils.isNotBlank(req.getRegion())) {
            locationBuilder.setRegion(StringValue.newBuilder().setValue(req.getRegion()));
        }
        if (StringUtils.isNotBlank(req.getZone())) {
            locationBuilder.setZone(StringValue.newBuilder().setValue(req.getZone()));
        }
        if (StringUtils.isNotBlank(req.getCampus())) {
            locationBuilder.setCampus(StringValue.newBuilder().setValue(req.getCampus()));
        }
        ModelProto.Location location = locationBuilder.build();
        instanceBuilder.setLocation(location);
        if (StringUtils.isNotEmpty(req.getInstanceID())) {
            instanceBuilder.setId(StringValue.newBuilder().setValue(req.getInstanceID()));
        }

        return instanceBuilder.build();
    }

    private ServiceProto.Instance buildHeartbeatRequest(CommonProviderRequest req) {
        ServiceProto.Instance.Builder instanceBuilder = ServiceProto.Instance.newBuilder();
        if (StringUtils.isNotBlank(req.getInstanceID())) {
            instanceBuilder.setId(StringValue.newBuilder().setValue(req.getInstanceID()).build());
        }
        if (StringUtils.isNotBlank(req.getService())) {
            instanceBuilder.setService(StringValue.newBuilder().setValue(req.getService()).build());
        }
        if (StringUtils.isNotBlank(req.getHost())) {
            instanceBuilder.setHost(StringValue.newBuilder().setValue(req.getHost()).build());
        }
        if (StringUtils.isNotBlank(req.getNamespace())) {
            instanceBuilder.setNamespace(StringValue.newBuilder().setValue(req.getNamespace()).build());
        }
        if (req.getPort() > 0) {
            instanceBuilder.setPort(UInt32Value.of(req.getPort()));
        }
        if (StringUtils.isNotBlank(req.getToken())) {
            instanceBuilder.setServiceToken(StringValue.newBuilder().setValue(req.getToken()).build());
        }
        return instanceBuilder.build();
    }

    private ClientProto.Client buildReportRequest(ReportClientRequest req) {
        Client.Builder builder = Client.newBuilder().setHost(StringValue.newBuilder().setValue(req.getClientHost()))
                .setVersion(StringValue.newBuilder().setValue(req.getVersion()));
        Optional.ofNullable(req.getReporterMetaInfos()).ifPresent(reporterMetaInfos -> reporterMetaInfos.forEach(
                reporterMetaInfo -> builder.addStat(StatInfo.newBuilder()
                        .setTarget(StringValue.newBuilder().setValue(reporterMetaInfo.getTarget()).build())
                        .setPort(UInt32Value.newBuilder().setValue(reporterMetaInfo.getPort()).build())
                        .setPath(StringValue.newBuilder().setValue(reporterMetaInfo.getPath()).build())
                        .setProtocol(StringValue.newBuilder().setValue(reporterMetaInfo.getProtocol()).build())
                        .build())));
        builder.setId(StringValue.newBuilder().setValue(clientInstanceId).build());
        return builder.build();
    }

    private ServiceProto.Instance buildDeregisterInstanceRequest(CommonProviderRequest req) {
        ServiceProto.Instance.Builder instanceBuilder = ServiceProto.Instance.newBuilder();
        if (StringUtils.isNotBlank(req.getInstanceID())) {
            instanceBuilder.setId(StringValue.newBuilder().setValue(req.getInstanceID()).build());
        }
        if (StringUtils.isNotBlank(req.getNamespace())) {
            instanceBuilder.setNamespace(StringValue.newBuilder().setValue(req.getNamespace()).build());
        }
        if (StringUtils.isNotBlank(req.getService())) {
            instanceBuilder.setService(StringValue.newBuilder().setValue(req.getService()).build());
        }
        if (StringUtils.isNotBlank(req.getHost())) {
            instanceBuilder.setHost(StringValue.newBuilder().setValue(req.getHost()).build());
        }
        if (req.getPort() > 0) {
            instanceBuilder.setPort(UInt32Value.of(req.getPort()));
        }
        if (StringUtils.isNotBlank(req.getToken())) {
            instanceBuilder.setServiceToken(StringValue.newBuilder().setValue(req.getToken()).build());
        }
        return instanceBuilder.build();
    }

    @Override
    public void deregisterInstance(CommonProviderRequest req) throws PolarisException {
        if (!isRegisterEnable()) {
            return;
        }
        checkDestroyed();
        Connection connection = null;
        ServiceKey serviceKey = new ServiceKey(req.getNamespace(), req.getService());
        try {
            waitDiscoverReady();
            connection = connectionManager
                    .getConnection(GrpcUtil.OP_KEY_DEREGISTER_INSTANCE, ClusterType.SERVICE_DISCOVER_CLUSTER);
            req.setTargetServer(connectionToTargetNode(connection));
            PolarisGRPCGrpc.PolarisGRPCBlockingStub stub = PolarisGRPCGrpc.newBlockingStub(connection.getChannel());
            GrpcUtil.attachRequestHeader(stub, GrpcUtil.nextInstanceDeRegisterReqId());
            ResponseProto.Response deregisterInstanceResponse = stub
                    .deregisterInstance(buildDeregisterInstanceRequest(req));
            GrpcUtil.checkResponse(deregisterInstanceResponse);
            LOG.debug("received deregister response {}", deregisterInstanceResponse);
        } catch (Throwable t) {
            if (t instanceof PolarisException) {
                //服务端异常不进行重试
                throw t;
            }
            if (null != connection) {
                connection.reportFail(ErrorCode.NETWORK_ERROR);
            }
            throw new RetriableException(ErrorCode.NETWORK_ERROR,
                    String.format("fail to deregister id %s, host %s:%d service %s",
                            req.getInstanceID(), req.getHost(), req.getPort(), serviceKey), t);
        } finally {
            if (null != connection) {
                connection.release(GrpcUtil.OP_KEY_DEREGISTER_INSTANCE);
            }
        }
    }

    @Override
    public void heartbeat(CommonProviderRequest req) throws PolarisException {
        if (!isRegisterEnable()) {
            return;
        }
        checkDestroyed();
        Connection connection = null;
        ServiceKey serviceKey = new ServiceKey(req.getNamespace(), req.getService());
        try {
            waitDiscoverReady();
            connection = connectionManager
                    .getConnection(GrpcUtil.OP_KEY_INSTANCE_HEARTBEAT, ClusterType.HEALTH_CHECK_CLUSTER);
            req.setTargetServer(connectionToTargetNode(connection));
            PolarisGRPCGrpc.PolarisGRPCBlockingStub stub = PolarisGRPCGrpc.newBlockingStub(connection.getChannel());
            GrpcUtil.attachRequestHeader(stub, GrpcUtil.nextHeartbeatReqId());
            ResponseProto.Response heartbeatResponse = stub.heartbeat(buildHeartbeatRequest(req));
            GrpcUtil.checkResponse(heartbeatResponse);
            LOG.debug("received heartbeat response {}", heartbeatResponse);
        } catch (Throwable t) {
            if (t instanceof PolarisException) {
                //服务端异常不进行重试
                throw t;
            }
            if (null != connection) {
                connection.reportFail(ErrorCode.NETWORK_ERROR);
            }
            throw new RetriableException(ErrorCode.NETWORK_ERROR,
                    String.format("fail to heartbeat id %s, host %s:%d service %s",
                            req.getInstanceID(), req.getHost(), req.getPort(), serviceKey), t);
        } finally {
            if (null != connection) {
                connection.release(GrpcUtil.OP_KEY_INSTANCE_HEARTBEAT);
            }
        }
    }

    @Override
    public ReportClientResponse reportClient(ReportClientRequest req) throws PolarisException {
        checkDestroyed();
        waitDiscoverReady();
        Connection connection = null;
        ServiceKey serviceKey = new ServiceKey(req.getNamespace(), req.getService());
        try {
            connection = connectionManager
                    .getConnection(GrpcUtil.OP_KEY_REPORT_CLIENT, ClusterType.SERVICE_DISCOVER_CLUSTER);
            req.setTargetServer(connectionToTargetNode(connection));
            PolarisGRPCGrpc.PolarisGRPCBlockingStub stub = PolarisGRPCGrpc.newBlockingStub(connection.getChannel());
            GrpcUtil.attachRequestHeader(stub, GrpcUtil.nextHeartbeatReqId());
            ClientProto.Client request = buildReportRequest(req);
            ResponseProto.Response response = stub.reportClient(request);
            LOG.debug("reportClient req:{}, rsp:{}", req, TextFormat.shortDebugString(response));
            GrpcUtil.checkResponse(response);
            ReportClientResponse rsp = new ReportClientResponse();
            if (null == response.getClient().getLocation()) {
                throw new IllegalStateException(
                        String.format("unexpected null response from clientReport api, response:%s",
                                TextFormat.shortDebugString(response)));
            }
            rsp.setCampus(response.getClient().getLocation().getCampus().getValue());
            rsp.setZone(response.getClient().getLocation().getZone().getValue());
            rsp.setRegion(response.getClient().getLocation().getRegion().getValue());
            return rsp;
        } catch (Throwable t) {
            if (t instanceof PolarisException) {
                //服务端异常不进行重试
                throw t;
            }
            if (null != connection) {
                connection.reportFail(ErrorCode.NETWORK_ERROR);
            }
            throw new RetriableException(ErrorCode.NETWORK_ERROR,
                    String.format("fail to report client host %s, version %s service %s",
                            req.getClientHost(), req.getVersion(), serviceKey), t);
        } finally {
            if (null != connection) {
                connection.release(GrpcUtil.OP_KEY_REPORT_CLIENT);
            }
        }
    }

    @Override
    public void updateServers(ServiceEventKey svcEventKey) {
        connectionManager.makeReady(svcEventKey);
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public String getName() {
        return DefaultPlugins.SERVER_CONNECTOR_GRPC;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isRegisterEnable() {
        return isRegisterEnable;
    }

    @Override
    public boolean isDiscoveryEnable() {
        return isDiscoveryEnable;
    }

    @Override
    public PluginType getType() {
        return PluginTypes.SERVER_CONNECTOR.getBaseType();
    }


    @Override
    public void postContextInit(Extensions extensions) throws PolarisException {
        if (initialized) {
            connectionManager.setExtensions(extensions);
            this.updateServiceExecutor.scheduleWithFixedDelay(new ClearIdleStreamClientTask(),
                    this.connectionIdleTimeoutMs, this.connectionIdleTimeoutMs, TimeUnit.MILLISECONDS);
            if (standalone) {
                this.updateServiceExecutor.scheduleWithFixedDelay(new UpdateServiceTask(), TASK_RETRY_INTERVAL_MS,
                        TASK_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public void doDestroy() {
        if (initialized) {
            LOG.info("start to destroy connector {}", getName());
            ThreadPoolUtils.waitAndStopThreadPools(
                    new ExecutorService[]{sendDiscoverExecutor, buildInExecutor, updateServiceExecutor});
            if (null != connectionManager) {
                connectionManager.destroy();
            }
        }
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }


    public AtomicReference<SpecStreamClient> getStreamClient(ClusterType clusterType) {
        return streamClients.get(clusterType);
    }

    public long getConnectionIdleTimeoutMs() {
        return connectionIdleTimeoutMs;
    }

    /**
     * 清理过期的streamClient
     */
    private class ClearIdleStreamClientTask implements Runnable {

        @Override
        public void run() {
            for (AtomicReference<SpecStreamClient> streamClientRef : streamClients.values()) {
                SpecStreamClient streamClient = streamClientRef.get();
                if (null == streamClient) {
                    continue;
                }
                streamClient.syncCloseExpireStream();
            }
        }
    }


}
