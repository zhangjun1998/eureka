**简介**

这里是 [『香蕉大魔王』](https://github.com/zhangjun1998) 的 Eureka 源码解析，[原始项目地址](https://github.com/zhangjun1998/eureka) 在这。
技术暂时还比较拙劣，可能有很多理解不到位或有出入的地方，所以源码注释仅供参考哈，如有错误还请指正，错误很多的话就别说了，请把一些优秀的博客地址贴给我，我爱学习。

目前还没时间总结整体流程，有时间了我再用图文结合的方式把核心流程描述一遍，现在先 mark 一下。

忘记说了，这是 2022-06-22 拉出来的 master 分支代码。
然后其中涉及到的一些关于 spring-cloud 对 eureka 的封装需要移步到 [香蕉大魔王的 spring-cloud-netflix 源码解析](https://github.com/zhangjun1998/spring-cloud-netflix) 去看。

**已看和待看的代码如下：**

+ [x] eureka-server/web.xml
+ [x] EurekaBootStrap
+ [x] EurekaServerConfig
+ [x] EurekaInstanceConfig
+ [x] EurekaClientConfig
+ [x] InstanceInfo
+ [x] ApplicationInfoManager
+ [x] EurekaClient
+ [x] EurekaConfigBasedInstanceInfoProvider
+ [x] DiscoveryClient
+ [x] InstanceInfoReplicator
+ [x] LeaseInfo
+ [x] TimedSupervisorTask
+ [x] AbstractInstanceRegistry
+ [x] PeerAwareInstanceRegistry
+ [x] PeerAwareInstanceRegistryImpl
+ [x] PeerEurekaNodes
+ [x] EurekaServerContext
+ [x] DefaultEurekaServerContext
+ [ ] ...
+ 


**联系方式：**

+ 邮箱：zhangjun_java@163.com
+ 微信：rzy-zj

如要联系我请备注来意，不知道怎么备注的我提供个模板：「oh，你的 Eureka 源码解析的太好了，加个好友瞧一瞧」。好，祝大家技术进步，生活快乐。
