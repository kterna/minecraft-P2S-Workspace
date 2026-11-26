# LLM 连接与参数

## 选择连接

配置文件是 `config/p2s_client.json`。顶层 `apiUrl`、`apiKey`、`model`、`useStreaming`、`useToolCall`、`httpTimeoutSeconds`、`temperature` 和 `maxTokens` 继续有效。没有 `llm` 的旧文件走 Chat Completions。游戏内 LLM 设置可编辑地址、凭据、模型、协议、思考等级和温度。高级字段放进 `llm` 对象。

| `llm.api` | `apiUrl` 示例 | 凭据和请求 |
|---|---|---|
| `openai-completions` | `https://api.openai.com/v1/chat/completions` | Bearer；兼容网关填完整的 Chat 端点。DeepSeek、OpenRouter、OpenAI 和 Mistral 有独立的默认字段映射。 |
| `openai-responses` | `https://api.openai.com/v1/responses` | Bearer；无服务端会话状态，按本地历史回放工具和加密 reasoning item。 |
| `anthropic-messages` | `https://api.anthropic.com/v1/messages` | `x-api-key` + `anthropic-version`。只接受 Bearer 的代理可设 `llm.compat.authHeader="bearer"`。 |
| `google-generative-ai` | `https://generativelanguage.googleapis.com/v1beta` | `x-goog-api-key`；客户端拼接模型和 generateContent 或 streamGenerateContent 方法。 |
| `google-vertex` | `https://us-central1-aiplatform.googleapis.com/v1/projects/PROJECT/locations/us-central1/publishers/google` | `apiKey` 填有效的 OAuth access token。令牌到期需更新。支持 API key 的端点可设 `llm.providerOptions.auth="apiKey"`。 |
| `bedrock-converse` | `https://bedrock-runtime.us-east-1.amazonaws.com` | 必填 `llm.providerOptions.region`。Bedrock API key 放顶层 `apiKey`；SigV4 则在 `providerOptions` 填 `accessKeyId`、`secretAccessKey`，临时凭据另填 `sessionToken`，此时 `apiKey` 可留空。 |
| `mistral` | `https://api.mistral.ai/v1/chat/completions` | Mistral Chat 的 HTTP 方法、流和工具结构沿用 Chat Completions；处理其 thinking 内容块、9 字符工具 ID 和结果名称。 |

例如使用一个公开了原生 Responses 接口的网关时，可把以下字段加到原配置。密钥继续填原来的 `apiKey`。

```json
{
  "apiUrl": "https://gateway.example/v1/responses",
  "model": "MODEL_ID",
  "llm": {
    "api": "openai-responses",
    "thinking": "medium"
  }
}
```

主 Agent 使用配置的流式开关，子 Agent 沿用同一模型，覆盖超时并走非流式。压缩也走同协议的非流式请求；对要求声明工具的原生协议，先把历史工具调用和结果转成供总结的文字。停止操作会取消进行中的 HTTP 请求。Bedrock 使用 AWS 二进制事件流，其余流式接口使用 SSE。协议错误或不完整的工具调用不会进入工具执行。

历史仍用原有会话消息格式。附加字段保存 Responses 的加密 reasoning、Anthropic 的签名/脱敏块、Gemini/Vertex 的原始 parts、Bedrock reasoning，以及 Chat 服务保存的 reasoning 扩展。签名只随同一协议、连接地址与模型回放。换模型后使用历史的文本和工具调用。旧存档缺少的签名无法补造；严格要求历史签名的模型可能需要新会话。用量依服务商返回值写入日志；费用及本地用量计费表尚未实现。

## 参数表

“已”表示客户端识别并组装字段，“透”表示可通过 `llm.extraBody` 手工提供服务商接受的字段，“无”表示这一层没有相应能力。透传按**顶层字段**替换，JSON null 删除字段。覆盖 `generationConfig`、`toolConfig` 或 `inferenceConfig` 时须自行保留需要的子项。显式 HTTP 头放 `llm.headers`。

| 参数 | Chat | Responses | Anthropic | Gemini / Vertex | Bedrock | Mistral Chat |
|---|---|---|---|---|---|---|
| 温度 `temperature`、`llm.topP` | 已 | 已；默认在启用思考时省略 | 已；按思考和模型限制省略 | 已 | 已；Claude 按限制省略 | 已 |
| 输出上限 `maxTokens` | 已，按预设选择字段 | 已，至少 16 | 已；默认 8192，预算思考时额外预留预算 | 已 | 已 | 已 |
| 思考等级 `llm.thinking` | 已，按网关方言 | 已，effort | 已，预算或 adaptive + effort | 已，预算或 level | 已，Claude；其他模型透 | 已，高或关 |
| 思考预算 `llm.thinkingBudget` | 无统一字段 | 无 | 已，预算模式 | 已，预算模式 | 已，Claude 预算模式 | 无 |
| 签名 / 工具关联回放 | 已，按兼容模式 | 已，加密 item | 已，签名或 redacted block | 已，thoughtSignature | 已，reasoningContent | 已，thinking 块 |
| 缓存控制 | 透 | 透 | 已，`llm.compat.cacheControl=true` 的临时断点 | 透，cachedContent | 透，cachePoint 所在字段 | 透，取决于服务 |
| 结束序列 | 透 | 无 | 透 | 透 | 透 | 透 |
| 工具选择策略 | 已，auto；其他透 | 透 | 透 | 透 | 透 | 已，auto；其他透 |
| 并行工具请求开关 | 透 | 透 | 透 | 无独立开关 | 依模型透 | 透 |
| 结构化输出 / response format | 透 | 透 | 透 | 透 | 依模型透 | 透 |
| 服务等级 / service tier | 透 | 透 | 透 | 依端点透 | 透 | 依端点透 |
| 流式/非流式、工具、停止、错误处理 | 已 | 已 | 已 | 已 | 已 | 已 |
| 多连接库、成本表、自动更换凭据 | 无 | 无 | 无 | 无 | 无 | 无 |

不设思考等级时，模型自行决定是否思考。可选 `off/minimal/low/medium/high/xhigh/max`；模型可用档位较少时会映射。Gemini 3 Pro 等不能关闭思考的模型在 off 时降到可用低档。Responses 在默认档也请求加密 reasoning，以便 `store=false` 场景恢复工具历史。Claude 原生预算模式要求思考预算至少 1024 且小于输出上限。

自定义 Chat 网关可在 `llm.compat` 设 `preset`（`generic/openai/deepseek/openrouter/mistral/anthropic-via-gateway`），或覆盖 `thinkingFormat`、`maxTokensField`、`supportsUsageInStreaming`、`requiresReasoningContent`、`supportsTemperature`、`supportsTopP`、`echoReasoning`。New API 下游可能切换模型，默认不会替网关注入 Claude 的思考设置。Vertex 令牌自动刷新、AWS 凭据链、Mistral 服务端托管对话、Gemini 主动开启分片函数参数、图像/音频目前没有客户端流程。

## 需要决定的深度

1. 是否需要在游戏内保存多个连接、为子 Agent 和上下文压缩单独选模型与预算。
2. 缓存、结构化输出、服务等级和工具选择，哪些值得做成可校验的控件，哪些继续留给 JSON 高级设置。
3. 要不要用可维护的模型目录精确列出思考档位、上下文和定价；目前兼容预设加本地覆盖足以控制字段。
4. 是否展示并默认折叠思考、缓存及用量；是否需要自动刷新的 Vertex、AWS 登录方式。

协议参考：[OpenAI Responses](https://platform.openai.com/docs/api-reference/responses)、[Anthropic Messages](https://platform.claude.com/docs/en/api/messages/create)、[Gemini](https://ai.google.dev/api/generate-content)、[Vertex REST](https://cloud.google.com/vertex-ai/generative-ai/docs/reference/rest/v1/projects.locations.publishers.models/generateContent)、[Bedrock ConverseStream](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ConverseStream.html)、[Mistral Chat](https://docs.mistral.ai/api/endpoint/chat)。
