/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.Date;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.CloudInstanceConfig;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DeploymentContext;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.converters.JsonXStream;
import com.netflix.discovery.converters.XmlXStream;
import com.netflix.eureka.aws.AwsBinder;
import com.netflix.eureka.aws.AwsBinderDelegate;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.AwsInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl;
import com.netflix.eureka.resources.DefaultServerCodecs;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.EurekaMonitors;
import com.thoughtworks.xstream.XStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class that kick starts the eureka server.
 *
 * <p>
 * The eureka server is configured by using the configuration
 * {@link EurekaServerConfig} specified by <em>eureka.server.props</em> in the
 * classpath.  The eureka client component is also initialized by using the
 * configuration {@link EurekaInstanceConfig} specified by
 * <em>eureka.client.props</em>. If the server runs in the AWS cloud, the eureka
 * server binds it to the elastic ip as specified.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim, David Liu
 *
 */
public class EurekaBootStrap implements ServletContextListener {
    private static final Logger logger = LoggerFactory.getLogger(EurekaBootStrap.class);

    private static final String TEST = "test";

    private static final String ARCHAIUS_DEPLOYMENT_ENVIRONMENT = "archaius.deployment.environment";

    private static final String EUREKA_ENVIRONMENT = "eureka.environment";

    private static final String CLOUD = "cloud";
    private static final String DEFAULT = "default";

    private static final String ARCHAIUS_DEPLOYMENT_DATACENTER = "archaius.deployment.datacenter";

    private static final String EUREKA_DATACENTER = "eureka.datacenter";

    protected volatile EurekaServerContext serverContext;
    protected volatile AwsBinder awsBinder;
    
    private EurekaClient eurekaClient;

    /**
     * Construct a default instance of Eureka boostrap
     */
    public EurekaBootStrap() {
        this(null);
    }
    
    /**
     * Construct an instance of eureka bootstrap with the supplied eureka client
     * 
     * @param eurekaClient the eureka client to bootstrap
     */
    public EurekaBootStrap(EurekaClient eurekaClient) {
        this.eurekaClient = eurekaClient;
    }

    /**
     * Initializes Eureka, including syncing up with other Eureka peers and publishing the registry.
     * 初始化eureka, 包括初始化eureka-server,进行服务注册
     * @see
     * javax.servlet.ServletContextListener#contextInitialized(javax.servlet.ServletContextEvent)
     */
    @Override
    public void contextInitialized(ServletContextEvent event) {
        try {
            //初始化eureka-server的环境
            initEurekaEnvironment();

            initEurekaServerContext();
            ServletContext sc = event.getServletContext();
            sc.setAttribute(EurekaServerContext.class.getName(), serverContext);
        } catch (Throwable e) {
            logger.error("Cannot bootstrap eureka server :", e);
            throw new RuntimeException("Cannot bootstrap eureka server :", e);
        }
    }

    /**
     * Users can override to initialize the environment themselves.
     *
     * 初始化eureka-server的环境
     * 包括初始化
     * 1. 数据中心
     * 2. eureka运行环境
     * 这两个配置
     */
    protected void initEurekaEnvironment() throws Exception {
        logger.info("Setting the eureka configuration..");
        /**
         * 初始化一个单例的配置管理器, 下面的getConfigInstace方法还会进行配置初始化
         * 1. 使用两步检查方法进行初始化单例
         * 2. 单例初始化的时候,是double check + volatile 进行
         */
        String dataCenter = ConfigurationManager.getConfigInstance().getString(EUREKA_DATACENTER);
        /**
         * 1.查看数据中心是否存在，key是eureka.datacenter
         * 2.不存在的话设置默认的数据中心
         */
        if (dataCenter == null) {
            logger.info("Eureka data center value eureka.datacenter is not set, defaulting to default");
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, DEFAULT);
        } else {
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, dataCenter);
        }
        /**
         * 1.查看eureka的环境是否设置了，key是eureka.environment
         * 2.如果没有，默认给test环境
         */
        String environment = ConfigurationManager.getConfigInstance().getString(EUREKA_ENVIRONMENT);
        if (environment == null) {
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_ENVIRONMENT, TEST);
            logger.info("Eureka environment value eureka.environment is not set, defaulting to test");
        }
    }

    /**
     * init hook for server context. Override for custom logic.
     * 第一步: 这里会先去加载properties文件中的配置
     * 第二步:
     * 第三步: 初始化eureka-server内部的eureka-client 用来跟其他eureka节点注册和通信
     * 第四步: 处理注册相关的事情
     * 第五步 : 处理peer节点相关的数据
     * 第六步: eureka-server的上下文构建
     * 第七步: 处理一些善后的事情,从相邻的eureka节点拷贝的注册信息
     * 第八步: 处理一些善后的事情,注册所有的监控
     */
    protected void initEurekaServerContext() throws Exception {
        /*
        第一步  获取配置项的代码阅读
            1.EurekaServerConfig这个类,相当于是一个Map,是专门提供eureka-server   配置项的API方法. 适合一些更新没那么频繁的项目
            2. new DefaultEurekaServerConfig().init()配置项的加载
                 1) EUREKA_PROPS_FILE    对应要加载的配置文件的名称
                    默认是eureka-server
                2) ConfigurationManager
                   （1）.把这个配置文件的名称传过去,
                   （2）.然后拼上后缀(properties) ，
                    (3) .并读取加载进成一个properties对象
                    (4) .然后根据文件名为key , 将加载出来的properties对象放到单例            的配置管理中去
                3).此时ConfigurationManager中即有了各个配置项
                4).DefaultEurekaServerConfig方法提供配置API,都是通过硬编码配置项      的名称
                5)如果没有配置,则全部进行默认值的加载,从DynamicPropertyFactory中      获取配置
         */
        EurekaServerConfig eurekaServerConfig = new DefaultEurekaServerConfig();

        // For backward compatibility
        JsonXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);
        XmlXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);

        logger.info("Initializing the eureka client...");
        logger.info(eurekaServerConfig.getJsonCodecName());
        ServerCodecs serverCodecs = new DefaultServerCodecs(eurekaServerConfig);


        /*
         第二步 : 初始化
            1. ApplicationInfo
                1). 相当于每一个ApplicationInfo就相当于一个eureka client
            2. EurekaInstanceConfig
                1) 将eureka-client.properties文件中的配置项加载进ConfigurationManager之中
                2) 基于EurekaInstanceConfig来对配置项API进行暴露
                3) EurekaInstanceConfig之中会提供一些默认值的硬编码存储
                4) 存储的数据为: 例如
                    InstanceId\Appname等
            3. Eureka Server
                1) Eureka Server自己也是一个Eureka Client
                2) 会将自己作为一个Eureka Client把自己当成一个服务
                3) 将自己注册到其他的Eureka Server上,组成集群
                4) 所以Eureka Server也是有Application \ Instance这些概念
             4.EurekaConfigBasedInstanceInfoProvider
                1) 这里会传入一个上述读取出来的 EurekaInstanceConfig配置类
                2) 通过构造器模式,从传进来的config对象中取参数,完成整个InstanceInfo的构造.拿到一个静态内部类的对象
                3) 给InstanceInfo设置一个租约信息
              5.ApplicationInfoManager
                1) 直接通过InstanceInfo和EurekaInstanceConfig对象,构建一个ApplicationInfoManager
                2) ApplicationInfoManager会对配置进行管理
         */
        ApplicationInfoManager applicationInfoManager = null;
        /*
        第三步 : 初始化eureka-server内部的eureka-client
                用来跟其他eureka节点注册和通信
         */
        if (eurekaClient == null) {
            /*
              eureka通信的时候,会判断是否为云环境
             */
            EurekaInstanceConfig instanceConfig = isCloud(ConfigurationManager.getDeploymentContext())
                    ? new CloudInstanceConfig()
                    : new MyDataCenterInstanceConfig();
            
            applicationInfoManager = new ApplicationInfoManager(
                    instanceConfig, new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get());
            
            EurekaClientConfig eurekaClientConfig = new DefaultEurekaClientConfig();
            eurekaClient = new DiscoveryClient(applicationInfoManager, eurekaClientConfig);
        } else {
            applicationInfoManager = eurekaClient.getApplicationInfoManager();
        }


        /*
            第三步:

                处理注册相关的事情
         */
        PeerAwareInstanceRegistry registry;
        if (isAws(applicationInfoManager.getInfo())) {
            registry = new AwsInstanceRegistry(
                    eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
            awsBinder = new AwsBinderDelegate(eurekaServerConfig, eurekaClient.getEurekaClientConfig(), registry, applicationInfoManager);
            awsBinder.start();
        } else {
            registry = new PeerAwareInstanceRegistryImpl(
                    eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
        }
        /*
         * 第四步 : 处理peer节点相关的数据
         */
        PeerEurekaNodes peerEurekaNodes = getPeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClient.getEurekaClientConfig(),
                serverCodecs,
                applicationInfoManager
        );
        /*
         * 第五步: eureka-server的上下文构建
         */
        serverContext = new DefaultEurekaServerContext(
                eurekaServerConfig,
                serverCodecs,
                registry,
                peerEurekaNodes,
                applicationInfoManager
        );

        EurekaServerContextHolder.initialize(serverContext);

        serverContext.initialize();
        logger.info("Initialized server context");
        /*
            第六步: 处理一些善后的事情,从相邻的eureka节点拷贝的注册信息
         */
        // Copy registry from neighboring eureka node
        int registryCount = registry.syncUp();
        registry.openForTraffic(applicationInfoManager, registryCount);
        /*
            第七步: 处理一些善后的事情,注册所有的监控
         */
        // Register all monitoring statistics.
        EurekaMonitors.registerAllStats();
    }
    
    protected PeerEurekaNodes getPeerEurekaNodes(PeerAwareInstanceRegistry registry, EurekaServerConfig eurekaServerConfig, EurekaClientConfig eurekaClientConfig, ServerCodecs serverCodecs, ApplicationInfoManager applicationInfoManager) {
        PeerEurekaNodes peerEurekaNodes = new PeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClientConfig,
                serverCodecs,
                applicationInfoManager
        );
        
        return peerEurekaNodes;
    }

    /**
     * Handles Eureka cleanup, including shutting down all monitors and yielding all EIPs.
     *
     * @see javax.servlet.ServletContextListener#contextDestroyed(javax.servlet.ServletContextEvent)
     */
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        try {
            logger.info("{} Shutting down Eureka Server..", new Date());
            ServletContext sc = event.getServletContext();
            sc.removeAttribute(EurekaServerContext.class.getName());

            destroyEurekaServerContext();
            destroyEurekaEnvironment();

        } catch (Throwable e) {
            logger.error("Error shutting down eureka", e);
        }
        logger.info("{} Eureka Service is now shutdown...", new Date());
    }

    /**
     * Server context shutdown hook. Override for custom logic
     */
    protected void destroyEurekaServerContext() throws Exception {
        EurekaMonitors.shutdown();
        if (awsBinder != null) {
            awsBinder.shutdown();
        }
        if (serverContext != null) {
            serverContext.shutdown();
        }
    }

    /**
     * Users can override to clean up the environment themselves.
     */
    protected void destroyEurekaEnvironment() throws Exception {

    }

    protected boolean isAws(InstanceInfo selfInstanceInfo) {
        boolean result = DataCenterInfo.Name.Amazon == selfInstanceInfo.getDataCenterInfo().getName();
        logger.info("isAws returned {}", result);
        return result;
    }

    protected boolean isCloud(DeploymentContext deploymentContext) {
        logger.info("Deployment datacenter is {}", deploymentContext.getDeploymentDatacenter());
        return CLOUD.equals(deploymentContext.getDeploymentDatacenter());
    }
}
