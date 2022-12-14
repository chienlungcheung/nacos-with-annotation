/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.nacos.naming.consistency.ephemeral.distro;

import com.alibaba.nacos.naming.cluster.ServerListManager;
import com.alibaba.nacos.naming.cluster.servers.Server;
import com.alibaba.nacos.naming.cluster.transport.Serializer;
import com.alibaba.nacos.naming.consistency.Datum;
import com.alibaba.nacos.naming.consistency.KeyBuilder;
import com.alibaba.nacos.naming.core.DistroMapper;
import com.alibaba.nacos.naming.misc.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data replicator
 *
 * 负责两个事情:
 * 1. 自动地将当前 nacos 节点负责的数据的校验和(keys 和校验和)发送给其它 nacos 节点.
 * 2. 被外部调用负责将指定数据(keys 和 values)同步给其它 nacos 节点, 注意 keys 不一定是本地
 * nacos 节点负责.
 *
 * @author nkorange
 * @since 1.0.0
 */
@Component
@DependsOn("serverListManager")
public class DataSyncer {

    /**
     * 本地存储
     */
    @Autowired
    private DataStore dataStore;

    @Autowired
    private GlobalConfig partitionConfig;

    @Autowired
    private Serializer serializer;

    @Autowired
    private DistroMapper distroMapper;

    @Autowired
    private ServerListManager serverListManager;

    /**
     * 暂存同步任务.
     */
    private Map<String, String> taskMap = new ConcurrentHashMap<>();

    /**
     * 启动定时同步.
     */
    @PostConstruct
    public void init() {
        startTimedSync();
    }

    /**
     * 供外部调用提交同步任务, keys(不校验是否本地 nacos 节点负责) 和 values 都一起同步.
     * 该方法默认支持任务重试.
     * @param task
     * @param delay
     */
    public void submit(SyncTask task, long delay) {

        // If it's a new task:
        if (task.getRetryCount() == 0) {
            Iterator<String> iterator = task.getKeys().iterator();
            while (iterator.hasNext()) {
                String key = iterator.next();
                if (StringUtils.isNotBlank(taskMap.putIfAbsent(buildKey(key, task.getTargetServer()), key))) {
                    // associated key already exist:
                    if (Loggers.DISTRO.isDebugEnabled()) {
                        Loggers.DISTRO.debug("sync already in process, key: {}", key);
                    }
                    iterator.remove();
                }
            }
        }

        if (task.getKeys().isEmpty()) {
            // all keys are removed:
            return;
        }

        GlobalExecutor.submitDataSync(new Runnable() {
            @Override
            public void run() {

                try {
                    if (getServers() == null || getServers().isEmpty()) {
                        Loggers.SRV_LOG.warn("try to sync data but server list is empty.");
                        return;
                    }

                    // 本次同步任务要同步的 keys
                    List<String> keys = task.getKeys();

                    if (Loggers.DISTRO.isDebugEnabled()) {
                        Loggers.DISTRO.debug("sync keys: {}", keys);
                    }

                    // 取出 keys 对应的 values
                    Map<String, Datum> datumMap = dataStore.batchGet(keys);

                    if (datumMap == null || datumMap.isEmpty()) {
                        // clear all flags of this task:
                        for (String key : task.getKeys()) {
                            taskMap.remove(buildKey(key, task.getTargetServer()));
                        }
                        return;
                    }

                    // 将全部要同步的数据序列化
                    byte[] data = serializer.serialize(datumMap);

                    long timestamp = System.currentTimeMillis();
                    // 发送数据给指定 nacos 节点
                    boolean success = NamingProxy.syncData(data, task.getTargetServer());
                    // 发送失败支持重试.
                    if (!success) {
                        SyncTask syncTask = new SyncTask();
                        syncTask.setKeys(task.getKeys());
                        syncTask.setRetryCount(task.getRetryCount() + 1);
                        syncTask.setLastExecuteTime(timestamp);
                        syncTask.setTargetServer(task.getTargetServer());
                        retrySync(syncTask);
                    } else {
                        // clear all flags of this task:
                        for (String key : task.getKeys()) {
                            taskMap.remove(buildKey(key, task.getTargetServer()));
                        }
                    }

                } catch (Exception e) {
                    Loggers.DISTRO.error("sync data failed.", e);
                }
            }
        }, delay);
    }

    /**
     * 数据同步失败支持重试直至成功.
     *
     * @param syncTask
     */
    public void retrySync(SyncTask syncTask) {

        Server server = new Server();
        server.setIp(syncTask.getTargetServer().split(":")[0]);
        server.setServePort(Integer.parseInt(syncTask.getTargetServer().split(":")[1]));
        if (!getServers().contains(server)) {
            // if server is no longer in healthy server list, ignore this task:
            return;
        }

        // TODO may choose other retry policy.
        submit(syncTask, partitionConfig.getSyncRetryDelay());
    }

    public void startTimedSync() {
        GlobalExecutor.schedulePartitionDataTimedSync(new TimedSync());
    }

    /**
     * 定时任务, 负责定期将当前 nacos 节点负责的数据
     * 的校验和同步给其它 nacos 节点.
     */
    public class TimedSync implements Runnable {

        @Override
        public void run() {

            try {

                if (Loggers.DISTRO.isDebugEnabled()) {
                    Loggers.DISTRO.debug("server list is: {}", getServers());
                }

                // send local timestamps to other servers:
                Map<String, String> keyChecksums = new HashMap<>(64);
                // 为当前 nacos 节点负责的数据计算校验和
                for (String key : dataStore.keys()) {
                    if (!distroMapper.responsible(KeyBuilder.getServiceName(key))) {
                        continue;
                    }

                    keyChecksums.put(key, dataStore.get(key).value.getChecksum());
                }

                if (keyChecksums.isEmpty()) {
                    return;
                }

                if (Loggers.DISTRO.isDebugEnabled()) {
                    Loggers.DISTRO.debug("sync checksums: {}", keyChecksums);
                }

                // 将上面计算出来的校验和同步给其它 nacos 节点的 /v1/ns/distro/checksum,
                // 其它节点发现校验和冲突的时候会主动来拉取数据.
                for (Server member : getServers()) {
                    if (NetUtils.localServer().equals(member.getKey())) {
                        continue;
                    }
                    NamingProxy.syncCheckSums(keyChecksums, member.getKey());
                }
            } catch (Exception e) {
                Loggers.DISTRO.error("timed sync task failed.", e);
            }
        }
    }

    public List<Server> getServers() {
        return serverListManager.getHealthyServers();
    }

    public String buildKey(String key, String targetServer) {
        return key + UtilsAndCommons.CACHE_KEY_SPLITER + targetServer;
    }
}
