package com.netflix.eureka.cluster;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl.Action;

/**
 * Base {@link ReplicationTask} class for instance related replication requests.
 *
 * @author Tomasz Bak
 */
public abstract class InstanceReplicationTask extends ReplicationTask {

    /**
     * For cancel request there may be no InstanceInfo object available so we need to store app/id pair
     * explicitly.
     */
    private final String appName;
    private final String id;

    private final InstanceInfo instanceInfo;
    private final InstanceStatus overriddenStatus;

    private final boolean replicateInstanceInfo;

    protected InstanceReplicationTask(String peerNodeName, Action action, String appName, String id) {
        super(peerNodeName, action);
        this.appName = appName;
        this.id = id;
        this.instanceInfo = null;
        this.overriddenStatus = null;
        this.replicateInstanceInfo = false;
    }
    /** description:
     * @param peerNodeName 需要同步阶段的信息
     * @param action 要执行的操作
     * @param instanceInfo 同步的节点
     * @param overriddenStatus 同步节点状态
     * @param replicateInstanceInfo 是否同步到其他节点
     * @Author: zeryts
     * @email: hezitao@agree.com
     * @Date: 2021/3/21 7:58
     */
    protected InstanceReplicationTask(String peerNodeName,
                                      Action action,
                                      InstanceInfo instanceInfo,
                                      InstanceStatus overriddenStatus,
                                      boolean replicateInstanceInfo) {

        super(peerNodeName, action);
        this.appName = instanceInfo.getAppName();
        this.id = instanceInfo.getId();
        this.instanceInfo = instanceInfo;
        this.overriddenStatus = overriddenStatus;
        this.replicateInstanceInfo = replicateInstanceInfo;
    }

    public String getTaskName() {
        return appName + '/' + id + ':' + action + '@' + peerNodeName;
    }

    public String getAppName() {
        return appName;
    }

    public String getId() {
        return id;
    }

    public InstanceInfo getInstanceInfo() {
        return instanceInfo;
    }

    public InstanceStatus getOverriddenStatus() {
        return overriddenStatus;
    }

    public boolean shouldReplicateInstanceInfo() {
        return replicateInstanceInfo;
    }
}
