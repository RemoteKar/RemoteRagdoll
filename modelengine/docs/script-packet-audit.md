# 패킷 렌더 Skript 호환 검사

`validation/audit_packet_scripts.py`는 legal14 저장소의
`dc7fe59a1434a8e5b0c4f3890b8a38f1104c29d9`를 `git show`로만 읽는다.
원본 작업 트리와 서버 폴더는 수정하지 않는다.

```powershell
python validation/audit_packet_scripts.py
```

검사한 `.sk` 파일은 전체 1,108개다. `PhysicsBody.getDisplays()` 직접 호출은
없다. `getBodies()`는 `b/래그돌.sk:318`, `physicstest.sk:331`에 있다.
직접 `setAngularVelocity()` 호출은 `a/Physics.sk:46`의 기존 래퍼와
`b/래그돌.sk:320`에 있다. `physicstest.sk:308`은 기존 래퍼를 호출한다.
Jolt 관련 클래스 직접 import 5개는 `a/Physics.sk`에만 있다.

`b/래그돌.sk:329`의 `Ragdoll_sweep`는 실제 월드 엔티티만 순회한다.
`GCB:RagdollHost`에 Model_remove/kill, `GCB:Ragdoll`에 kill을 수행하므로
패킷 파츠가 없다고 호출 자체가 실패할 경로는 없다. 패킷 파츠 정리는
직전의 `Physics_clearAll()`과 플러그인의 별도 소유권 관리가 담당한다.
호스트 및 실제 엔티티 폴백·디버그 파츠에는 기존 sweep이 계속 적용된다.

배포 `Physics.sk`는 원본 바이트에서 import 패키지 5개만 바꾼 내용 뒤에
다음 두 함수와 설명 주석을 추가했다. 기존 함수 58개의 선언·본문과
개행을 포함한 모든 기존 바이트를 보존했다. 함수 총수는 60개다.

```skript
function Ragdoll_setPacketRender(b:boolean):
	set JoltRagdoll.PACKET_RENDER to {_b}

function Ragdoll_getPacketRender() :: boolean:
	return JoltRagdoll.PACKET_RENDER
```

이 스위치는 앞으로 생성할 파츠의 렌더 방식에 적용되며 이미 생성한 파츠의
렌더 방식을 바꾸지 않는다. `getDisplays()`가 패킷 파츠를 돌려주지 않는
공개 API 특성은 Java Javadoc에 명시한다.

원본 SHA-256은 `6f1a6a5d93b6b7570ffbb511ab4e4245289bbada20c70312f16249757dbcbaa5`,
추가 후 SHA-256은 `b5147223bf2dd19861665f022287e85416dfa26f16405da796de9d856b4e0019`다.
기계 판독 결과는 `../output/RemoteRagdoll/script-packet-audit.json`에 저장한다.
출력 사본과 저장소 `scripts/Physics.sk`의 바이트 일치도 검사한다.

이 검사는 스크립트 정적 호환성 검사다. 실제 Skript 파싱·클라이언트 시각
결과는 별도 스모크/인게임 검증 범위를 따른다.
