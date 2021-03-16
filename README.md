Netflix Eureka 的源码阅读
=====

1.eureka server的启动  , 相当于是注册中心的驱动 -> 启动的过程搞清楚 , 初始化了哪些东西  

2.eureka client的启动,相当于服务的启动,初始化了哪些东西    

3.eureka运行的核心流程，eureka往eureka server的注册过程，服务注册、服务发现、eureka client从eureka server获取注册表的过程  

4. 服务心跳、eureka定时往eureka server发送续约通知（心跳）  

5.服务实例摘除  

6.通信  

7.限流  

8.自我保护
