# RemoteRagdoll (ModelEngine)

GCB 서버의 Jolt 물리·래그돌을 GCBAPI 에서 떼어 낸 Paper 26.3 플러그인. ModelEngine 모델이 있는 엔티티를 **본마다 강체**로
무너뜨리고, 조각은 PacketEvents 아이템 디스플레이(패킷)로 그린다. 일반 물리 객체(디스플레이에 물리 붙이기, 상자),
액체 입자, 절단·허리 분리·코스튬도 있다.

Skript(skript-reflect)에서 [`scripts/Physics.sk`](scripts/Physics.sk) 의 함수로 쓴다.

```
Physics_isAvailable()                     Ragdoll_spawn(entity, modelId, impulse, life)
Physics_attach(display, half, mass, life) Ragdoll_push(r, v) / Ragdoll_remove(r) / Ragdoll_freeze(r)
Physics_box(location, half, mass, life)   Ragdoll_cut(r, bone, power) / Ragdoll_cutAll(r, power)
Physics_impulse(b, v) / Physics_remove(b) Ragdoll_splitWaist(r, power) / Ragdoll_attachCostume(r, item)
Physics_liquid(...)                       Physics_clear(world) / Physics_clearAll()
```

## 필요한 것

- Minecraft 26.3 (Paper / Purpur), Java 25, JVM 옵션 `--enable-native-access=ALL-UNNAMED`
- 플러그인: GCBAPI, ModelEngine R4, packetevents 2.14 (Skript + skript-reflect 는 `Physics.sk` 를 쓸 때)

## 빌드

`libs/` 에 compile-only jar 세 개(GCBAPI, ModelEngine, packetevents)를 넣고 `./gradlew build`. 파일명과 출처는
[libs/README.md](libs/README.md). 이 jar 들은 저장소에 없다.

## 문서

- [PORTING.md](PORTING.md) — GCBAPI 에서 분리·26.3 포팅 내역, 패킷 렌더, 검증 기록
- [docs/](docs) — 패킷 프로토콜·렌더·스크립트 감사, 서버 스모크 기록
- [validation/](validation) — 감사·스모크 스크립트 (경로는 `GCBSERVER_ROOT`, `JAVA_HOME` 환경 변수로 바꾼다)
