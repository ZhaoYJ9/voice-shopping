# 本地启动与常见错误

先按 [README](../README.md#快速开始) 准备 Java 21、PostgreSQL、表结构、Redis 和环境变量。IDEA 的运行配置不会自动导出到新开的终端，终端启动前需要在同一终端设置这些变量。

## 启动方式

开发时在项目根目录运行：

```bash
mvn spring-boot:run
```

打包运行：

```bash
mvn clean package -DskipTests
java -jar target/zyj-voice-shopping-1.0-SNAPSHOT.jar
```

看到应用启动成功日志后，访问 <http://localhost:8080/voice-test.html>。启动时会调用 DashScope 合成三句提示语；目前预热失败会中断应用启动。

## 本机数据库报 UnknownHostException

若异常是 `UnknownHostException: 127.0.0.1`，先检查 PostgreSQL 是否监听 5432、数据库地址是否含多余字符，以及 Java 是否配置了 SOCKS 代理。该异常本身不足以证明数据库已停止。

需要保留外部代理、让本机连接直连时，可使用仅对当前 Java 进程生效的参数：

```bash
mvn \
  '-Dspring-boot.run.jvmArguments=-DsocksNonProxyHosts=localhost|127.*|[::1] -Dhttp.nonProxyHosts=localhost|127.*|[::1]' \
  spring-boot:run
```

如果使用 JAR，将参数放在 `-jar` 前：

```bash
java '-DsocksNonProxyHosts=localhost|127.*|[::1]' \
  '-Dhttp.nonProxyHosts=localhost|127.*|[::1]' \
  -jar target/zyj-voice-shopping-1.0-SNAPSHOT.jar
```

只有在确认 DashScope 可以直连、且代理导致它连接异常时，再向上述两个列表追加 `|dashscope.aliyuncs.com`。参数中的竖线必须保留在引号内。

## TTS 报 Model not exist

检查异常是否来自 `CachedTtsPhrases` 的启动预热，并确认模型、音色、服务地域和 API Key 的适用范围。模型和音色必须使用[官方支持的搭配](https://help.aliyun.com/zh/model-studio/cosyvoice-voice-list)。

当前默认值为 `cosyvoice-v2` 与 `longwan_v2`，可在启动前成对覆盖：

```bash
export VOICE_SHOPPING_TTS_MODEL='cosyvoice-v2'
export VOICE_SHOPPING_TTS_VOICE='longwan_v2'
mvn spring-boot:run
```

2026-10-13 的本地诊断中，`cosyvoice-v1 + longwan` 返回 `Model not exist`，同一环境下 `cosyvoice-v2 + longwan_v2` 合成“你好”返回了 23,040 字节音频。这仅说明当时该环境中的调用结果，不代表 v1 已全面下线，也不等同于完整应用或部署验证。

## Maven 下载依赖返回 403

这是依赖仓库访问问题，应检查 Maven 的镜像配置和网络连接。若已准备好匹配的依赖缓存及镜像配置，可使用：

```bash
mvn -o -s /path/to/settings.xml spring-boot:run
```

将路径替换为自己的配置文件。离线模式只能使用已下载的依赖和插件，不能解决缺少依赖的问题；也不要将某台机器上的临时配置路径作为项目通用启动条件。
