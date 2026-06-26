# Ollama 모델 런타임 선택 설계 (Local-only)

> 상태: **설계 구상(미구현)**. 구현 전 합의용. 코드/설정 변경은 별도 단계.
> 범위 확정: **로컬 Ollama 모델만** 사용자가 런타임에 선택. 외부 프로바이더(Claude/OpenAI/Gemini) 도입하지 않음.
> 이유: 로컬 LLM 채택 동기가 **보안(데이터가 외부로 안 나감) + 비용(과금 없음)**. 외부 프로바이더는 이 두 전제를 깨므로 범위에서 제외.

---

## 1. 목표

- 프론트엔드가 요청 본문에 **모델 이름**(예: `gemma4:e4b`, `qwen2.5:7b`)을 실어 보내면, 통합 에이전트(`POST /api/agent`)가 **그 모델로** 답변을 생성한다.
- 모델은 모두 **같은 로컬 Ollama 서버**(`localhost:11434`)에 있는 것. 바뀌는 건 호출 시 `model` 옵션 하나뿐.
- 허용 모델은 **서버 allow-list**로 제한(오타·미설치 모델·과도한 VRAM 모델 차단).
- 기존 통합 에이전트 흐름(Router → DIRECT/RESEARCH/CLARIFY), 도구 단일화(`ToolProvider`), 가드레일/관측 advisor, budget 정지조건은 **그대로**. 외부 연동·키·데이터 거버넌스 이슈는 **없음**(로컬 동일).

### 비목표
- 외부 AI 프로바이더 연동.
- 모델 자동 다운로드/설치(운영자가 `ollama pull` 로 사전 준비).
- 여러 모델 동시 호출(ensemble). 한 요청은 한 모델.

---

## 2. 외부 프로바이더 대비 단순해지는 점

| 항목 | 외부 프로바이더(폐기안) | **로컬 Ollama만(본안)** |
|---|---|---|
| API 키 관리 | 필요(유출 위험) | **불필요** |
| 데이터 거버넌스(PII/외부 전송) | 핵심 이슈 | **해당 없음**(로컬) |
| base-url/SSRF | 검증 필요 | 고정(로컬 1개) |
| ChatModel 빈 | 프로바이더별 N개, 자동구성 충돌 | **OllamaChatModel 1개**(현행) |
| 옵션 비대칭(temperature 400 등) | 프로바이더별 정책 | **단일 OllamaOptions** |
| 비용 통제 | 과금 방어 필요 | **과금 없음**(VRAM/지연만 고려) |
| 폴백/refusal | 필요 | 단순(모델 미설치 정도) |

→ 남는 문제는 사실상 **"요청마다 `OllamaOptions.model` 만 바꾸기 + allow-list 검증 + VRAM 고려"** 로 축소된다.

---

## 3. 핵심 설계 결정

| # | 결정 | 선택 | 근거 |
|---|---|---|---|
| D1 | 프론트가 보내는 것 | **모델 이름 문자열만** | 프로바이더 개념 불필요(전부 Ollama) |
| D2 | 허용 모델 | **서버 allow-list** 검증 | 미설치/오타/과도 VRAM 모델 차단 |
| D3 | 모델 교체 방식 | 새 ChatClient 빈을 만들지 않고 **호출 시 `.options(OllamaOptions.model(...))` 로 per-request 오버라이드** | 가장 단순. 빈/캐시 불필요 |
| D4 | 선택 모델 적용 범위 | **DIRECT 답변 + RESEARCH 워커만** 선택 모델. Router·명확화·분해·합성·평가는 **기본 모델 고정** | 사용자 대면 생성물만 사용자가 고른 모델로. 내부 분류/판정은 기본 모델로 일관·저비용 (확정) |
| D5 | 미지정 시 | `spring.ai.ollama...model` 기본값(`gemma4:e4b`) | 하위호환 |
| D6 | 미설치/실패 모델 | 명확한 에러 또는 기본 모델 폴백(설정) | graceful degrade |

### D4 보충 — DIRECT/워커만 적용 (확정)
- 선택 모델은 **사용자 대면 텍스트를 생성하는 지점에만** 적용: `UnifiedAgentService.direct()`(DIRECT 답변)과 `OrchestratorService.runWorker()`(RESEARCH 근거 수집 워커).
- **기본 모델 고정**: Router 분류·명확화(Clarify)·분해(decompose)·합성(synthesize)·평가(Evaluator). 이들은 내부 판정/구조화로 일관성과 저비용이 중요.
- VRAM 스왑 주의: 선택 모델이 기본 모델과 다르면 한 요청 안에서 두 모델(기본=내부, 선택=DIRECT/워커)이 쓰여 **스왑이 일어날 수 있다**. VRAM 이 둘을 동시 상주 못 하면 리로드 지연 발생 → allow-list 모델 크기/수를 VRAM 에 맞춰 제한(§7). 같은 모델을 고르면(또는 미지정) 스왑 0.
- `executedModel`(응답) = DIRECT/워커에 실제 적용한 모델.

---

## 4. 요청/응답 변경

### 4.1 `ChatRequest` 확장 (선택 필드 1개)
```
record ChatRequest(
    @NotBlank String message,
    String conversationId,
    String model        // optional. 예: "qwen2.5:7b". 미지정 시 기본 모델
)
```
- 검증: allow-list 밖 모델 → `400`(기존 `GlobalExceptionHandler` 형식, 사용 가능한 모델 목록 안내).

### 4.2 `UnifiedAgentResponse` 확장
```
... + String executedModel    // 실제로 답변 생성에 쓴 모델(폴백 시 요청값과 다를 수 있음)
```
- 어떤 모델이 답했는지 투명하게 노출(라우팅의 routed/executed 노출과 동일 철학).

### 4.3 모델 목록 조회 엔드포인트(신규, 선택)
- `GET /api/agent/models` → allow-list(이름 + 표시명 + 기본 여부) 반환. 프론트가 드롭다운 구성.

---

## 5. 컴포넌트 설계

| 컴포넌트 | 책임 |
|---|---|
| `OllamaModelProperties`(@ConfigurationProperties `kyuloud.ai.ollama`) | allow-list `models`, `default-model`, 미설치 정책 바인딩 |
| `ModelCatalog` | `validate(model)` / `isAllowed` / `defaultModel()` / `list()` |
| `RuntimeModelOptions`(헬퍼) | 선택 모델명 → `OllamaOptions`(temperature 등 기존 정책 유지) 생성 |
| `UnifiedAgentController`(수정) | `ChatRequest.model` 해석 → 검증 → 서비스에 모델명 전달, `models` 조회 엔드포인트 |
| `UnifiedAgentService`(수정) | 모델명을 받아 흐름 전체에 적용. 호출 시 `.options(runtimeOptions)` 부착 |
| `OrchestratorService`(수정) | 워커/합성 호출에 동일 `.options(...)` 부착 |
| `RouterService`/`ClarificationService`/`EvaluatorService` | D4(요청 전체 적용) 채택 시에도 동일 모델 옵션 전달(스왑 0) |

> **도구는 이미 `ToolProvider` 로 단일화** → 모델이 바뀌어도 `.tools(toolProvider.tools())` 불변. 모델만 `.options()` 로 갈아끼움.

### 5.1 핵심 메커니즘 — per-request 옵션 오버라이드(D3)
빈을 새로 만들지 않는다. 기존 `workerChatClient`(Ollama) 를 그대로 쓰되, 호출 시 모델만 바꾼다:
```
var runtimeOptions = OllamaOptions.builder()
        .model(selectedModel)          // 사용자가 고른 모델
        .temperature(...)              // 기존 정책 유지
        .build();

workerChatClient.prompt()
    .system(directSystemPrompt)
    .messages(history)
    .user(userMessage)
    .tools(toolProvider.tools())
    .options(runtimeOptions)           // ← 이 한 줄이 모델 교체
    .toolContext(ctx.tracer().asToolContext())
    .call().content();
```
- `ChatClientRequestSpec.options(ChatOptions)` 가 빈의 기본 옵션을 **이 호출에 한해** 덮어쓴다.
- ChatClient 빈/캐시를 늘리지 않으므로 `ChatClientConfig` 변경 최소(빈 구조 유지). Router/워커/합성 호출부에 `.options(runtimeOptions)` 만 추가.

### 5.2 모델명을 흐름에 전달
- `UnifiedAgentService.agent(conversationId, message, model)` 시그니처에 `model` 추가(미지정 시 기본).
- 내부에서 `OllamaOptions runtimeOptions` 1개를 만들어 Router/DIRECT/Orchestrator(워커·합성)에 동일하게 넘김(D4).
- `AgentContext` 에 선택 모델/옵션을 담아 흐름 전체가 공유하는 방식도 가능(현 tracer/budget 보유 방식과 일관).

---

## 6. 설정(config) 형태 초안

```yaml
kyuloud:
  ai:
    ollama:
      default-model: gemma4:e4b
      # 운영자가 ollama pull 로 미리 설치해 둔 모델만 등록(allow-list)
      models:
        - name: gemma4:e4b
          display-name: "Gemma 4 (e4b, 기본)"
        - name: qwen2.5:7b
          display-name: "Qwen2.5 7B"
        - name: llama3.1:8b
          display-name: "Llama 3.1 8B"
      # 요청 모델이 allow-list엔 있으나 Ollama에 미설치/로드 실패일 때
      on-unavailable: default   # default(기본 모델로 폴백) | error
```
- `spring.ai.ollama.base-url` 등 연결 설정은 기존 그대로(로컬 1개).
- VRAM 한계를 고려해 **allow-list 는 작게**(동시 사용 패턴상 무리 없는 모델만).

---

## 7. 운영/제약 (로컬 특유)

- **VRAM & keep_alive**: 서로 다른 모델을 번갈아 쓰면 첫 사용 시 로드 지연. Ollama `keep_alive` 로 상주 시간 조절. allow-list 모델 수와 크기를 VRAM 에 맞춰 제한.
- **모델 사전 설치**: 자동 pull 하지 않음. allow-list 등록 전 `ollama pull <model>` 필수. 미설치 모델 요청 시 `on-unavailable` 정책대로 처리.
- **기능 차이(구조화 출력/도구)**: 모델마다 tool-calling·구조화 출력 신뢰도가 다름. D4(요청 전체 적용)에서 약한 모델은 Router 구조화가 흔들릴 수 있으나 **DIRECT 폴백**이 흡수. allow-list 에는 도구 호출이 되는 모델 위주로 등록 권장.
- **관측**: `MetricsAdvisor` 에 `model` 태그를 추가하면 모델별 지연·토큰 비교 가능(모델 선택 가이드 데이터).

---

## 8. 단계적 구현 계획

1. **P1 — 카탈로그/검증/조회**
   - `OllamaModelProperties`(allow-list) + `ModelCatalog`(검증) + `ChatRequest.model` 확장 + 검증(400).
   - `GET /api/agent/models` 조회 엔드포인트. 응답에 `executedModel` 노출.
   - 아직 흐름은 기본 모델로만 동작(검증/배선만).
2. **P2 — per-request 모델 적용**
   - `UnifiedAgentService.agent(..., model)` + `RuntimeModelOptions` 생성.
   - DIRECT/워커/합성(+ D4면 Router·평가까지) 호출부에 `.options(runtimeOptions)` 부착.
   - 실호출 검증: 같은 질문을 모델 바꿔가며 `executedModel` 과 답변 차이 확인, 스왑 동작 확인.
3. **P3 — 정책/관측 마무리**
   - `on-unavailable` 폴백 처리(미설치/로드 실패), `MetricsAdvisor` model 태그.

각 단계: 컴파일 통과 → 사용자 실호출 검증 → 사용자 커밋. 단위테스트는 4e 보류 정책 유지.

---

## 9. 미해결 질문(구현 전 확정)

1. ~~D4 확정~~ → **확정: DIRECT/워커만 적용**(내부 역할은 기본 모델 고정).
2. **allow-list 초기 모델**: 어떤 Ollama 모델들을 등록할지(설치 여부·VRAM·tool-calling 지원 기준).
3. **`on-unavailable` 기본값**: 기본 모델 폴백(추천) vs 에러.
4. **모델 옵션 차등**: 모델별 temperature 등 옵션을 다르게 둘지(예: 분류용 낮은 temp). 우선은 공용 정책 유지.

---

## 부록 — 관련 기존 자산
- `ToolProvider`/`AgentToolProvider` — 도구 단일 주입(모델이 바뀌어도 도구 코드 불변).
- `ChatClientConfig` — router/worker/planner/chat 빈(Ollama). 본안은 빈 구조 변경 없이 호출부 `.options()` 만 추가(P2).
- Phase 6 `Budget` — 요청당 LLM 호출/시간 상한(로컬에선 폭주 방지·지연 상한 의미).
- `GuardrailAdvisor`/`LoggingAdvisor`/`MetricsAdvisor` — 신규 옵션 적용 후에도 그대로 적용.
- `application-local.yaml` `spring.ai.ollama.*` — 연결/기본 모델 설정(그대로 사용).
```
