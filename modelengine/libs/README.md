# Compile-only 의존성 출처

이 폴더의 세 jar는 컴파일에만 사용하며 RemoteRagdoll 결과 jar에 포함하지 않는다.
서버에는 GCBAPI, ModelEngine, packetevents를 별도 플러그인으로 설치해야 한다.

| 파일 | 출처 | SHA-256 |
| --- | --- | --- |
| `GCBAPI-1.2.0-26.3.jar` | GCBAPI `d67b72e4cf4b55ae1b0d9986b0d484599593e6f1`의 분리된 worktree에서 전체 `shadowJar` 빌드 | `B20C312CFDA78662FE3ADC157DC9CFB3967C6E43892956F880D87DB443E65C04` |
| `ModelEngine-R4.1.1.jar` | 같은 커밋의 `libs/ModelEngine-R4.1.1.jar`를 `git show`로 추출 | `065DA77F6CC93C7C75A64D6864BD7E763977348DD97AC96EF245C5F140D0C3DB` |
| `packetevents-spigot-2.14.0.jar` | 지정된 `GCBServer/20260412FOC/personal/파오캐/plugins` jar 복사 | `060087C58EC268EAE7DD0EB1F17AC3BEA1EAADDA1693414141FF64E506091BD7` |

GCBAPI는 시그니처만 만든 stub jar가 아니다. 1.21.8 로컬 작업트리 및 그 빌드 결과는 사용하지 않았다.
빌드 일자: 2026-09-26. 빌드 환경: 기존 Temurin 25.0.3,
Gradle 9.7.1, paperweight.userdev 2.0.0-beta.23,
Paper dev bundle `26.3.build.5-alpha`, Shadow 9.0.2.
`BUILD SUCCESSFUL in 2m 56s` 확인.

GCBAPI 원본 작업트리는 그대로 두고 다음 명령으로 별도 worktree를 만들었다.

```powershell
git -C ../GCBAPI worktree add --detach ../RemoteRagdoll-dependency-build d67b72e4cf4b55ae1b0d9986b0d484599593e6f1
$env:JAVA_HOME = '<JDK 25 경로>'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
# 설치되어 있는 Gradle 9.7.1의 gradle.bat 사용
gradle --no-daemon -p ../RemoteRagdoll-dependency-build shadowJar
Copy-Item ../RemoteRagdoll-dependency-build/build/libs/GCBAPI-1.2.0.jar libs/GCBAPI-1.2.0-26.3.jar
```

jar 파일은 Git에서 제외한다. 새 개발 환경에서는 정당하게 보유한 ModelEngine R4.1.1 jar와
위 커밋에서 빌드한 GCBAPI jar를 같은 파일명으로 배치한다.

26.3 소스 및 실제 jar에서 확인한 사용 API는 다음과 같다. 기존 호출과 시그니처가 같다.

```java
org.EIQUI.GCBAPI.modelengine.MEAPI
    public static ActiveModel getActiveModel(Entity e, String modelID);
    public static Location getBoneLocation(Entity e, String modelID, String boneID);

org.EIQUI.GCBAPI.particle.ComplexParticleAPI
    public static void Dot(String particle, Location center,
                           double x, double y, double z, int amount,
                           double speed, boolean force, Object data);
```
