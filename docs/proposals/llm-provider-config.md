# LLM 提供方配置（provider / 模型能力 / 思考等级）

> 2026-11-25 设计草案。2026-11-26 已在 `feat/llm-provider-config` 接入各协议，实际字段、连接示例和待定选项见[协议与参数表](../architecture/llm-transports.md)。
> 主要参考：pi-ai 1.6.0（`dist/types.d.ts`、`dist/providers/compat-schema.d.ts`、`dist/api/openai-completions.js`、`anthropic-messages.js`、`openai-responses.js`、`google-*.js`、`providers/data/*.json`、`README.md`）。只读源码，没有运行。

## 0. 问题

- 每个请求都写死 `"temperature": 0.4`（`OpenAiChatCompletions.buildRequestBody` 和 `completeText` 两处）。用户的 New API 后面是开了思考的 Claude，上游直接 400：“`temperature` may only be set to 1 when thinking is enabled or in adaptive mode”。重构前也一样。
- 配置只有 `apiUrl / apiKey / model / httpTimeoutSeconds / useToolCall / useStreaming`，没法表达“这个模型不收 temperature”“用 max_tokens 还是 max_completion_tokens”“思考开多深”。
- 思考文本（`reasoning_content` / `reasoning`）会被解析，但：界面不显示；`OpenAiMessageCodec.toWire` 不把它写回请求；会话存盘走 `toWire`，所以存盘后也丢了（`fromWire` 能读 `reasoning_content`，但写的一侧从来不写）。DeepSeek 这类服务商在多轮工具调用时要求把 `reasoning_content` 回传，现在会出错或掉质量。
- 没有 `max_tokens`、没有 `stream_options.include_usage`、不解析 usage，所以没有真实 token 数和缓存命中。

## 1. 现在的协议格局（只列影响本 mod 的部分）

pi-ai 把“线上协议”（`KnownApi`）和“服务商”分开：同一个协议有很多服务商实现，差异用 `compat` 标志描述。

```ts
type KnownApi = "openai-completions" | "openai-responses" | "anthropic-messages"
  | "google-generative-ai" | "google-vertex" | "bedrock-converse-stream" | "mistral-conversations" | ...
```

| | OpenAI Chat Completions（现有） | OpenAI Responses | Anthropic Messages | Gemini generateContent |
|---|---|---|---|---|
| 端点 | `/v1/chat/completions` | `/v1/responses` | `/v1/messages` | `/v1beta/models/{m}:streamGenerateContent` |
| 系统提示 | `system`（OpenAI 官方推理模型用 `developer`，别家多数拒绝 `developer`） | `instructions` 或 `developer` 消息 | 顶层 `system` | `systemInstruction` |
| 流式事件 | `choices[].delta` 的 `content` / `tool_calls[i].function.arguments` 增量 + `[DONE]` | 有类型的事件：`response.output_text.delta`、`response.function_call_arguments.delta`、`response.reasoning_summary_text.delta`、`response.completed` | `message_start`、`content_block_start/delta/stop`（`text_delta`、`input_json_delta`、`thinking_delta`、`signature_delta`）、`message_delta`、`message_stop` | 每块一个 `candidates[].content.parts[]` |
| 工具调用 | `tool_calls`，结果用 `role:"tool"` + `tool_call_id` | `function_call` / `function_call_output` 条目 | `tool_use` 块，结果是 user 消息里的 `tool_result` 块 | `functionCall` / `functionResponse` part |
| 思考怎么开 | 没有统一字段：OpenAI 是 `reasoning_effort`；DeepSeek 是 `thinking:{type}`；OpenRouter 是 `reasoning:{effort}`；Qwen 是 `enable_thinking`；vLLM 是 `chat_template_kwargs` | `reasoning:{effort, summary}` | 旧模型 `thinking:{type:"enabled", budget_tokens}`；新模型 `thinking:{type:"adaptive"}` + `output_config:{effort}` | `thinkingConfig:{thinkingBudget}` 或 `thinkingLevel`，`includeThoughts` |
| 思考怎么返回 | 非标准字段 `reasoning_content` / `reasoning` / `reasoning_text`；OpenRouter 另有 `reasoning_details`（可含加密内容） | `reasoning` 条目（摘要 + `encrypted_content`） | `thinking` 块 + `signature`；可能出现 `redacted_thinking`（只有加密数据） | `thought:true` 的 part + `thoughtSignature` |
| 必须回传吗 | DeepSeek：要（pi 对 DeepSeek 默认 `requiresReasoningContentOnAssistantMessages`）；其他多数不要 | `store:false` 时要把加密 reasoning 条目原样带回，需请求 `include:["reasoning.encrypted_content"]` | 工具循环中要把带签名的 thinking 块原样带回，否则会报错或丢思考连续性 | `thoughtSignature` 要随原 part 带回 |
| 采样参数 | 一般都收 `temperature`，但推理模型/网关后面的 Claude 可能拒绝 | 推理模型通常不收 `temperature` | 开思考时 temperature 只能是 1；pi 标注 Opus 4.7 起、Sonnet 5 等直接不支持 temperature（`supportsTemperature:false`）。据 Anthropic 文档，较新 API 版本对任何模型都拒绝 temperature/top_p/top_k（见“未能核实”） | `generationConfig.temperature` |
| 输出上限 | `max_completion_tokens`（OpenAI 新）或 `max_tokens`（DeepSeek、Moonshot、Together、NVIDIA、Z.ai 等只认它） | `max_output_tokens`（≥16） | `max_tokens` **必填**，且要大于思考预算 | `maxOutputTokens` |
| 提示缓存 | 自动；OpenAI 可带 `prompt_cache_key`；经 OpenRouter 转 Claude 时要 `cache_control` | `prompt_cache_key`、`prompt_cache_retention` | 显式 `cache_control` 断点（系统、最后一个工具、最后一条消息） | 隐式 + cachedContent |
| 用量 | 要 `stream_options:{include_usage:true}` 才有；`prompt_tokens_details.cached_tokens`（DeepSeek 是 `prompt_cache_hit_tokens`）、`completion_tokens_details.reasoning_tokens` | `usage.input_tokens_details.cached_tokens`、`output_tokens_details.reasoning_tokens` | `input_tokens`、`cache_read_input_tokens`、`cache_creation_input_tokens`、`output_tokens` | `usageMetadata`（含 `thoughtsTokenCount`、`cachedContentTokenCount`） |
| 错误 | HTTP 4xx/5xx + `{"error":{...}}`；流中也可能夹 `error` 块（现有代码已处理） | `response.failed` / `error` 事件 | `{"type":"error","error":{type,message}}`，流中 `event: error`（如 `overloaded_error`） | `{"error":{code,message,status}}` |

结论：Chat Completions 是“最大公约数”，能跑通文本 + 工具 + 流式，但**思考的开关、回传和签名都只能靠各家扩展字段**，这正是网关最容易出错的地方。

## 2. pi-ai 怎么按模型决定发什么

### 2.1 不默认发 temperature

pi-ai 只在调用方显式给了 `temperature` 时才写进请求体（`openai-completions.js`：`if (options?.temperature !== undefined) params.temperature = ...`）。Anthropic 适配器还会在开思考或模型 `supportsTemperature:false` 时主动丢掉它。**这一条就是我们 400 的直接解法。**

### 2.2 模型 = 协议 + 能力 + compat

```ts
interface Model<TApi> {
  id; api: TApi; provider; baseUrl;
  reasoning: boolean;                 // 是否是推理模型
  thinkingLevelMap?: ThinkingLevelMap; // pi 等级 -> 服务商值；null = 不支持
  contextWindow: number; maxTokens: number;
  samplingParams?: Record<string, unknown>;              // 模型默认采样参数
  samplingParamsByThinkingLevel?: Partial<Record<ModelThinkingLevel, SamplingParams>>;
  compat?: OpenAICompletionsCompat | AnthropicMessagesCompat | OpenAIResponsesCompat | ...;
}
```

Chat Completions 的 compat 标志（节选，对本 mod 有用的）：

| 标志 | 含义 | pi 默认 |
|---|---|---|
| `maxTokensField` | `max_completion_tokens` 或 `max_tokens` | 按 URL 识别，DeepSeek/Moonshot/Together/NVIDIA/Z.ai 等用 `max_tokens`，其余 `max_completion_tokens` |
| `supportsDeveloperRole` | 推理模型系统提示用 `developer` | 只有 `api.openai.com` 为 true |
| `supportsReasoningEffort` | 是否收 `reasoning_effort` | 大多 true；xAI、Z.ai、Moonshot、Together、NVIDIA 等 false |
| `thinkingFormat` | 思考开关的写法：`openai`（`reasoning_effort`）、`deepseek`（`thinking:{type}`+effort）、`openrouter`（`reasoning:{effort}`）、`qwen`、`zai`、`chat-template` 等 11 种 | 按 URL 识别，默认 `openai` |
| `supportsUsageInStreaming` | 是否发 `stream_options.include_usage` | true |
| `requiresReasoningContentOnAssistantMessages` | 回放的 assistant 消息必须带 `reasoning_content`（没有就给 `""`） | 只 DeepSeek |
| `requiresThinkingAsText` | 思考要转成普通文本回传 | false |
| `requiresNonNullAssistantContent` / `requiresToolResultName` / `requiresAssistantAfterToolResult` | 各种怪服务商的消息格式要求 | false |
| `supportsStore` | 是否收 `store` | 非标准服务商 false |
| `cacheControlFormat: "anthropic"` | 在 Chat Completions 里加 Anthropic 式 `cache_control` | OpenRouter 上的 `anthropic/*` 模型 |
| `supportsStrictMode` | 工具定义能否带 `strict` | false |

规则：先按 `provider`/`baseUrl` 自动识别，再用模型上显式写的 `compat` 逐项覆盖（`getCompat`：`model.compat.x ?? detected.x`）。README 明说：自定义代理/未知端点就靠手写 compat。

Anthropic Messages 的 compat 里与本问题相关的：`supportsTemperature`、`forceAdaptiveThinking`（用 `adaptive` + effort 而不是 `budget_tokens`）、`allowEmptySignature`、`supportsCacheControlOnTools`。目录数据里例如 `claude-opus-4-8`：`forceAdaptiveThinking:true, supportsTemperature:false`；`claude-sonnet-4-5`：两者都没写（老式预算思考、可以收 temperature）。

### 2.3 思考等级

- 统一等级：`off | minimal | low | medium | high | xhigh | max`（`xhigh`/`max` 要模型的 `thinkingLevelMap` 显式开）。
- Chat Completions：按 `thinkingFormat` 写对应字段；`off` 时若 `thinkingLevelMap.off` 是字符串就显式发关闭值。
- Anthropic：自适应模型 → `thinking:{type:"adaptive"}` + `output_config:{effort}`（minimal/low→low、medium、high，`xhigh`/`max` 走映射）；老模型 → `thinking:{type:"enabled", budget_tokens}`，默认预算 minimal 1024 / low 2048 / medium 8192 / high 16384，并保证 `max_tokens` 比预算多至少 1024。
- Responses：`reasoning:{effort, summary:"auto"}` + `include:["reasoning.encrypted_content"]`，`store:false`。
- Gemini：`thinkingBudget` 或 `thinkingLevel`，关闭用预算 0。

### 2.4 换模型时的思考

`transform-messages.js`：只有**同 provider + 同 api + 同 model** 产生的 assistant 消息才原样保留思考块（含签名/加密内容）；换了模型就整块丢掉思考，工具调用 id 按需规范化。这对本 mod 的“主 Agent / 压缩 / 子 Agent 用不同模型”很重要。

## 3. New API 这类网关意味着什么

New API 同时对外提供 `/v1/chat/completions`、`/v1/responses`、`/v1/messages`、Gemini 路由，并在几种格式之间互转；项目自己也说转换是有损的，具体看渠道、上游和转换路径。

| 需求 | 走 Chat Completions 能不能表达 |
|---|---|
| 文本、工具调用、流式 | 能 |
| 不发 temperature | 能（不写就行） |
| 开/关 Claude 思考 | 只能间接：模型名后缀（`-thinking`）或新版的 `@thinking:on/off/adaptive/<预算>`、`@effort:...` 修饰；`reasoning_effort` 字段怎么映射到 Claude 我没核实到 |
| 拿到思考文本 | 网关会转成 `reasoning_content`（渠道开 `thinking_to_content` 时会变成正文里的 `<think>`） |
| 工具循环中回传 Claude 思考签名 | **不能可靠表达**。Chat Completions 没有签名字段；能否保持思考连续性完全取决于网关实现 |
| 显式 `cache_control` | 取决于网关是否透传 |
| 准确的 cache 读/写用量 | 部分 |

所以：
- **短期**：Chat Completions 够用，前提是别乱发参数，并且能按模型开/关思考。
- **需要原生协议的时候**：想要 Claude 思考在工具循环里连续（签名回传）、显式提示缓存、准确缓存计费时走 `/v1/messages`；想用 OpenAI 推理模型并保留加密推理时走 `/v1/responses`；Gemini 3 的 `thoughtSignature` 同理。New API 本身就提供这几个入口，所以原生协议客户端在同一个网关上也能用。

## 4. 给 P2S 的配置模型

### 4.1 概念

- **连接（provider）**：协议 + 地址 + 密钥 + 额外请求头。
- **模型配置（model profile）**：引用一个连接，加模型 id、能力、思考等级、可选采样参数、compat 覆盖。
- **用途（role）**：`main`、`compaction`、`subagent` 各指向一个模型配置，默认都指 `main`。

### 4.2 `p2s_client.json` 草案

```jsonc
{
  // 旧字段保留，继续可读；有 llm 块时以 llm 块为准
  "apiUrl": "...", "apiKey": "...", "model": "...",
  "httpTimeoutSeconds": 60, "useToolCall": true, "useStreaming": true,

  "llm": {
    "version": 1,
    "providers": {
      "default": {
        "api": "openai-completions",          // 第 3/4 阶段再加 anthropic-messages / openai-responses
        "url": "https://gateway.example/v1/chat/completions", // 与现有 apiUrl 一样写完整端点
        "apiKey": "...",
        "headers": {}
      }
    },
    "models": {
      "main": {
        "provider": "default",
        "id": "claude-opus-4-8",
        "preset": "auto",                     // auto | openai | deepseek | openrouter | anthropic-via-gateway | generic
        "reasoning": true,
        "thinking": "medium",                 // off | minimal | low | medium | high | xhigh | max
        "maxTokens": null,                    // null = 不发
        "temperature": null,                  // null = 不发（默认）
        "topP": null,
        "extraBody": {},                      // 原样合并进请求体，最后合并、可覆盖前面字段
        "compat": {                           // 只写要覆盖的项，其余按 preset/URL 推断
          "maxTokensField": "max_tokens",
          "supportsTemperature": false,
          "thinkingFormat": "none",
          "echoReasoning": "auto"             // auto | always | never
        }
      }
    },
    "roles": { "main": "main", "compaction": "main", "subagent": "main" }
  }
}
```

说明：
- **采样参数默认全部不发**，只有用户显式填了才发。`supportsTemperature:false` 或思考开着且 compat 说明冲突时，即使填了也丢掉并在日志里提示一次。
- **preset** 是一组 compat 默认值，学 pi 的 `detectCompat`：
  - `openai`（`api.openai.com`）：`max_completion_tokens`、`developer` 角色、`reasoning_effort`。
  - `deepseek`（URL 含 `deepseek.com`）：`max_tokens`、`thinking:{type}`、回传 `reasoning_content`（没有就发 `""`）。
  - `openrouter`：`reasoning:{effort}`，回传 `reasoning_details`。
  - `anthropic-via-gateway`：不发 temperature/top_p，思考通过模型名修饰或 `extraBody` 表达（具体写法见开放问题 2）。
  - `generic`：最保守，`system` 角色、`max_tokens`、不发任何思考字段。
  - `auto`：按 URL 选上面之一，认不出来就 `generic`。
- **`extraBody`** 等同 pi 的 `samplingParams`：给 vLLM 的 `top_k`、New API 的特殊字段等留口子，不用改代码。
- **超时、`useToolCall`、`useStreaming`** 先保留在顶层全局；以后需要再下放到模型配置。

### 4.3 压缩和子 Agent 怎么选模型

- `ClientHistoryCompactor` / `OpenAiChatCompletions.completeText` 用 `roles.compaction`；`SubagentManager` 用 `roles.subagent`（子 Agent profile 以后可以写 `"model": "<配置名>"` 覆盖）。
- 压缩请求默认 `thinking: off` 也可以，但如果它和 `main` 是同一个开着思考的 Claude，`off` 会改变请求形状；按配置原样走最安全，见开放问题 4。
- 换模型时学 pi：消息上记录产生它的 `provider/api/model`，只把思考回传给同一个模型，其他情况丢掉思考。

### 4.4 向后兼容

- 读配置：没有 `llm` 块 → 用旧的 `apiUrl/apiKey/model` 生成一个 `default` 连接 + `main` 模型，`preset:"auto"`，三个用途都指向它。行为上**唯一的变化是不再发 `temperature: 0.4`**。
- 写配置：配置界面的旧字段继续读写，并同步到 `llm.providers.default` / `llm.models.main`；第一次保存时写出 `llm` 块，旧字段也保留，降级到旧版 mod 还能用。
- 存盘的会话历史仍然是 OpenAI 线格式。第 1 阶段在 assistant 消息上多写 `reasoning_content`（旧版读的时候本来就认这个字段）。第 3 阶段要存签名时，加一个带版本号的扩展字段，不改现有字段。

## 5. 分阶段实施

### 第 1 阶段：修 400，不改配置结构（最小改动）

1. `buildRequestBody` 和 `completeText` 去掉写死的 `temperature`；`LlmRequestConfig` 增加可空的 `temperature`、`maxTokens` 和 `maxTokensField`，非空才写。
2. `P2SClientConfig` 读取可选的顶层 `temperature`、`maxTokens`（没有就是 null），配置界面暂时不加控件，或只加一个“温度（留空不发）”。
3. 顺手补上回传思考：`OpenAiMessageCodec.toWire` 在 assistant 消息有思考时写回它读到的那个字段（先只做 `reasoning_content`）；DeepSeek URL 下没有思考也发 `""`。存盘因此也能保留思考。

测试：
- `buildRequestBody` 默认不含 `temperature`、`max_tokens`、`max_completion_tokens`；配置了才有，字段名正确。
- `completeText` 请求体同上（抽出构造请求体的方法，测试时不联网）。
- `toWire` / `fromWire` 往返保留 `reasoning_content`；DeepSeek 场景空串规则。
- 旧版 `p2s_client.json`（没有新字段）能加载，生成的请求体只少了 `temperature`。
- 手动：用户的 New API + Claude 思考模型跑一个带工具调用的回合；再挑一个普通 OpenAI 兼容服务（比如 gpt-4o-mini）跑一遍，确认没退步。

### 第 2 阶段：模型配置、preset 和思考等级（仍只用 Chat Completions）

1. 新增 `llm` 块的解析和迁移（§4.4），`LlmRequestConfig` 换成“解析后的模型配置”（连接 + 模型 + 生效后的 compat）。
2. compat 解析：preset/URL 识别 → 显式 compat 覆盖，照搬 pi `getCompat` 的“逐项 `??`”。
3. 思考等级 → `reasoning_effort` / `thinking:{type}` / `reasoning:{effort}` / 不发；思考开着时自动丢 temperature/top_p。
4. `stream_options.include_usage`，解析 usage（含 cached、reasoning tokens），日志和界面显示。
5. 界面：思考过程可折叠显示；配置界面加“预设”“思考等级”“最大输出”“温度/top_p（留空不发）”，高级 compat 只放在 JSON 里。
6. 压缩和子 Agent 按用途取模型配置。

测试：每个 preset 一份请求体快照（golden JSON）；compat 覆盖优先级；旧配置迁移和写回；不同思考等级的请求体；usage 解析（OpenAI、DeepSeek、无 usage 三种 SSE 样本）；思考 + temperature 冲突时被丢掉且只警告一次。

### 第 3 阶段：原生 Anthropic Messages

1. `AnthropicMessages implements StreamFunction`：`system` 顶层，`tool_use`/`tool_result` 块，SSE 事件状态机，必填 `max_tokens`，`adaptive`/`budget_tokens` 两种思考，`cache_control` 断点，`anthropic-version` 头，`x-api-key` 或 Bearer（New API 的 `/v1/messages` 接受哪种需要确认）。
2. `AgentMessage.Assistant` 增加不透明的“回放数据”（思考签名、`redacted_thinking`）和来源标记（provider/api/model）；同模型才回传，换模型就丢。
3. 会话存盘加带版本号的扩展字段保存回放数据。

测试：录制的 SSE 样本（文本、思考+签名、redacted、工具调用、`overloaded_error`、`max_tokens` 截断）；回放请求体中签名原样保留；换模型时思考被丢；旧会话（无扩展字段）能恢复；对 New API `/v1/messages` 手动跑一个多轮工具回合。

### 第 4 阶段（按需）：OpenAI Responses / Gemini

只有真要用 OpenAI 推理模型的加密推理或 Gemini 3 的 `thoughtSignature` 时再做，复用第 3 阶段的“回放数据”机制。测试方式同上。

## 6. 开放问题（请用户决定）

1. **默认不发 temperature 可以吗？** 之前固定 0.4，不发等于用各家默认（OpenAI 默认 1）。生成结构的稳定性可能略变。建议：默认不发，需要的人在配置里填。
2. **New API 上的 Claude 思考怎么开？** 现在是靠模型名（例如带 `-thinking`）还是渠道设置？是否愿意用新版的 `model@thinking:adaptive` 写法？这决定 `anthropic-via-gateway` preset 怎么写。也请确认 New API 的版本。
3. **要不要第 3 阶段？** 也就是在 New API 上改走 `/v1/messages`，换来思考签名回传和显式缓存。工作量明显比 1、2 大。
4. 压缩和子 Agent 要不要默认用更便宜的模型 / 关思考？还是先全部跟 `main` 一样？
5. 思考过程要在聊天界面显示吗（默认折叠）？要不要存进会话？
6. 遇到“参数不支持”的 400，要不要自动去掉该参数重试一次？pi-ai 不这么做（靠 compat 声明）；自动重试省事，但会掩盖配置错误。建议不做，只把错误原文和“去 compat 里关掉某项”的提示显示出来。
7. 配置界面要做到多大？只加预设 + 思考等级 + 温度，还是要多连接、多模型的完整编辑？

## 7. 未能核实

- New API 的 Chat Completions 入口会怎样把 `reasoning_effort` 转成 Claude 的 `thinking`/`output_config`：没查到源码依据，第 2 阶段之前需要用用户的网关实测。
- 搜索摘要说 Anthropic 从 API 版本 2026-10-21 起对所有模型拒绝 `temperature/top_p/top_k`。官方页面没能直接打开核对，所以这里只把它当作“默认不发”的又一条理由。
- 网关是否透传 `cache_control`、Claude 思考签名，以及 `/v1/messages` 用哪种鉴权头，都依赖具体部署，需要实测。

## 参考

- pi-ai 1.6.0：`dist/types.d.ts`、`dist/providers/compat-schema.d.ts`、`dist/providers/model-schema.d.ts`、`dist/api/openai-completions.js`（`detectCompat`/`getCompat`/`buildParams`）、`dist/api/anthropic-messages.js`、`dist/api/openai-responses.js`、`dist/api/simple-options.js`、`dist/api/transform-messages.js`、`dist/providers/data/anthropic.json`、`deepseek.json`、`README.md`（OpenAI Compatibility Settings 一节）
- Anthropic 思考文档：https://platform.claude.com/docs/en/build-with-claude/thinking ，https://docs.claude.com/en/docs/build-with-claude/extended-thinking
- New API：https://github.com/QuantumNous/new-api/blob/main/README.en.md ，https://github.com/QuantumNous/new-api/releases ，https://newreleases.io/project/github/QuantumNous/new-api/release/v1.0.0-rc.33 ，https://github.com/QuantumNous/new-api/pull/7137 ，https://github.com/QuantumNous/new-api/pull/5825 ，https://github.com/QuantumNous/new-api/issues/7201
