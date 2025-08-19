# Base image
FROM openjdk:17-jdk-alpine

# 작업 디렉토리 설정
WORKDIR /app

# 빌드된 JAR 파일을 복사
COPY target/sj-lab-mapservice-rest.jar /app/sj-lab-mapservice-rest.jar

# 포트 노출 (Eureka 서버의 기본 포트)
#EXPOSE 8761

#기본 프로파일을 local로 설정 (환경 변수로 설정)
# helm 주입으로 변경
# ENV SPRING_PROFILES_ACTIVE=prod

# 애플리케이션 실행
ENTRYPOINT ["java", "-jar", "/app/sj-lab-mapservice-rest.jar"]