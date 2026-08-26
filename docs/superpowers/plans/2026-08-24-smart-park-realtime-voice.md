# 智慧园区实时语音助手实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to execute this plan task-by-task, and use `superpowers:test-driven-development` for every behavior change.

**Goal:** 实现点击开始/停止、实时转写、真实只读工具调用、流式文本回答和可中断 TTS 的端到端在线语音助手。

**Architecture:** 浏览器用 AudioWorklet 采集 PCM，通过单个 session WebSocket 发送二进制音频和 JSON 控制帧。服务端 session state machine 串联 DashScope streaming ASR、受约束 Agent 工具路由、答案校验与 streaming TTS；统一事件协议向展台展示真实过程。

**Tech Stack:** Spring WebSocket, Spring AI Alibaba DashScope 2.0 streaming ASR/TTS, ReactAgent/ChatModel, Java concurrency, Vue 3 Web Audio API/AudioWorklet, Vitest.

**Spec:** `docs/superpowers/specs/2026-08-24-smart-park-p1-voice-multiagent-analytics-design.md`

**Depends on:** 2.0 基线与统一执行事件计划完成；可调用已存在的园区只读工具，若需运营 SQL 则再依赖运营分析计划。

**Global constraints:** 只走在线真实 ASR/LLM/tool/TTS；无 mock/fallback；原始音频不落盘不进事件；回答必须在工具证据/政策引用校验后才进入 TTS；点击麦克风可中断当前 TTS；输入上限 10 秒、Agent 15 秒、TTS 首块 5 秒；错误显式展示并回到可重试状态。

## 文件结构与职责

- `src/main/java/com/example/smartpark/voice/model/*`：session 状态、控制帧、服务器帧、转写和回答。
- `src/main/java/com/example/smartpark/voice/port/*`：StreamingAsrPort、StreamingTtsPort、VoiceAnswerPort。
- `src/main/java/com/example/smartpark/voice/adapter/dashscope/*`：真实 DashScope ASR/TTS 适配器。
- `src/main/java/com/example/smartpark/voice/VoiceSession.java`：状态机和单 session 并发边界。
- `src/main/java/com/example/smartpark/voice/VoiceSessionService.java`：创建、帧路由、超时、中断、清理。
- `src/main/java/com/example/smartpark/voice/VoiceAnswerAgent.java`：意图/工具/流式答案与证据校验。
- `src/main/java/com/example/smartpark/web/VoiceSessionController.java`、`VoiceWebSocketHandler.java`：HTTP + WS。
- `ui/src/audio/pcm-capture.worklet.ts`、`pcm-player.ts`：采集、重采样、播放队列与取消。
- `ui/src/composables/useVoiceSession.ts`、`ui/src/components/voice/*`：状态、协议、实时 UI。

## Task 1：定义可验证的协议与状态机

- [x] 先写 `VoiceSessionStateMachineTest.java`，覆盖：

  - `IDLE -> LISTENING -> ASR_FINALIZED -> REASONING/TOOL_CALLING -> ANSWER_STREAMING -> SPEAKING -> IDLE`；
  - `START_INPUT` 在 SPEAKING 时先中断再 LISTENING；
  - 非法二进制帧/控制帧顺序拒绝；
  - ERROR 可回到 IDLE；CLOSED 不可恢复。

- [x] 定义 client controls `START_INPUT|COMMIT_INPUT|INTERRUPT_OUTPUT|CLOSE_SESSION`；server frames `SESSION_STATE|ASR_PARTIAL|ASR_FINAL|TOOL_EVENT|ANSWER_DELTA|AUDIO_CHUNK|ERROR`。所有 JSON 帧含 `sessionId`、`messageId`、`sequence`。

- [x] 实现纯 Java `VoiceSessionStateMachine`，不含网络和厂商 SDK。

- [x] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=VoiceSessionStateMachineTest,VoiceProtocolTest test
git add -- src/main/java/com/example/smartpark/voice/model src/main/java/com/example/smartpark/voice/VoiceSessionStateMachine.java src/test/java/com/example/smartpark/voice
git commit -m "feat: define realtime voice session protocol"
```

## Task 2：建立音频格式与内存边界

- [x] 写 `AudioFrameValidatorTest.java`，覆盖唯一接受的 PCM 规格、单帧字节上限、累计 10 秒上限、空音频、过快发送、状态不匹配；断言对象不保留完成 turn 的原始 byte[]。

- [x] 实现 `AudioFormatSpec(sampleRate=16000, channels=1, sampleSizeBits=16)`、frame validator 和仅内存 ring buffer；COMMIT 后把 buffer 所有权交给 ASR 并清零引用。

- [x] 在日志捕获测试中断言音频 base64/byte 内容不出现。

- [x] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=AudioFrameValidatorTest,VoiceAudioPrivacyTest test
git add -- src/main/java/com/example/smartpark/voice/audio src/test/java/com/example/smartpark/voice/audio
git commit -m "feat: bound ephemeral voice audio buffers"
```

## Task 3：实现真实 DashScope streaming ASR 适配器

- [x] 定义 `StreamingAsrPort`，测试 fake port 的 partial/final/close/error 语义；应用代码不得依赖 mock 实现。

- [x] 创建 `DashScopeStreamingAsrAdapter`，包装 2.0 的 `StreamingTranscriptionModel`/`DashScopeWebSocketAsrApi`。将 SDK callback 串行化为 session frame，供应商错误映射为安全错误码。

- [x] 写 adapter 合同测试，使用本地假的 SDK facade 验证音频传递、partial 顺序、commit、cancel、close；真正联网 smoke 放在加固计划，不进入默认单测。

- [x] 配置 bean 条件必须是“凭据存在则创建，否则应用启动失败”，不得注册 mock 替代品。

- [x] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=StreamingAsrPortTest,DashScopeStreamingAsrAdapterTest,VoiceProviderConfigurationTest test
git add -- src/main/java/com/example/smartpark/voice/port/StreamingAsrPort.java src/main/java/com/example/smartpark/voice/adapter/dashscope/DashScopeStreamingAsrAdapter.java src/main/java/com/example/smartpark/voice/VoiceProviderConfiguration.java src/test/java/com/example/smartpark/voice
git commit -m "feat: stream voice input to dashscope asr"
```

## Task 4：实现证据约束的语音回答 Agent

- [x] 写 `VoiceAnswerAgentTest.java`，覆盖告警、能耗、停车政策三个问题。固定模型必须调用对应只读工具/知识；未调用工具却给出数据、引用不存在政策、尝试写操作均被拒绝。

- [x] 构建 `VoiceAnswerAgent`：工具集合仅含 Alert/Device/Energy/Security/ParkKnowledge 的只读接口；停车政策必须有知识引用；实时值必须有本 turn 的 tool evidence。

- [x] 定义 `VoiceAnswer(text, evidenceRefs, toolCalls)`；`VoiceAnswerValidator` 在 TTS 前检查数字、告警/设备 ID 与引用均可追溯。校验失败显式结束，不生成“合理猜测”。

- [x] 真实工具开始/完成和文本 delta 发布到 WS 与统一事件；UI 工具事件由后端事实产生。

- [x] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=VoiceAnswerAgentTest,VoiceAnswerValidatorTest,ParkToolsTest test
git add -- src/main/java/com/example/smartpark/voice/VoiceAnswerAgent.java src/main/java/com/example/smartpark/voice/VoiceAnswerValidator.java src/main/java/com/example/smartpark/voice/model/VoiceAnswer.java src/test/java/com/example/smartpark/voice
git commit -m "feat: answer park voice queries with evidence"
```

## Task 5：实现 streaming TTS 和可取消播放流

- [x] 定义 `StreamingTtsPort`，写测试覆盖 text delta 合并、首块、后续块、完成、供应商错误、cancel 后不再发块。

- [x] 实现 `DashScopeStreamingTtsAdapter`，包装 `StreamingInputTextToSpeechModel`；只接收已校验 VoiceAnswer 的文本流。

- [x] 中断使用 per-turn cancellation token；取消同时停止供应商订阅、清空待发送块并发布 `OUTPUT_INTERRUPTED`。晚到 callback 必须按 turnId 丢弃。

- [x] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=StreamingTtsPortTest,DashScopeStreamingTtsAdapterTest,VoiceOutputCancellationTest test
git add -- src/main/java/com/example/smartpark/voice/port/StreamingTtsPort.java src/main/java/com/example/smartpark/voice/adapter/dashscope/DashScopeStreamingTtsAdapter.java src/test/java/com/example/smartpark/voice
git commit -m "feat: stream interruptible dashscope speech output"
```

## Task 6：编排 session 生命周期、HTTP 与 WebSocket

- [x] 写 `VoiceSessionServiceTest.java`，覆盖完整 turn、ASR 空文本、输入/Agent/TTS 超时、中断、重复 commit、连接关闭、两个 session 隔离、清理后无音频引用。

- [x] 写 `VoiceSessionControllerTest` 与 `VoiceWebSocketHandlerTest`，覆盖：

  - `POST /api/voice/sessions`；
  - `GET /api/voice/sessions/{sessionId}`；
  - `/ws/voice/sessions/{sessionId}` 二进制和 JSON 帧；
  - 未知 session、超大 frame、非法 control、鉴权/Origin 策略。

- [x] 实现 service、store、controller、handler；同 session 通过 serial executor 处理；每个 turn 拥有独立 ID/cancellation；CLOSE/网络断开关闭 ASR/TTS 并移除 buffer。

- [x] 所有状态转换同步发布统一事件，terminal session 完成事件流。

- [x] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=VoiceSessionServiceTest,VoiceSessionControllerTest,VoiceWebSocketHandlerTest test
git add -- pom.xml src/main/java/com/example/smartpark/voice/VoiceSession.java src/main/java/com/example/smartpark/voice/VoiceSessionService.java src/main/java/com/example/smartpark/voice/VoiceSessionStore.java src/main/java/com/example/smartpark/web/VoiceSessionController.java src/main/java/com/example/smartpark/web/VoiceWebSocketHandler.java src/main/java/com/example/smartpark/web/VoiceWebSocketConfiguration.java src/test/java/com/example/smartpark/voice src/test/java/com/example/smartpark/web
git commit -m "feat: expose realtime voice sessions"
```

## Task 7：浏览器采集、播放与中断

- [ ] 先写 `pcm-player.spec.ts` 和 `useVoiceSession.spec.ts`，mock AudioContext/WebSocket，覆盖采集帧、commit、partial/final、音频顺序播放、麦克风点击中断、晚到块丢弃、连接错误。

- [ ] 创建 AudioWorklet：麦克风 float32 重采样为 16k mono int16，每 20ms 发送；主线程不得用已弃用 ScriptProcessorNode。

- [ ] 创建 player：解码服务器约定格式、按 sequence 排队、cancel 立即 stop 当前 source 且清空队列。

- [ ] `useVoiceSession` 只根据服务器状态驱动 UI；本地点击只发 control，不直接把状态伪设为成功。

- [ ] 验证并提交：

```powershell
Push-Location ui
npm.cmd run test:unit -- voice
npm.cmd run typecheck
Pop-Location
git add -- ui/src/audio ui/src/types/voice.ts ui/src/services/voiceApi.ts ui/src/composables/useVoiceSession.ts ui/src/composables/useVoiceSession.spec.ts
git commit -m "feat: capture and play realtime park voice audio"
```

## Task 8：语音展台页面

- [ ] 写 `VoiceAssistantPage.spec.ts`，覆盖麦克风权限、状态文案、实时识别文本、工具卡、流式回答、TTS 状态、中断、明确错误与重试。

- [ ] 实现页面：中央麦克风主控、实时转写、回答字幕与证据；右侧共享轨迹；不得展示 raw audio、prompt 或隐含工具参数。

- [ ] `App.vue` 加“实时语音”入口和共享 runId；离开页面发送 CLOSE 并释放 MediaStream tracks。

- [ ] 验证并提交：

```powershell
Push-Location ui
npm.cmd run test:unit -- VoiceAssistant
npm.cmd run build
Pop-Location
git add -- ui/src/components/voice ui/src/App.vue ui/src/styles.css
git commit -m "feat: add realtime voice assistant console"
```

## Task 9：配置、隐私与回归

- [ ] 在 `application.yml` 增加音频规格、10 秒输入、15 秒 agent、5 秒 TTS 首块、allowed origins；密钥仍只来自环境变量。

- [ ] 运行后端全测、前端全测/构建、敏感数据测试；搜索日志与事件 DTO 确认没有 `byte[]`、base64、API key、原 prompt 字段。

## 完成闸门

- 真实 ASR partial、工具调用、回答 delta、TTS chunk 可在一个 session 中对齐。
- 麦克风点击能取消当前 TTS，晚到音频不播放。
- 原始音频不落盘、不进事件、不出现在日志；连接关闭后内存引用释放。
- 正常链路 5–10 秒；供应商失败/超时明确失败，不返回替代文本或静音假成功。
