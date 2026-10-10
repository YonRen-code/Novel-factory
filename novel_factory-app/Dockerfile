# 基础镜像，可以先执行 docker pull openjdk:17-jdk-slim
FROM openjdk:17-jdk-slim

# 时区
ENV TZ=PRC
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 运行数据（日志 ./data/log、故事仓库 ./docs/workspace/stories）都落在 WORKDIR 下，
# 需要持久化时挂这两个子目录即可
WORKDIR /app

ENV JAVA_OPTS=""
ENV PARAMS=""

ADD target/novel_factory-app.jar /app/novel_factory-app.jar

EXPOSE 8080

# $JAVA_OPTS 必须排在 -jar 之前：放到 -jar 之后会被当成传给 main 的程序参数，JVM 收不到
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/novel_factory-app.jar $PARAMS"]
